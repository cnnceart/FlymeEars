package io.github.cnnceart.flymeears.bt;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.cnnceart.flymeears.protocol.AncMapper;
import io.github.cnnceart.flymeears.protocol.OppoWireCodec;
import io.github.cnnceart.flymeears.protocol.OppoWireConfig;

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

    /** 下行帧统一从单线程写出：主线程只入队，帧不会和其它线程交错。 */
    private static final int MAX_PENDING_WRITES = 64;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "FlymeEars-Write");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger pendingWrites = new AtomicInteger();

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
        enqueue(OppoWireCodec.setAnc(primary));
        // 发送成功不等于生效：稍等片刻再读回，避免拿到切换前的旧状态。
        main.postDelayed(this::refresh, 600);
        log("已下发模式 " + EncoSnapshot.modeName(flymeMode));
    }

    /** 设置降噪档位（魅族 17 智能 / 18 深度 / 19 均衡 / 20 轻度）。 */
    public void requestAncLevel(int level) {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            log("链路未就绪，无法切换降噪档位");
            return;
        }
        int primary = AncMapper.levelToPrimary(level);
        if (primary < 0) {
            log("未知降噪档位：" + level);
            return;
        }
        enqueue(OppoWireCodec.setAnc(primary));
        main.postDelayed(this::refresh, 600);
        log("已下发降噪档位 " + level + "（0x"
                + String.format(java.util.Locale.ROOT, "%02X", primary) + "）");
    }

    /** 功能开关（游戏模式 06 / 黄金听感 0B / 设备双连 11）。 */
    public void requestFeature(int feature, boolean on) {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            log("链路未就绪，无法切换功能");
            return;
        }
        enqueue(OppoWireCodec.featureSwitch(feature, on));
        log(String.format(java.util.Locale.ROOT,
                "已下发功能 0x%02X = %s", feature, on ? "开" : "关"));
    }

    /** 高品质音频编码。 */
    public void requestCodec(int codec) {
        requestCodec(codec, false);
    }

    /** 高品质音频编码 + Hi-Res 开关。 */
    public void requestCodec(int codec, boolean hiRes) {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            log("链路未就绪，无法切换编码");
            return;
        }
        enqueue(OppoWireCodec.setCodec(codec, hiRes));
        main.postDelayed(this::refresh, 800);
        log(String.format(java.util.Locale.ROOT,
                "已下发编码 0x%02X，Hi-Res=%s", codec, hiRes ? "开" : "关"));
    }

    /** 查找耳机：0x0400 01 响铃 / 00 停止，实测均返回 0x8400 00。 */
    public void requestFind(boolean on) {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            log("链路未就绪，无法查找耳机");
            return;
        }
        enqueue(OppoWireCodec.packet(0x0400, new byte[]{(byte) (on ? 1 : 0)}));
        log(on ? "已请求耳机响铃" : "已请求停止响铃");
    }

    /** 设置 OPPO EQ 模式（0..3），成功后耳机会主动回 0x0504。 */
    public void requestEq(int mode) {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            log("链路未就绪，无法设置 EQ");
            return;
        }
        enqueue(OppoWireCodec.packet(0x0406, new byte[]{(byte) mode}));
        main.postDelayed(this::refreshEq, 600);
        log("已下发 EQ 模式 " + mode);
    }

    /**
     * 自定义 EQ：先选设备内档案，再写 12 段增益曲线，最后打开黄金听感。
     *
     * <p>帧格式来自欢律「黄金听感」抓包（见 docs/protocol-oppo-enco.md §17）：
     * {@code 0x0411 01 01 01 00 12} → {@code 0x040E 03 0C + 12 组 + uid + 名称}
     * → {@code 0x0403 0B 01}。实测 {@code 0x840E 00} 表示被接受。
     */
    /** 等待耳机回传滤波系数的状态：收到后要原样 0x0415 回填。 */
    private volatile int pendingProfileId = -1;
    private volatile long pendingAt;

    public void requestCurve(int[] nativeDb) {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            log("链路未就绪，无法下发自定义 EQ");
            return;
        }
        if (nativeDb == null || nativeDb.length == 0) {
            log("自定义 EQ 曲线为空");
            return;
        }
        int[] bands = io.github.cnnceart.flymeears.protocol.EqMapper.toOppoBands(nativeDb);
        String name = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date());
        // 阈值只是参数：真正生效的滤波系数由耳机计算并回传，必须再回填 0x0415。
        pendingProfileId = OppoWireCodec.HEARING_PROFILE_ID;
        pendingAt = android.os.SystemClock.elapsedRealtime();
        // 四帧必须连续写出，合并成一个写任务。
        enqueue(OppoWireCodec.selectHearingProfile(OppoWireCodec.HEARING_PROFILE_ID),
                OppoWireCodec.hearingCurve(bands, bands, OppoWireCodec.HEARING_PROFILE_ID, name),
                OppoWireCodec.hearingFilterRequest(bands, bands, OppoWireCodec.HEARING_PROFILE_ID),
                OppoWireCodec.featureSwitch(OppoWireCodec.FEATURE_PERSONAL_SOUND, true));
        log("已下发自定义 EQ：" + java.util.Arrays.toString(nativeDb)
                + " -> 6 段 " + java.util.Arrays.toString(bands) + "（" + name + "）"
                + "，等待滤波系数");
    }

    private void refreshEq() {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            return;
        }
        enqueue(OppoWireCodec.packet(0x010F));
    }

    public void refresh() {
        OutputStream stream = output;
        if (stream == null || !running.get()) {
            return;
        }
        enqueue(OppoWireCodec.QUERY_BATTERY_PACKET,
                OppoWireCodec.QUERY_ANC_PACKET,
                OppoWireCodec.packet(0x010F),
                OppoWireCodec.packet(0x0109),
                OppoWireCodec.packet(0x0114),
                OppoWireCodec.packet(0x0123),
                OppoWireCodec.packet(0x0112));
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

    /** 连接期间每 6 秒刷新一次电量/佩戴/模式；进盒、充电这类变化靠它收敛。 */
    private void startTicker() {
        stopTicker();
        Thread thread = new Thread(() -> {
            while (running.get() && socket != null) {
                try {
                    Thread.sleep(6_000L);
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

    private void writeInitialQueries() {
        // 先问特性，再读电量与模式；特性响应用来注册主动上报。
        enqueue(OppoWireCodec.QUERY_NOTIFICATION_SUPPORT_PACKET,
                OppoWireCodec.QUERY_BATTERY_PACKET,
                OppoWireCodec.QUERY_ANC_PACKET,
                OppoWireCodec.packet(0x010F),
                OppoWireCodec.packet(0x0109),
                OppoWireCodec.packet(0x0114),
                OppoWireCodec.packet(0x0123),
                OppoWireCodec.packet(0x0112));
    }

    /**
     * 把一次请求的所有帧合并后交给单线程 writer。
     *
     * <p>同一请求的多帧必须连续写出，不能和 ticker/读线程的写交错；主线程调用只入队。
     */
    private void enqueue(byte[]... frames) {
        if (frames == null || frames.length == 0 || !running.get()) {
            return;
        }
        int total = 0;
        for (byte[] frame : frames) {
            if (frame == null) {
                return;
            }
            total += frame.length;
        }
        byte[] data = new byte[total];
        int offset = 0;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 0, data, offset, frame.length);
            offset += frame.length;
        }
        int ticket = generation.get();
        if (pendingWrites.incrementAndGet() > MAX_PENDING_WRITES) {
            pendingWrites.decrementAndGet();
            log("写队列已满，丢弃一帧");
            return;
        }
        writer.execute(() -> {
            try {
                if (ticket != generation.get() || !running.get()) {
                    return;
                }
                OutputStream stream = output;
                if (stream == null) {
                    return;
                }
                stream.write(data);
                stream.flush();
            } catch (IOException error) {
                log("写入失败：" + error.getClass().getSimpleName());
                closeQuietly();
            } finally {
                pendingWrites.decrementAndGet();
            }
        });
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
        if (acceptFilterBlob(frame)) {
            return;
        }
        if (frame.command() == 0x8114 && frame.payload().length >= 2
                && (frame.payload()[0] & 0xFF) == 0) {
            int codec = frame.payload()[1] & 0xFF;
            log("当前编码类型：" + codec);
            publish(EncoSnapshot.builder(snapshot).codecType(codec).build());
            return;
        }
        if (frame.command() == 0x8123) {
            log("高清编码列表：" + OppoWireCodec.hex(frame.payload()));
            return;
        }
        if (frame.command() == 0x8112 && frame.payload().length >= 2) {
            parseMultiConnect(frame.payload());
            return;
        }
        if (frame.command() == 0x8108 && frame.payload().length >= 2) {
            parseKeyFunctions(frame.payload());
            return;
        }
        if (frame.command() == 0x8109 && frame.payload().length >= 2) {
            applyComponentStatus(frame.payload(), 1);
            return;
        }
        if (frame.command() == 0x0204 && frame.payload().length >= 2
                && (frame.payload()[0] & 0xFF) == 0x02) {
            applyComponentStatus(frame.payload(), 1);
            return;
        }
        if (frame.command() == 0x810F && frame.payload().length >= 2
                && (frame.payload()[0] & 0xFF) == 0) {
            int eq = frame.payload()[1] & 0xFF;
            log("EQ 模式：" + eq);
            publish(EncoSnapshot.builder(snapshot).eq(eq).build());
            return;
        }
        if (frame.command() == 0x0504 && frame.payload().length >= 1) {
            int eq = frame.payload()[0] & 0xFF;
            log("EQ 模式变化：" + eq);
            publish(EncoSnapshot.builder(snapshot).eq(eq).build());
            return;
        }
        if (frame.command() == 0x840E) {
            int status = frame.payload().length > 0 ? frame.payload()[0] & 0xFF : -1;
            log("自定义 EQ 曲线" + (status == 0 ? "已被耳机接受" : "被拒绝（0x"
                    + String.format(java.util.Locale.ROOT, "%02X", status) + "）"));
            return;
        }
        if (frame.command() == 0x8403) {
            boolean ok = frame.payload().length > 0 && (frame.payload()[0] & 0xFF) == 0;
            log("功能开关" + (ok ? "成功" : "被拒绝"));
            return;
        }
        if (frame.command() == 0x8411) {
            int status = frame.payload().length > 0 ? frame.payload()[0] & 0xFF : -1;
            log("听力档案选择" + (status == 0 ? "成功" : "失败（0x"
                    + String.format(java.util.Locale.ROOT, "%02X", status) + "）"));
            return;
        }
        if (frame.command() == 0x8406) {
            int status = frame.payload().length > 0 ? frame.payload()[0] & 0xFF : -1;
            log("EQ 设置" + (status == 0 ? "成功" : "失败（0x"
                    + String.format(java.util.Locale.ROOT, "%02X", status) + "）"));
            return;
        }
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
                EncoSnapshot.Builder builder = EncoSnapshot.builder(snapshot)
                        .protocolReady(true).mode(mode);
                int level = AncMapper.primaryToLevel(anc.primary());
                if (level > 0) {
                    builder.ancLevel(level);
                }
                EncoSnapshot next = builder.build();
                log("降噪模式：" + EncoSnapshot.modeName(mode)
                        + (level > 0 ? "（档位 " + level + "）" : ""));
                publish(next);
            }
            return;
        }

        log("未识别帧 cmd=0x" + String.format(java.util.Locale.ROOT, "%04X", frame.command())
                + " payload=" + OppoWireCodec.hex(frame.payload()));
    }

    /**
     * 解析 <status=0> <count> <component> <value>… 的组件状态表。
     * Enco X2 实测：4=在盒、5=出盒未戴、7=佩戴中（左右耳）；盒固定 4。
     */
    /**
     * 耳机对 0x0116 的回包就是 0x0415 要回填的滤波系数。
     *
     * <p>本链路里它可能以通知（0x0204 等）或 0x8416 的形式出现，结构和
     * {@code action(1) | dataLen(LE16) | data | uid(BE)} 一致。这里按结构识别：
     * 首字节为 3 且长度字段与总长吻合才认，避免误吃别的通知。
     */
    private boolean acceptFilterBlob(OppoWireCodec.Frame frame) {
        int profile = pendingProfileId;
        if (profile < 0
                || android.os.SystemClock.elapsedRealtime() - pendingAt > 8000L) {
            return false;
        }
        byte[] payload = frame.payload();
        if (payload.length < 8 || (payload[0] & 0xFF) != 0x03) {
            return false;
        }
        int length = (payload[1] & 0xFF) | ((payload[2] & 0xFF) << 8);
        if (length <= 0 || payload.length != length + 7) {
            return false;
        }
        byte[] data = new byte[length];
        System.arraycopy(payload, 3, data, 0, length);
        pendingProfileId = -1;
        log("收到滤波系数 " + length + " 字节（cmd=0x"
                + String.format(java.util.Locale.ROOT, "%04X", frame.command()) + "）");
        writeQuietly(OppoWireCodec.earScanData(data, profile));
        writeQuietly(OppoWireCodec.featureSwitch(OppoWireCodec.FEATURE_PERSONAL_SOUND, true));
        log("已回填 0x0415 并启用黄金听感");
        return true;
    }

    /** 0x0112：多设备/双连接信息。 */
    private void parseMultiConnect(byte[] payload) {
        int count = payload[1] & 0xFF;
        int offset = 2;
        java.util.ArrayList<String> current = new java.util.ArrayList<>();
        java.util.ArrayList<String> others = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (offset + 6 > payload.length) {
                break;
            }
            StringBuilder mac = new StringBuilder();
            for (int b = 0; b < 6; b++) {
                if (b > 0) {
                    mac.append(':');
                }
                mac.append(String.format(java.util.Locale.ROOT, "%02X", payload[offset + b]));
            }
            offset += 6;
            if (offset + 4 > payload.length) {
                break;
            }
            offset++;                       // 块长度
            int state = payload[offset++] & 0xFF;
            int flags = payload[offset++] & 0xFF;
            int nameLength = payload[offset++] & 0xFF;
            if (offset + nameLength > payload.length) {
                break;
            }
            String name = new String(payload, offset, nameLength,
                    java.nio.charset.StandardCharsets.UTF_8);
            offset += nameLength;
            int deviceType = ((flags >> 3) & 7);
            log("多设备[" + i + "] " + name + " " + mac + " state=" + state
                    + " current=" + ((flags & 1) != 0)
                    + " mainAudio=" + ((flags & 2) != 0)
                    + " audioActive=" + ((flags & 4) != 0)
                    + " type=" + deviceType);
            String entry = mac.toString() + '\u0001' + name;
            if ((flags & 1) != 0) {
                current.add(entry);
            } else {
                others.add(entry);
            }
        }
        // 详情页「设备双连」把第一条当本机、最后一条当另一台，这里保证当前设备排第一。
        current.addAll(others);
        publish(EncoSnapshot.builder(snapshot)
                .dualDevices(current.toArray(new String[0]))
                .build());
    }

    /** 0x0108：左右耳按键/快捷操作表。 */
    private void parseKeyFunctions(byte[] payload) {
        int count = payload[1] & 0xFF;
        int offset = 2;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count && offset + 4 <= payload.length; i++) {
            int deviceType = payload[offset] & 0xFF;
            int button = payload[offset + 1] & 0xFF;
            int action = payload[offset + 2] & 0xFF;
            int function = payload[offset + 3] & 0xFF;
            offset += 4;
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append("dev").append(deviceType)
                    .append("/btn").append(button)
                    .append("/act").append(action)
                    .append("->").append(function);
        }
        log("快捷操作表：" + text);
    }

    private void applyComponentStatus(byte[] payload, int headerLength) {
        if (headerLength >= payload.length) {
            return;
        }
        int count = payload[headerLength] & 0xFF;
        int left = -1;
        int right = -1;
        for (int i = 0; i < count; i++) {
            int base = headerLength + 1 + i * 2;
            if (base + 1 >= payload.length) {
                break;
            }
            int component = payload[base] & 0xFF;
            int value = payload[base + 1] & 0xFF;
            if (value == 0) {
                // 0 = 该组件暂时没上报（正在充电/唤醒），保持上一次的值，别把状态刷掉。
                continue;
            }
            if (component == 0x01) {
                left = value;
            } else if (component == 0x02) {
                right = value;
            }
        }
        EncoSnapshot current = snapshot;
        int previousLeft = current == null ? -1 : current.leftStatus;
        int previousRight = current == null ? -1 : current.rightStatus;
        int nextLeft = left >= 0 ? left : previousLeft;
        int nextRight = right >= 0 ? right : previousRight;
        if (nextLeft < 0 && nextRight < 0) {
            return;
        }
        if (nextLeft == previousLeft && nextRight == previousRight) {
            return;
        }
        log("佩戴状态：左=" + nextLeft + " 右=" + nextRight);
        publish(EncoSnapshot.builder(snapshot)
                .leftStatus(nextLeft)
                .rightStatus(nextRight)
                .build());
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
        enqueue(data);
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
        // 让旧链路排队的帧在重连后失效。
        generation.incrementAndGet();
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
