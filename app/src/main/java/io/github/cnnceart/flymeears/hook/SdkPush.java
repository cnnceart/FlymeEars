package io.github.cnnceart.flymeears.hook;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.cnnceart.flymeears.protocol.OppoWireCodec;

/**
 * 把模块状态按魅族 SDK 的回调链推给宿主。
 *
 * <p>真机的状态变化来自 SDK 进程：{@code MzBluetoothWrapper$MzBluetoothSDKCallback}
 * 写 {@code AudioFunctionStatusModel} → {@code device.m().i()} 通知 LiveData 观察者 →
 * {@code BluetoothConnectionCallbackHandler} 分发连接/降噪/佩戴事件。页面只监听这些
 * 回调，模块因此不再直接调 {@code loadDeviceData()} 之类的页面方法。
 *
 * <p>连接状态走 {@code handleConnectedStateChanged(deviceId, 2/0)}：这是 SDK 收到
 * state 变化后的原生处理入口，会自己读状态模型并触发 {@code deviceConnectionSuccessful}
 * / {@code deviceDisconnected}。
 */
final class SdkPush {

    private static final String TAG = "FlymeEars";
    /** 耳机进盒时 RFCOMM 会先断一次，延迟确认避免详情页反复弹断开提示。 */
    private static final long DISCONNECT_DEBOUNCE_MS = 2500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Runnable pendingDisconnect;
    private static boolean haveConnected;
    private static boolean lastConnected;
    private static int lastMode = -1;
    private static int lastLevel = -1;
    private static int lastEq = -2;
    private static int lastHighQuality = -1;
    private static int lastLhdcMode = -1;
    private static boolean lastGameMode;
    private static boolean lastDualConnection;
    private static String lastDevices;
    private static String lastName;
    private static String lastWear;
    private static String lastBattery;

    private SdkPush() {
    }

    /** ModuleState 变化后调用：只推变化的字段，避免刷屏。 */
    static void sync() {
        Object device = Host.device();
        Object callback = Host.sdkCallback();
        String deviceId = Host.deviceId();
        if (device == null || callback == null || deviceId == null) {
            return;
        }
        if (!ModuleState.hasState()) {
            // 还没有任何广播时不要推“断开”，否则详情页会立刻弹断开提示。
            return;
        }
        boolean connected = ModuleState.connected();
        boolean pushed = false;
        if (!haveConnected || connected != lastConnected) {
            haveConnected = true;
            lastConnected = connected;
            pushConnection(connected);
            pushed = true;
        }
        int mode = ModuleState.mode();
        if (mode > 0 && mode != lastMode) {
            lastMode = mode;
            pushed = true;
            Host.invoke(callback, "onNoiseControlModeChanged", deviceId, mode);
            Log.i(TAG, "push: 降噪模式回调 -> " + EncoSnapshotName.of(mode));
        }
        int level = ModuleState.ancLevel();
        if (level > 0 && level != lastLevel) {
            lastLevel = level;
            pushed = true;
            Host.invoke(callback, "onNoiseReductionModeChanged", deviceId, level);
            Log.i(TAG, "push: 降噪档位回调 -> " + level);
        }
        int eq = ModuleState.eq();
        if (eq >= 0 && eq != lastEq) {
            lastEq = eq;
            pushed = true;
            Host.invoke(callback, "onEqModeChanged", deviceId, eq);
        }
        int highQuality = highQualityModel();
        if (highQuality > 0 && highQuality != lastHighQuality) {
            lastHighQuality = highQuality;
            pushed = true;
            Host.invoke(callback, "onHighQualityAudioModeChanged", deviceId, highQuality);
            Log.i(TAG, "push: 高品质音频回调 -> " + highQuality);
        }
        int lhdcMode = ModuleState.hiRes() ? 18 : 17;
        if (lhdcMode != lastLhdcMode) {
            lastLhdcMode = lhdcMode;
            pushed = true;
            Host.invoke(callback, "onLhdcAudioModeChanged", deviceId, lhdcMode);
        }
        boolean gameMode = ModuleState.lowLatency();
        if (gameMode != lastGameMode) {
            lastGameMode = gameMode;
            pushed = true;
            Host.invoke(callback, "onGameModeChanged", deviceId, gameMode);
        }
        boolean dual = ModuleState.dualConnection();
        if (dual != lastDualConnection) {
            lastDualConnection = dual;
            pushed = true;
            Host.invoke(callback, "onMultipleConnectionsEnabledChanged", deviceId, dual);
            Log.i(TAG, "push: 设备双连回调 -> " + dual);
        }
        Map<String, String> devices = ModuleState.dualDevices();
        String devicesSignature = devices.isEmpty() ? "" : devices.toString();
        if (!devicesSignature.equals(lastDevices)) {
            lastDevices = devicesSignature;
            if (!devices.isEmpty()) {
                pushed = true;
                Host.invoke(callback, "onMultipleConnectDevicesChanged", deviceId,
                        new LinkedHashMap<>(devices));
                Log.i(TAG, "push: 双连设备表回调 -> " + devices);
            }
        }
        String name = ModuleState.name();
        if (name != null && !name.isEmpty() && !name.equals(lastName)) {
            lastName = name;
            pushed = true;
            Host.invoke(callback, "onDeviceNameChanged", deviceId, name);
        }
        if (ModuleState.wearValid()) {
            String wear = ModuleState.wearLeft() + "/" + ModuleState.wearRight() + "/"
                    + ModuleState.inBoxLeft() + "/" + ModuleState.inBoxRight();
            if (!wear.equals(lastWear)) {
                lastWear = wear;
                pushed = true;
                Host.invoke(callback, "onEarphoneWearingStatusChanged", deviceId,
                        Host.wearingStatus());
            }
        }
        String battery = ModuleState.left() + "/" + ModuleState.right() + "/" + ModuleState.box()
                + "/" + ModuleState.leftCharging() + ModuleState.rightCharging()
                + ModuleState.boxCharging();
        if (!battery.equals(lastBattery)) {
            lastBattery = battery;
            pushed = true;
            try {
                Host.invoke(callback, "onElectricityOfEarphoneChanged", deviceId, Host.battery());
            } catch (Throwable error) {
                Log.w(TAG, "push: 电量回调失败", error);
            }
        }
        // 没有真正推送时不要再投递 LiveData 事件，避免每个 tick 重绘多次。
        if (pushed) {
            Host.notifyObservers();
        }
    }

