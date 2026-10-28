package dev.flymeears.hook;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 把第三方耳机注入魅族耳机 App 的设备列表。
 *
 * <p>数据流（TWS.apk 100.12.23）：
 * <pre>
 *   h5.b.c()  ──►  e6.a.f()  ──►  d6.a(包装)  ──►  DeviceListActivity 的适配器
 *   （同步 Set）      （构建列表）
 * </pre>
 *
 * <p>做法：
 * <ol>
 *   <li>Hook {@code h5.b.c()}：往返回的 Set 里补一条我们构造的 {@link Object AudioDevice}，
 *       这样列表 ViewModel 和后续所有按 ID 的查询都能看到它；</li>
 *   <li>Hook {@code e6.a.f()}：列表刷新后把我们的设备状态改成"已连接"，
 *       因为原生逻辑会用魅族 SDK 判定连接态，第三方设备永远是 false。</li>
 * </ol>
 *
 * <p>设备地址/名称来自模块远程偏好（SystemUI 会话连接耳机时写入），
 * 避免在耳机进程里申请蓝牙权限。
 */
public final class DeviceListHooks {

    private static final String TAG = "FlymeEars";
    private static final String STORE = "h5.b";
    private static final String VIEW_MODEL = "e6.a";
    private static final String AUDIO_DEVICE = "com.meizu.earphone.audiodo.model.AudioDevice";
    private static final String TRANSPORT_TYPE = "com.meizu.earphone.audiodo.mzbluetooth.TransportType";
    private static final String PREFS = "flymeears";

    private static ClassLoader loader;
    private static SharedPreferences preferences;
    private static Object injected;

    private DeviceListHooks() {
    }

    public static void install(XposedModule module, ClassLoader classLoader) {
        loader = classLoader;
        try {
            preferences = module.getRemotePreferences(PREFS);
        } catch (Throwable error) {
            Log.w(TAG, "device list: 读取远程偏好失败", error);
        }

        try {
            Class<?> store = Class.forName(STORE, false, classLoader);
            Method devices = store.getDeclaredMethod("c");
            module.hook(devices).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    inject(result);
                } catch (Throwable error) {
                    Log.w(TAG, "device list: 注入失败", error);
                }
                return result;
            });
            Log.i(TAG, "device list: 仓库 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "device list: 仓库 Hook 失败", error);
        }

        try {
            Class<?> viewModel = Class.forName(VIEW_MODEL, false, classLoader);
            Method refresh = viewModel.getDeclaredMethod("f");
            module.hook(refresh).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    markConnected(chain.getThisObject());
                } catch (Throwable error) {
                    Log.w(TAG, "device list: 标记连接态失败", error);
                }
                return result;
            });
            Log.i(TAG, "device list: 列表 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "device list: 列表 Hook 失败", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static void inject(Object set) throws Exception {
        if (!(set instanceof Set<?> devices)) {
            return;
        }
        Object ours = device();
        if (ours == null) {
            return;
        }
        String id = deviceId(ours);
        for (Object existing : devices) {
            if (id.equals(deviceId(existing))) {
                return;
            }
        }
        ((Set<Object>) devices).add(ours);
        Log.i(TAG, "device list: 已注入 " + id);
    }

    /** 原生按魅族 SDK 判定连接态，第三方设备需要手工置为 2（已连接）。 */
    @SuppressWarnings("unchecked")
    private static void markConnected(Object viewModel) throws Exception {
        Field field = viewModel.getClass().getDeclaredField("f5336c");
        field.setAccessible(true);
        Object value = field.get(viewModel);
        if (!(value instanceof java.util.List<?> list)) {
            return;
        }
        Object ours = device();
        if (ours == null) {
            return;
        }
        String id = deviceId(ours);
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            Field deviceField = item.getClass().getDeclaredField("f5246b");
            deviceField.setAccessible(true);
            Object audioDevice = deviceField.get(item);
            if (audioDevice != null && id.equals(deviceId(audioDevice))) {
                Field status = audioDevice.getClass().getField("l");
                status.setInt(audioDevice, 2);
            }
        }
    }

    private static String deviceId(Object device) throws Exception {
        Field field = device.getClass().getField("f4497a");
        return (String) field.get(device);
    }

    /** 构造一条 AudioDevice；地址未知时返回 null（不注入空设备）。 */
    private static synchronized Object device() throws Exception {
        if (injected != null) {
            return injected;
        }
        if (loader == null) {
            return null;
        }
        String address = preferences == null ? null : preferences.getString("address", null);
        String name = preferences == null ? null : preferences.getString("name", null);
        if (address == null || address.isEmpty()) {
            return null;
        }
        if (name == null || name.isEmpty()) {
            name = "OPPO Enco X2";
        }

        Class<?> audioType = Class.forName(AUDIO_DEVICE, true, loader);
        Class<?> transportType = Class.forName(TRANSPORT_TYPE, true, loader);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object bredr = Enum.valueOf((Class<? extends Enum>) transportType, "BREDR");

        Constructor<?> constructor = audioType.getConstructor(
                String.class, String.class, String.class, String.class, int.class,
                transportType, boolean.class, Boolean.class, String.class, Boolean.class,
                String.class);
        injected = constructor.newInstance(
                address,               // deviceId
                "flymeears:third-party", // modelId
                address,               // address
                name,                  // 显示名称
                0,                     // 未知字段
                bredr,                 // 经典蓝牙
                true,
                null, null, null, null);
        Log.i(TAG, "device list: 构造设备 " + name + "（" + address + "）");
        return injected;
    }
}
