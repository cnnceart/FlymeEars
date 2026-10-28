package dev.flymeenco.bt;

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

import dev.flymeenco.protocol.OppoWireCodec;
import dev.flymeenco.protocol.OppoWireConfig;

/**
 * 与 OPPO Enco 耳机建立私有 RFCOMM 通道，读取电量 / 降噪状态并下发控制。
 *
 * <p>这条链路替代「欢律」：不依赖任何厂商 App，直接说耳机自己的协议。
 * 连接建立在独立工作线程上，所有回调切回主线程，避免阻塞宿主进程。
 */
public final class EncoLink {

    private static final String TAG = "FlymeEnco";

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

    public EncoSnapshot snapshot() {
        return snapshot;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start(String address, String name, Listener callback) {
        stop();
        listener = callback;
        config = OppoWireConfig.forDeviceName(name);
        snapshot = EncoSnapshot.builder(address, name).build();
        running.set(true);
        worker = new Thread(() -> run(address, name), "FlymeEnco-Link");
        worker.start();
    }

    public void stop() {
        running.set(false);
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
        int primary = switch (flymeMode) {
            case EncoSnapshot.MODE_OFF -> config.offPrimary();
            case EncoSnapshot.MODE_ANC -> config.ancPrimary();
            case EncoSnapshot.MODE_TRANSPARENCY -> OppoWireConfig.TRANSPARENCY_PRIMARY;
            default -> -1;
        };
        if (primary < 0) {
            log("未知模式：" + flymeMode);
            return;
        }
        try {
            stream.write(OppoWireCodec.setAnc(primary));
            stream.flush();
            // 发送成功不等于生效，随后主动读回一次。
            stream.write(OppoWireCodec.QUERY_ANC_PACKET);
            stream.flush();
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
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                fail("设备不支持蓝牙");
                return;
            }
            BluetoothDevice device = adapter.getRemoteDevice(address);
            local = device.createRfcommSocketToServiceRecord(OPPO_RFCOMM_UUID);
            socket = local;
            local.connect();
            output = local.getOutputStream();
            InputStream input = local.getInputStream();
            log("RFCOMM 已连接：" + name + "（" + address + "）");
            publish(EncoSnapshot.builder(snapshot).connected(true).build());

            writeInitialQueries();
            readLoop(input);
        } catch (SecurityException error) {
            fail("缺少蓝牙权限或被系统拒绝");
        } catch (IOException error) {
            if (running.get()) {
                fail("连接失败：" + error.getClass().getSimpleName());
            }
        } finally {
            closeQuietly();
            if (running.get()) {
                publish(EncoSnapshot.builder(snapshot).connected(false).protocolReady(false).build());
            }
            running.set(false);
        }
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
            publish(builder.build());
            return;
        }

        OppoWireCodec.AncState anc = OppoWireCodec.parseAncState(frame);
        if (anc != null) {
            int mode = toFlymeMode(anc);
            if (mode > 0) {
                publish(EncoSnapshot.builder(snapshot).protocolReady(true).mode(mode).build());
            }
        }
    }

    private int toFlymeMode(OppoWireCodec.AncState state) {
        int primary = state.primary();
        Integer secondary = state.secondary();
        if (primary == config.ancPrimary() && (secondary == null || secondary == 0x00)) {
            return EncoSnapshot.MODE_ANC;
        }
        if (primary == config.offPrimary() && (secondary == null || secondary == 0x00)) {
            return EncoSnapshot.MODE_OFF;
        }
        if (primary == OppoWireConfig.TRANSPARENCY_PRIMARY && (secondary == null || secondary == 0x00)) {
            return EncoSnapshot.MODE_TRANSPARENCY;
        }
        if (primary == 0x00 && secondary != null && (secondary == 0x01 || secondary == 0x02)) {
            return EncoSnapshot.MODE_TRANSPARENCY;
        }
        if (primary == 0x00 && secondary != null && secondary == 0x08) {
            return EncoSnapshot.MODE_ANC;
        }
        if (primary == 0x08 && (secondary == null || secondary == 0x00)) {
            return EncoSnapshot.MODE_OFF;
        }
        if ((primary == 0x10 || primary == 0x20 || primary == 0x40 || primary == 0x80)
                && (secondary == null || secondary == 0x00)) {
            return EncoSnapshot.MODE_ANC;
        }
        return 0;
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