    /** 宿主侧读到的 model.o：1 AAC / 2 LHDC / 3 LDAC。 */
    private static int highQualityModel() {
        int codec = ModuleState.codec();
        if (codec == OppoWireCodec.CODEC_AAC) {
            return 1;
        }
        if (codec == OppoWireCodec.CODEC_LDAC) {
            return 3;
        }
        if (codec == OppoWireCodec.CODEC_LHDC) {
            return 2;
        }
        // 还没读到编码时返回 0（未知），由调用方跳过推送，避免把高品质页刷成 AAC。
        return 0;
    }

    private static long lastConnectPush;
    private static boolean connectPushed;
    private static Runnable pendingConnectPush;
    private static final long CONNECT_THROTTLE_MS = 10_000L;

    private static void pushConnection(boolean connected) {
        cancelPendingDisconnect();
        if (connected) {
            // 每次 RFCOMM 重连都走一遍 handleConnectedStateChanged 会让 SDK 反复弹
            // 原生连接弹窗（它的对话框有 double-dismiss 崩溃），这里限流。
            cancelPendingConnect();
            long now = android.os.SystemClock.elapsedRealtime();
            long wait = connectPushed ? lastConnectPush + CONNECT_THROTTLE_MS - now : 0L;
            if (wait > 0) {
                // 窗口内不能直接丢：断开→重连往往发生在 10s 内，窗口末补发一次。
                if (pendingConnectPush == null) {
                    pendingConnectPush = () -> {
                        pendingConnectPush = null;
                        if (!ModuleState.connected()) {
                            return;
                        }
                        lastConnectPush = android.os.SystemClock.elapsedRealtime();
                        Host.invoke(Host.wrapper(), "handleConnectedStateChanged",
                                Host.deviceId(), 2);
                        Log.i(TAG, "push: 连接回调（补发）-> deviceConnectionSuccessful");
                    };
                    MAIN.postDelayed(pendingConnectPush, wait + 50L);
                }
                Log.i(TAG, "push: 连接回调延后 " + wait + "ms");
                return;
            }
            connectPushed = true;
            lastConnectPush = now;
            Host.invoke(Host.wrapper(), "handleConnectedStateChanged", Host.deviceId(), 2);
            Log.i(TAG, "push: 连接回调 -> deviceConnectionSuccessful");
            return;
        }
        cancelPendingConnect();
        pendingDisconnect = () -> {
            pendingDisconnect = null;
            if (!ModuleState.connected()) {
                Host.invoke(Host.wrapper(), "handleConnectedStateChanged", Host.deviceId(), 0);
                // 设备中心卡片不跟着 Activity 走，单独再推一次。
                SdkStateHooks.pushCardNow();
                Log.i(TAG, "push: 连接回调 -> deviceDisconnected");
            }
        };
        MAIN.postDelayed(pendingDisconnect, DISCONNECT_DEBOUNCE_MS);
    }

    private static void cancelPendingConnect() {
        Runnable pending = pendingConnectPush;
        if (pending != null) {
            MAIN.removeCallbacks(pending);
            pendingConnectPush = null;
        }
    }

    private static void cancelPendingDisconnect() {
        Runnable pending = pendingDisconnect;
        if (pending != null) {
            MAIN.removeCallbacks(pending);
            pendingDisconnect = null;
        }
    }

    /** 只给日志用：模式名的轻量包装，避免这里再依赖 EncoSnapshot。 */
    private static final class EncoSnapshotName {
        private static String of(int mode) {
            return io.github.cnnceart.flymeears.bt.EncoSnapshot.modeName(mode);
        }
    }
}
