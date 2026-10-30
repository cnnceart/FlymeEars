package io.github.cnnceart.flymeears.systemui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import io.github.cnnceart.flymeears.bt.EncoLink;
import io.github.cnnceart.flymeears.bt.EncoSnapshot;
import io.github.cnnceart.flymeears.popup.PopupLauncher;

/**
 * SystemUI 进程内的耳机会话：发现 OPPO/Enco 输出设备 → 建立直连 →
 * 拿到真实电量后拉起 Flyme 原生弹窗。
 *
 * <p>放在 SystemUI 是因为它常驻、且能以系统身份启动宿主 Activity；
 * 弹窗只在该设备本次连接后展示一次。
 */
public final class EncoSession implements EncoLink.Listener {

    private static final String TAG = "FlymeEars";

    private static volatile EncoSession instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EncoLink link = new EncoLink();

    private boolean audioCallbackRegistered;
    private String currentAddress;
    private String currentName;
    private boolean popupShown;
    /** 最近一次广播出去的连接态；null 表示本进程还没发过。 */
    private volatile Boolean publishedConnected;
    /** 用户主动断开后先别自动回连，等音频路由消失再恢复。 */
    private volatile boolean userDisconnected;
    private volatile EncoSnapshot snapshot;
    private volatile boolean lowLatency;
    private volatile boolean dualConnection;
    private volatile boolean personalSound;
    private volatile int codecType = -1;
    private volatile boolean hiRes;
    /** 最近一次真实上报的电量：耳机在耳时充电盒不回报，沿用旧值比显示 -1% 好。 */
    private int lastLeft = -1;
    private int lastRight = -1;
    private int lastCase = -1;
    /** 0x0112 多设备表，断开后仍保留，避免双连页被刷空。 */
    private volatile String[] dualDevices = new String[0];
    /**
     * 最近一次充电状态。断链（盒子插电、关盖、进盒）时链路会掉一下，
     * 这里要沿用旧值，不能因为一次断连就把闪电清掉。
     */
    private volatile boolean leftCharging;
    private volatile boolean rightCharging;
    private volatile boolean caseCharging;

    private EncoSession(Context context) {
        this.context = context;
    }

    /** 当前会话（可能为 null，表示 SystemUI 还没启动过会话）。 */
    public static EncoSession instance() {
        return instance;
    }

    /** 卡片 Hook 读取当前电量与充电状态用。 */
    public static EncoSnapshot currentSnapshot() {
        EncoSession session = instance;
        return session == null ? null : session.snapshot;
    }

    public static void start(Context context) {
        EncoSession local = instance;
        if (local == null) {
            synchronized (EncoSession.class) {
                local = instance;
                if (local == null) {
                    local = new EncoSession(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        local.loadLastBattery();
        local.loadFlags();
        local.attach();
    }

    private void attach() {
        if (audioCallbackRegistered) {
            return;
        }
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) {
            return;
        }
        audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                reconcile();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                reconcile();
            }
        }, main);
        audioCallbackRegistered = true;
        reconcile();
        main.postDelayed(watchdog, WATCHDOG_INTERVAL);
        Log.i(TAG, "session: 已监听音频设备");
    }

