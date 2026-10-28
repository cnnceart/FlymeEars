package com.cnnceart.flymeears.systemui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.cnnceart.flymeears.bt.EncoLink;
import com.cnnceart.flymeears.bt.EncoSnapshot;
import com.cnnceart.flymeears.popup.PopupLauncher;

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
    private volatile EncoSnapshot snapshot;
    private final java.util.List<Runnable> observers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile android.content.SharedPreferences preferences;

    private EncoSession(Context context) {
        this.context = context;
    }

    /** 当前会话（可能为 null，表示 SystemUI 还没启动过会话）。 */
    public static EncoSession instance() {
        return instance;
    }

    /** 注册状态变化回调，用于音量面板重绘。 */
    public void addObserver(Runnable observer) {
        observers.add(observer);
    }

    public static void start(Context context) {
        start(context, null);
    }

    /** prefs 为模块远程偏好，用于把设备地址/名称共享给耳机 App 进程。 */
    public static void start(Context context, android.content.SharedPreferences prefs) {
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
        if (prefs != null) {
            local.preferences = prefs;
        }
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
            String address = currentAddress;
            if (address != null && !link.isRunning()) {
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
            if (!address.equals(currentAddress)) {
                currentAddress = address;
                currentName = name;
                popupShown = false;
                remember(address, name);
                Log.i(TAG, "session: 连接 " + name + "（" + address + "）");
                link.start(address, name, this);
            }
            return;
        }
        if (currentAddress != null) {
            Log.i(TAG, "session: 设备已断开，释放链路");
            currentAddress = null;
            popupShown = false;
            link.stop();
            snapshot = null;
        }
    }

    /** 把当前耳机写进远程偏好，供 com.meizu.earphone 进程注入设备列表使用。 */
    private void remember(String address, String name) {
        android.content.SharedPreferences prefs = preferences;
        if (prefs == null) {
            return;
        }
        try {
            prefs.edit().putString("address", address).putString("name", name).apply();
        } catch (Throwable error) {
            Log.w(TAG, "session: 写远程偏好失败", error);
        }
    }

    /** 把整份状态推给耳机 App（详情页）和我们自己的 App（磁贴）。 */
    private void broadcastState(EncoSnapshot value) {
        try {
            android.content.Intent state = new android.content.Intent(
                    com.cnnceart.flymeears.EncoIntents.ACTION_STATE)
                    .putExtra(com.cnnceart.flymeears.EncoIntents.EXTRA_MODE, value.mode)
                    .putExtra(com.cnnceart.flymeears.EncoIntents.EXTRA_LEFT, value.leftBattery)
                    .putExtra(com.cnnceart.flymeears.EncoIntents.EXTRA_RIGHT, value.rightBattery)
                    .putExtra(com.cnnceart.flymeears.EncoIntents.EXTRA_CASE, value.caseBattery)
                    .putExtra(com.cnnceart.flymeears.EncoIntents.EXTRA_CONNECTED, value.connected)
                    .putExtra(com.cnnceart.flymeears.EncoIntents.EXTRA_NAME, currentName);
            context.sendBroadcast(new android.content.Intent(state).setPackage("com.meizu.earphone"));
            context.sendBroadcast(new android.content.Intent(state)
                    .setPackage(com.cnnceart.flymeears.BuildConfig.APPLICATION_ID));
        } catch (Throwable error) {
            Log.w(TAG, "session: 广播状态失败", error);
        }
    }

    private void broadcastMode(int mode) {
        try {
            EncoSnapshot value = snapshot;
            int battery = value == null ? -1
                    : (value.leftBattery >= 0 ? value.leftBattery : value.rightBattery);
            context.sendBroadcast(new android.content.Intent(com.cnnceart.flymeears.EncoIntents.ACTION_MODE)
                    .putExtra(com.cnnceart.flymeears.EncoIntents.EXTRA_MODE, mode)
                    .putExtra(com.cnnceart.flymeears.EncoIntents.EXTRA_BATTERY, battery)
                    .setPackage(com.cnnceart.flymeears.BuildConfig.APPLICATION_ID));
        } catch (Throwable error) {
            Log.w(TAG, "session: 广播模式失败", error);
        }
    }

    private static boolean looksLikeEnco(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return normalized.contains("oppo") || normalized.contains("enco");
    }

    private String resolveName(AudioDeviceInfo output, String address) {
        try {
            CharSequence product = output.getProductName();
            if (product != null && product.length() > 0) {
                return product.toString();
            }
        } catch (Throwable ignored) {
            // 取不到产品名时继续尝试蓝牙层。
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                BluetoothDevice device = adapter.getRemoteDevice(address);
                String name = device.getName();
                if (name != null) {
                    return name;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "session: 取设备名失败（权限？）", error);
        }
        return null;
    }

    @Override
    public void onSnapshot(EncoSnapshot value) {
        snapshot = value;
        broadcastMode(value.mode);
        broadcastState(value);
        for (Runnable observer : observers) {
            try {
                main.post(observer);
            } catch (Throwable error) {
                Log.w(TAG, "session: 通知观察者失败", error);
            }
        }
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
        main.post(() -> PopupLauncher.show(context, device, left, right, box));
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
