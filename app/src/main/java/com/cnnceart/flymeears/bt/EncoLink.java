package com.cnnceart.flymeears.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.cnnceart.flymeears.protocol.AncMapper;
import com.cnnceart.flymeears.protocol.OppoWireCodec;
import com.cnnceart.flymeears.protocol.OppoWireConfig;

/**
 * 与 OPPO Enco 耳机建立私有 RFCOMM 通道，读取电量 / 降噪状态并下发控制。
 *
 * <p>这条链路替代「欢律」：不依赖任何厂商 App，直接说耳机自己的协议。
 * 连接建立在独立工作线程上，所有回调切回主线程，避免阻塞宿主进程。
 */
public final class EncoLink {

    private static final String TAG = "FlymeEars";

    /** OPPO Enco 私有 RFCOMM 服务。 */
    public static final UUID OPPO_RFCOMM_UUID =
            UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5");

    public interface Listener {
        void onSnapshot(EncoSnapshot snapshot);

        void onLog(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Listener listener;
    private volatile BluetoothSocket socket;
    private volatile OutputStream output;
    private volatile EncoSnapshot snapshot;
    private volatile OppoWireConfig config = OppoWireConfig.STANDARD;
    private Thread worker;
    private Thread ticker;

    public EncoSnapshot snapshot() {
        return snapshot;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start(String address, String name, Listener callback) {
        start(address, name, OppoWireConfig.forDeviceName(name), callback);
    }

    public void start(String address, String name, OppoWireConfig wireConfig, Listener callback) {
        stop();
        listener = callback;
        config = wireConfig;
        snapshot = EncoSnapshot.builder(address, name).build();
        running.set(true);
        worker = new Thread(() -> run(address, name), "FlymeEars-Link");
        worker.start();
    }

    public void stop() {
        running.set(false);
        stopTicker();
        closeQuietly();
        Thread current = worker;
        worker = null;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
        }
        EncoSnapshot previous = snapshot;
        if (previous != null) {
            publish(EncoSnapshot.builder(previous).connected(false).protocolReady(false).build());
        }
    }

    /** Flyme 语义的模式（1 关闭 / 2 降噪 / 3 通透）写回耳机。 */
    public void requestMode(int flymeMode) {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            log("链路未就绪，无法切换模式");
            return;
        }
        int primary = AncMapper.toPrimary(flymeMode, config);
        if (primary < 0) {
            log("未知模式：" + flymeMode);
            return;
        }
        try {
            stream.write(OppoWireCodec.setAnc(primary));
            stream.flush();
            // 发送成功不等于生效：稍等片刻再读回，避免拿到切换前的旧状态。
            main.postDelayed(this::refresh, 600);
            log("已下发模式 " + EncoSnapshot.modeName(flymeMode));
        } catch (IOException error) {
            log("模式下发失败：" + error.getClass().getSimpleName());
            closeQuietly();
        }
    }

    public void refresh() {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            return;
        }
        try {
            stream.write(OppoWireCodec.QUERY_BATTERY_PACKET);
            stream.write(OppoWireCodec.QUERY_ANC_PACKET);
            stream.flush();
        } catch (IOException error) {
            log("刷新失败：" + error.getClass().getSimpleName());
            closeQuietly();
        }
    }