    /**
     * 看门狗：耳机收进盒子会主动断开 RFCOMM，重试耗尽后音频回调不会再触发，
     * 这里周期性补连，保证拿出来戴上时能自动恢复。
     */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (currentAddress == null) {
                reconcile();
            }
            String address = currentAddress;
            if (address != null && !link.isRunning() && !userDisconnected) {
                Log.i(TAG, "session: 补连 " + address);
                link.start(address, currentName, EncoSession.this);
            }
            main.postDelayed(this, WATCHDOG_INTERVAL);
        }
    };

    private static final long WATCHDOG_INTERVAL = 20_000L;

    public EncoSnapshot snapshot() {
        return snapshot;
    }

    /**
     * 用户主动断开：先停私有链路，再断 A2DP。
     *
     * <p>只 stop 链路不解决任何问题——音频路由还在，看门狗又会连回来，
     * 所以要连蓝牙音频一起断，等用户重新连接时再自动接管。
     */
    public void disconnect() {
        String address = currentAddress;
        Log.i(TAG, "session: 用户断开耳机 " + address);
        userDisconnected = true;
        link.stop();
        currentAddress = null;
        popupShown = false;
        broadcastDisconnected();
        snapshot = null;
        if (address == null) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (Throwable error) {
            return;
        }
        try {
            adapter.getProfileProxy(context, new android.bluetooth.BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                    try {
                        proxy.getClass().getMethod("disconnect", BluetoothDevice.class)
                                .invoke(proxy, device);
                        Log.i(TAG, "session: 已请求断开 A2DP");
                    } catch (Throwable error) {
                        Log.w(TAG, "session: 断开 A2DP 失败", error);
                    } finally {
                        try {
                            adapter.closeProfileProxy(profile, proxy);
                        } catch (Throwable ignored) {
                            // 关闭代理失败不影响断开。
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, android.bluetooth.BluetoothProfile.A2DP);
        } catch (Throwable error) {
            Log.w(TAG, "session: 取 A2DP 代理失败", error);
        }
    }

    /** 当前是否有一副可控的 OPPO/Enco 耳机。 */
    public boolean active() {
        EncoSnapshot value = snapshot;
        return value != null && value.connected;
    }

    public int mode() {
        EncoSnapshot value = snapshot;
        return value == null ? 0 : value.mode;
    }

    public void cycleMode() {
        int current = mode();
        int next = switch (current) {
            case EncoSnapshot.MODE_ANC -> EncoSnapshot.MODE_TRANSPARENCY;
            case EncoSnapshot.MODE_TRANSPARENCY -> EncoSnapshot.MODE_OFF;
            default -> EncoSnapshot.MODE_ANC;
        };
        link.requestMode(next);
    }

    /** 详情页点选具体模式时使用。 */
    public void requestMode(int mode) {
        link.requestMode(mode);
    }

    /** 详情页「降噪方式」四档。 */
    public void requestAncLevel(int level) {
        link.requestAncLevel(level);
    }

    /** 功能开关（06 游戏模式 / 0B 黄金听感 / 11 设备双连）。 */
    public void requestFeature(int feature, boolean on) {
        link.requestFeature(feature, on);
        switch (feature) {
            case io.github.cnnceart.flymeears.protocol.OppoWireCodec.FEATURE_GAME_MODE ->
                    lowLatency = on;
            case io.github.cnnceart.flymeears.protocol.OppoWireCodec.FEATURE_DUAL_CONNECTION ->
                    dualConnection = on;
            case io.github.cnnceart.flymeears.protocol.OppoWireCodec.FEATURE_PERSONAL_SOUND ->
                    personalSound = on;
            default -> {
            }
        }
        saveFlags();
        EncoSnapshot value = snapshot;
        if (value != null) {
            broadcastState(value);
        }
    }

    /** 高品质音频编码。 */
    public void requestCodec(int codec) {
        requestCodec(codec, hiRes);
    }

    /** 高品质音频编码 + Hi-Res 开关。 */
    public void requestCodec(int codec, boolean hiResOn) {
        link.requestCodec(codec, hiResOn);
        codecType = codec;
        hiRes = hiResOn;
        saveFlags();
        EncoSnapshot value = snapshot;
        if (value != null) {
            broadcastState(value);
        }
    }

    /** 充电态（含"在盒即充电"推断），设备中心卡片用。 */
    public static boolean chargingOf(String side) {
        EncoSession session = instance;
        if (session == null) {
            return false;
        }
        return switch (side) {
            case "left" -> session.leftCharging;
            case "right" -> session.rightCharging;
            default -> session.caseCharging;
        };
    }

    /** 详情页「音效」行设置 EQ 模式。 */
    public void setEq(int mode) {
        link.requestEq(mode);
    }

    /** 「自定义 EQ」页下发 10 段增益曲线。 */
    public void setCurve(int[] configs) {
        link.requestCurve(configs);
        personalSound = true;
        saveFlags();
        EncoSnapshot value = snapshot;
        if (value != null) {
            broadcastState(value);
        }
    }

    /**
     * 详情页刚打开时来问一次状态：只回推快照并刷新电量，不改变模式。
     *
     * <p>早期版本把这次询问当成「循环切换」处理，打开详情页会顺带切 ANC，
     * 所以单独开一条只读通道。
     */
    public void publishState() {
        EncoSnapshot value = snapshot;
        if (value != null) {
            broadcastState(value);
        }
        link.refresh();
    }

    private void reconcile() {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) {
            return;
        }
        for (AudioDeviceInfo output : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (output.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    && output.getType() != AudioDeviceInfo.TYPE_BLE_HEADSET) {
                continue;
            }
            String address = output.getAddress();
            if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
                continue;
            }
            String name = resolveName(output, address);
            if (name == null || !looksLikeEnco(name)) {
                continue;
            }
            if (userDisconnected) {
                return;
            }
            if (!address.equals(currentAddress)) {
                currentAddress = address;
                currentName = name;
                popupShown = false;
                Log.i(TAG, "session: 连接 " + name + "（" + address + "）");
                link.start(address, name, this);
            }
            return;
        }
        if (currentAddress != null || userDisconnected) {
            if (userDisconnected && currentAddress == null) {
                // 路由已经消失，允许下一次自动接管。
                userDisconnected = false;
            }
            Log.i(TAG, "session: 设备已断开，释放链路");
            if (publishedConnected == null || publishedConnected) {
                broadcastDisconnected();
            }
            currentAddress = null;
            popupShown = false;
            link.stop();
            snapshot = null;
        } else if (publishedConnected == null || publishedConnected) {
            // SystemUI 重启后如果耳机本来就没连，也要补发一次“未连接”，
            // 否则耳机进程会保留上一次运行留下的过期 connected 状态。
            Log.i(TAG, "session: 启动时无设备，补发未连接");
            broadcastDisconnected();
        }
    }

    /** 把整份状态推给耳机 App（详情页）。 */
    private android.content.SharedPreferences batteryPrefs() {
        return context.getSharedPreferences("flymeears_battery",
                android.content.Context.MODE_PRIVATE);
    }

    /** 重启 SystemUI 后仍能显示最近一次真实电量（充电盒在耳时不上报）。 */
    private void loadLastBattery() {
        try {
            android.content.SharedPreferences prefs = batteryPrefs();
            lastLeft = prefs.getInt("left", -1);
            lastRight = prefs.getInt("right", -1);
            lastCase = prefs.getInt("case", -1);
        } catch (Throwable error) {
            Log.w(TAG, "session: 读取电量缓存失败", error);
        }
    }

    /**
     * 功能开关是耳机侧持久化的，SystemUI 重启后模块也要记住，
     * 否则页面会显示成关闭（耳机实际仍开着）。
     */
    private void loadFlags() {
        try {
            android.content.SharedPreferences prefs = batteryPrefs();
            dualConnection = prefs.getBoolean("dual_connection", false);
            lowLatency = prefs.getBoolean("low_latency", false);
            personalSound = prefs.getBoolean("personal_sound", false);
            codecType = prefs.getInt("codec", -1);
            hiRes = prefs.getBoolean("hi_res", false);
            leftCharging = prefs.getBoolean("left_charging", false);
            rightCharging = prefs.getBoolean("right_charging", false);
            caseCharging = prefs.getBoolean("box_charging", false);
        } catch (Throwable error) {
            Log.w(TAG, "session: 读取开关缓存失败", error);
        }
    }

    private void saveFlags() {
        try {
            batteryPrefs().edit()
                    .putBoolean("dual_connection", dualConnection)
                    .putBoolean("low_latency", lowLatency)
                    .putBoolean("personal_sound", personalSound)
                    .putInt("codec", codecType)
                    .putBoolean("hi_res", hiRes)
                    .putBoolean("left_charging", leftCharging)
                    .putBoolean("right_charging", rightCharging)
                    .putBoolean("box_charging", caseCharging)
                    .apply();
        } catch (Throwable error) {
            Log.w(TAG, "session: 保存开关缓存失败", error);
        }
    }

    private void saveLastBattery() {
        try {
            batteryPrefs().edit()
                    .putInt("left", lastLeft)
                    .putInt("right", lastRight)
                    .putInt("case", lastCase)
                    .apply();
        } catch (Throwable error) {
            Log.w(TAG, "session: 保存电量缓存失败", error);
        }
    }

    private void broadcastState(EncoSnapshot value) {
        publishedConnected = value.connected;
        if (value.dualDevices.length > 0) {
            dualDevices = value.dualDevices;
        }
        if (value.leftBattery >= 0) {
            lastLeft = value.leftBattery;
        }
        if (value.rightBattery >= 0) {
            lastRight = value.rightBattery;
        }
        if (value.caseBattery >= 0) {
            lastCase = value.caseBattery;
        }
        // 0x8106 在盒里不置 bit7，按 0x0109「在盒=4」补上充电态。
        leftCharging = value.leftCharging || value.leftStatus == 4;
        rightCharging = value.rightCharging || value.rightStatus == 4;
        caseCharging = value.caseCharging;
        saveLastBattery();
        saveFlags();
        try {
            android.content.Intent state = new android.content.Intent(
                    io.github.cnnceart.flymeears.EncoIntents.ACTION_STATE)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_MODE, value.mode)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_ANC_LEVEL, value.ancLevel)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_EQ, value.eqMode)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_LOW_LATENCY, lowLatency)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_DUAL_CONNECTION,
                            dualConnection)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_PERSONAL_SOUND,
                            personalSound)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_CODEC,
                            value.codecType >= 0 ? value.codecType : codecType)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_HI_RES, hiRes)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_WEAR_LEFT,
                            value.leftStatus == 7)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_WEAR_RIGHT,
                            value.rightStatus == 7)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_IN_BOX_LEFT,
                            value.leftStatus == 4)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_IN_BOX_RIGHT,
                            value.rightStatus == 4)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_WEAR_VALID,
                            value.leftStatus >= 0 || value.rightStatus >= 0)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_LEFT_CHARGING,
                            leftCharging)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_RIGHT_CHARGING,
                            rightCharging)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_BOX_CHARGING,
                            caseCharging)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_LEFT, lastLeft)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_RIGHT, lastRight)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_CASE, lastCase)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_CONNECTED, value.connected)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_NAME, currentName)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_DUAL_DEVICES,
                            dualDevices);
            context.sendBroadcast(new android.content.Intent(state).setPackage("com.meizu.earphone"));
            context.sendBroadcast(new android.content.Intent(state)
                    .setPackage(io.github.cnnceart.flymeears.BuildConfig.APPLICATION_ID));
        } catch (Throwable error) {
            Log.w(TAG, "session: 广播状态失败", error);
        }
    }

    /** 断开时也要广播一次，宿主的详情页/弹窗才能收起。 */
    private void broadcastDisconnected() {
        publishedConnected = false;
        try {
            android.content.Intent state = new android.content.Intent(
                    io.github.cnnceart.flymeears.EncoIntents.ACTION_STATE)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_CONNECTED, false)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_LEFT, lastLeft)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_RIGHT, lastRight)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_CASE, lastCase)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_NAME, currentName)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_DUAL_DEVICES,
                            dualDevices)
                    // 断开时这些开关仍要保持：耳机重启回来前页面不该被刷回默认值。
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_LOW_LATENCY, lowLatency)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_DUAL_CONNECTION,
                            dualConnection)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_PERSONAL_SOUND,
                            personalSound)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_CODEC, codecType)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_HI_RES, hiRes)
                    // 断链的一瞬间也要带着充电态，否则闪电会闪一下。
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_LEFT_CHARGING,
                            leftCharging)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_RIGHT_CHARGING,
                            rightCharging)
                    .putExtra(io.github.cnnceart.flymeears.EncoIntents.EXTRA_BOX_CHARGING,
                            caseCharging);
            context.sendBroadcast(new android.content.Intent(state).setPackage("com.meizu.earphone"));
            context.sendBroadcast(new android.content.Intent(state)
                    .setPackage(io.github.cnnceart.flymeears.BuildConfig.APPLICATION_ID));
        } catch (Throwable error) {
            Log.w(TAG, "session: 广播断开失败", error);
        }
    }

    private static boolean looksLikeEnco(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return normalized.contains("oppo") || normalized.contains("enco");
    }

    /**
     * 设备名优先用蓝牙层的完整名称。
     *
     * <p>{@code AudioDeviceInfo.getProductName()} 有时只回短名（"Enco X2"），
     * 卡片和详情页就会在两个名字之间跳，所以能用配对名就先用配对名。
     */
    private String resolveName(AudioDeviceInfo output, String address) {
        String bluetoothName = null;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                bluetoothName = adapter.getRemoteDevice(address).getName();
            }
        } catch (Throwable error) {
            Log.w(TAG, "session: 取蓝牙名失败（权限？）", error);
        }
        if (bluetoothName != null && !bluetoothName.isEmpty()) {
            return bluetoothName;
        }
        try {
            CharSequence product = output.getProductName();
            if (product != null && product.length() > 0) {
                return product.toString();
            }
        } catch (Throwable ignored) {
            // 取不到产品名时继续尝试蓝牙层。
        }
        return null;
    }

    @Override
    public void onSnapshot(EncoSnapshot value) {
        snapshot = value;
        if (value.codecType >= 0 && value.codecType != codecType) {
            codecType = value.codecType;
            saveFlags();
        }
        broadcastState(value);
        if (popupShown || !value.connected || !value.protocolReady || !value.hasBattery()) {
            return;
        }
        popupShown = true;
        BluetoothDevice device = device(value.address);
        if (device == null) {
            Log.w(TAG, "session: 找不到设备对象，跳过弹窗");
            return;
        }
        int left = value.leftBattery;
        int right = value.rightBattery;
        int box = value.caseBattery;
        Log.i(TAG, "session: 拉起原生弹窗 " + left + "/" + right + "/" + box);
        boolean leftCharging = value.leftCharging;
        boolean rightCharging = value.rightCharging;
        boolean boxCharging = value.caseCharging;
        main.post(() -> PopupLauncher.show(context, device, left, right, box,
                leftCharging, rightCharging, boxCharging));
    }

    @Override
    public void onLog(String message) {
        Log.i(TAG, "session: " + message);
    }

    private BluetoothDevice device(String address) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter == null ? null : adapter.getRemoteDevice(address);
        } catch (Throwable error) {
            return null;
        }
    }
}
