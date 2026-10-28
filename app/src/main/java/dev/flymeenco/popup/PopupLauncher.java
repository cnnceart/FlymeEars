package dev.flymeenco.popup;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 唤起 Flyme 原生耳机弹窗（{@code com.meizu.bluetooth.sdk.MzfpDailogActivity}）。
 *
 * <p>逆向结论（TWS.apk 100.12.23）：
 * <ul>
 *   <li>弹窗是 {@code MzfpDailogActivity}，导出、无权限校验，底部 gravity 的对话框；</li>
 *   <li>电量与名称来自 Intent 里的 {@code MzfpDevice} Parcelable（
 *       {@code MzfpDeviceConnectedFragment} 直接读 {@code getLeftBattery()} 等）；</li>
 *   <li>{@code NETWORK_ENABLE=false} 时使用内置图 {@code tws_2311}，不联网、
 *       也不设置名称（名称由模块在宿主进程里补）。</li>
 * </ul>
 *
 * <p>因为该 Parcelable 的类只存在于耳机 App 里，这里用
 * {@code createPackageContext(..., CONTEXT_INCLUDE_CODE)} 借它的 ClassLoader 构造实例。
 */
public final class PopupLauncher {

    private static final String TAG = "FlymeEnco";
    private static final String PACKAGE = "com.meizu.earphone";
    private static final String ACTIVITY = "com.meizu.bluetooth.sdk.MzfpDailogActivity";

    private PopupLauncher() {
    }

    /** 找到已配对的 OPPO / Enco 耳机，用于测试与真实连接时复用。 */
    public static BluetoothDevice findEncoDevice() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return null;
            }
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                if (name == null) {
                    continue;
                }
                String normalized = name.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
                if (normalized.contains("oppo") || normalized.contains("enco")) {
                    return device;
                }
            }
        } catch (SecurityException error) {
            Log.w(TAG, "popup: 缺少蓝牙权限");
        }
        return null;
    }

    public static boolean show(Context context, BluetoothDevice device, int left, int right, int box) {
        if (device == null) {
            Log.w(TAG, "popup: 没有可用的 OPPO/Enco 设备");
            return false;
        }
        try {
            Context earphone = context.createPackageContext(PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader loader = earphone.getClassLoader();
            Class<?> type = Class.forName("com.meizu.bluetooth.sdk.MzfpDevice", true, loader);
            Object value = type.getConstructor(BluetoothDevice.class).newInstance(device);

            set(type, value, "setDeviceId", String.class, device.getAddress());
            set(type, value, "setLeftBattery", int.class, left);
            set(type, value, "setRightBattery", int.class, right);
            set(type, value, "setBoxBattery", int.class, box);
            set(type, value, "setShowTime", long.class, System.currentTimeMillis());

            Intent intent = new Intent()
                    .setClassName(PACKAGE, ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("DIALOG_TYPE_KEY", 1)
                    .putExtra("MZFP_DEVICE_KEY", (Parcelable) value)
                    .putExtra("NETWORK_ENABLE", false)
                    .putExtra("IS_CONNECTED", true);
            context.startActivity(intent);
            Log.i(TAG, "popup: 已唤起原生弹窗 " + device.getAddress());
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "popup: 唤起失败", error);
            return false;
        }
    }

    private static void set(Class<?> type, Object target, String method, Class<?> parameter, Object value)
            throws ReflectiveOperationException {
        Method setter = type.getMethod(method, parameter);
        setter.invoke(target, value);
    }
}