    private void run(String address, String name) {
        BluetoothSocket local = null;
        int attempt = 0;
        while (running.get() && attempt < 3) {
            attempt++;
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) {
                    fail("设备不支持蓝牙");
                    return;
                }
                BluetoothDevice device = adapter.getRemoteDevice(address);
                local = connect(device);
                socket = local;
                output = local.getOutputStream();
                InputStream input = local.getInputStream();
                log("RFCOMM 已连接：" + name + "（" + address + "）");
                publish(EncoSnapshot.builder(snapshot).connected(true).build());

                writeInitialQueries();
                startTicker();
                readLoop(input);
                break;
            } catch (SecurityException error) {
                fail("缺少蓝牙权限或被系统拒绝");
                break;
            } catch (IOException error) {
                if (!running.get()) {
                    break;
                }
                log("连接失败（第 " + attempt + " 次）：" + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : " / " + error.getMessage()));
                closeQuietly();
                if (attempt >= 3) {
                    fail("多次重连失败，已放弃");
                    break;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    break;
                }
            } finally {
                closeQuietly();
            }
        }
        stopTicker();
        if (running.get()) {
            publish(EncoSnapshot.builder(snapshot).connected(false).protocolReady(false).build());
        }
        running.set(false);
    }

    /** 连接期间每 30 秒刷新一次电量与模式，避免长期显示旧值。 */
    private void startTicker() {
        stopTicker();
        Thread thread = new Thread(() -> {
            while (running.get() && socket != null) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (!running.get() || socket == null) {
                    return;
                }
                refresh();
            }
        }, "FlymeEars-Tick");
        thread.setDaemon(true);
        ticker = thread;
        thread.start();
    }

    private void stopTicker() {
        Thread thread = ticker;
        ticker = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 先按 SDP UUID 建链；失败时按已知服务通道回退。
     *
     * <p>OPPO Enco 的私有服务在实测机型上落在 RFCOMM scn 15；SDP 查询失败
     * （例如厂商 App 已占用链路）时直接连通道更稳。
     */
    private BluetoothSocket connect(BluetoothDevice device) throws IOException {
        try {
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(OPPO_RFCOMM_UUID);
            socket.connect();
            log("通过 SDP UUID 建链");
            return socket;
        } catch (IOException error) {
            log("SDP 建链失败：" + describe(error) + "，尝试固定通道");
        }
        IOException last = null;
        for (int channel : new int[]{15, 1, 2, 3, 4}) {
            BluetoothSocket candidate = null;
            try {
                java.lang.reflect.Method create = device.getClass()
                        .getMethod("createRfcommSocket", int.class);
                create.setAccessible(true);
                candidate = (BluetoothSocket) create.invoke(device, channel);
                candidate.connect();
                log("通过 RFCOMM 通道 " + channel + " 建链");
                return candidate;
            } catch (Throwable error) {
                if (candidate != null) {
                    try {
                        candidate.close();
                    } catch (IOException ignored) {
                        // 关闭失败不影响换下一个通道。
                    }
                }
                last = error instanceof IOException io ? io : new IOException(error);
            }
        }
        throw last != null ? last : new IOException("RFCOMM 不可用");
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : " / " + error.getMessage());
    }

    private void writeInitialQueries() throws IOException {
        OutputStream stream = output;
        if (stream == null) {
            return;
        }
        // 先问特性，再读电量与模式；特性响应用来注册主动上报。
        stream.write(OppoWireCodec.QUERY_NOTIFICATION_SUPPORT_PACKET);
        stream.write(OppoWireCodec.QUERY_BATTERY_PACKET);
        stream.write(OppoWireCodec.QUERY_ANC_PACKET);
        stream.flush();
    }

    private void readLoop(InputStream input) throws IOException {
        OppoWireCodec.Decoder decoder = new OppoWireCodec.Decoder();
        byte[] buffer = new byte[256];
        while (running.get()) {
            int count = input.read(buffer);
            if (count < 0) {
                log("链路被耳机关闭");
                return;
            }
            List<OppoWireCodec.Frame> frames = decoder.offer(java.util.Arrays.copyOf(buffer, count));
            for (OppoWireCodec.Frame frame : frames) {
                handle(frame);
            }
        }
    }

    private void handle(OppoWireCodec.Frame frame) {
        log("帧 cmd=0x" + String.format(java.util.Locale.ROOT, "%04X", frame.command())
                + " payload=" + OppoWireCodec.hex(frame.payload()));
        if (frame.command() == 0x8404) {
            int status = frame.payload().length > 0 ? frame.payload()[0] & 0xFF : -1;
            if (status != 0) {
                log("耳机拒绝切换（状态 0x"
                        + String.format(java.util.Locale.ROOT, "%02X", status)
                        + "），常见原因：耳机在充电盒中或未佩戴");
            }
            return;
        }
        byte[] ids = OppoWireCodec.parseNotificationSupport(frame);
        if (ids != null) {
            StringBuilder hex = new StringBuilder();
            for (byte id : ids) {
                hex.append(String.format(java.util.Locale.ROOT, "%02X ", id));
            }
            log("特性上报 ID：" + hex.toString().trim());
            byte[] subscribable = filterDebugNotifications(ids);
            if (subscribable.length > 0) {
                writeQuietly(OppoWireCodec.registerNotifications(subscribable));
            }
            publish(EncoSnapshot.builder(snapshot).protocolReady(true).build());
            // 注册完成后再问一次，确保拿到电量与当前模式。
            writeQuietly(OppoWireCodec.QUERY_BATTERY_PACKET);
            writeQuietly(OppoWireCodec.QUERY_ANC_PACKET);
            return;
        }

        OppoWireCodec.BatteryState battery = OppoWireCodec.parseBatteryState(frame);
        if (battery != null) {
            EncoSnapshot.Builder builder = EncoSnapshot.builder(snapshot).protocolReady(true);
            if (battery.left() != null) {
                builder.left(battery.left().percent(), battery.left().charging());
            }
            if (battery.right() != null) {
                builder.right(battery.right().percent(), battery.right().charging());
            }
            if (battery.caseBattery() != null) {
                builder.caseBattery(battery.caseBattery().percent(), battery.caseBattery().charging());
            }
            EncoSnapshot next = builder.build();
            log("电量 L=" + next.leftBattery + "% R=" + next.rightBattery + "% Case=" + next.caseBattery + "%");
            publish(next);
            return;
        }

        OppoWireCodec.AncState anc = OppoWireCodec.parseAncState(frame);
        if (anc != null) {
            int mode = AncMapper.toFlymeMode(anc, config);
            if (mode != AncMapper.MODE_UNKNOWN) {
                log("降噪模式：" + EncoSnapshot.modeName(mode));
                publish(EncoSnapshot.builder(snapshot).protocolReady(true).mode(mode).build());
            }
            return;
        }

        log("未识别帧 cmd=0x" + String.format(java.util.Locale.ROOT, "%04X", frame.command())
                + " payload=" + OppoWireCodec.hex(frame.payload()));
    }

    private static byte[] filterDebugNotifications(byte[] ids) {
        int size = 0;
        for (byte id : ids) {
            if ((id & 0xF0) != 0xF0) {
                size++;
            }
        }
        byte[] out = new byte[size];
        int index = 0;
        for (byte id : ids) {
            if ((id & 0xF0) != 0xF0) {
                out[index++] = id;
            }
        }
        return out;
    }

    private void writeQuietly(byte[] data) {
        OutputStream stream = output;
        if (stream == null) {
            return;
        }
        try {
            stream.write(data);
            stream.flush();
        } catch (IOException error) {
            log("写入失败：" + error.getClass().getSimpleName());
        }
    }

    private void fail(String message) {
        log(message);
        publish(EncoSnapshot.builder(snapshot).connected(false).error(message).build());
    }

    private void publish(EncoSnapshot value) {
        if (value == null) {
            return;
        }
        snapshot = value;
        Listener target = listener;
        if (target != null) {
            main.post(() -> target.onSnapshot(value));
        }
    }

    private void log(String message) {
        Log.i(TAG, "link: " + message);
        Listener target = listener;
        if (target != null) {
            main.post(() -> target.onLog(message));
        }
    }

    private void closeQuietly() {
        output = null;
        BluetoothSocket local = socket;
        socket = null;
        if (local != null) {
            try {
                local.close();
            } catch (IOException ignored) {
                // 关闭失败不影响状态收敛。
            }
        }
    }
}
