package io.github.cnnceart.flymeears.hook;

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

    private static final int LISTENER_TAG = 0x0f1e0002;

    private static ClassLoader loader;
    private static SharedPreferences preferences;
    private static Object injected;
    private static volatile String resolvedAddress;
    private static volatile String resolvedName;

    private DeviceListHooks() {
    }

    public static void install(XposedModule module, ClassLoader classLoader) {
        loader = classLoader;
        try {
            preferences = module.getRemotePreferences(PREFS);
            Log.i(TAG, "device list: 偏好 address=" + (preferences == null ? "null"
                    : preferences.getString("address", "null")));
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

        // 原生 MainActivity 只按"魅族缓存列表"决定去配对页还是设备页；
        // 第三方设备不在缓存里，所以这里直接在同进程拉起设备页。
        try {
            Class<?> main = Class.forName("com.meizu.earphone.MainActivity", false, classLoader);
            Method onCreate = main.getDeclaredMethod("onCreate", android.os.Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof android.app.Activity activity) {
                        ModuleState.ensure(activity);
                    }
                    redirect(chain.getThisObject());
                } catch (Throwable error) {
                    Log.w(TAG, "device list: 跳转失败", error);
                }
                return result;
            });
            Log.i(TAG, "device list: 入口 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "device list: 入口 Hook 失败", error);
        }

        installRowDecoration(module, classLoader);
    }

    /**
     * 列表行内补电量与降噪模式：原生行只有名称 + 连接状态，
     * 第三方设备的这两项都来自 {@link ModuleState}（SystemUI 广播）。
     */
    private static void installRowDecoration(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> adapter = Class.forName("b6.e", false, classLoader);
            Class<?> viewHolder = Class.forName(
                    "androidx.recyclerview.widget.RecyclerView$ViewHolder", false, classLoader);
            Method bind = adapter.getDeclaredMethod("onBindViewHolder", viewHolder, int.class);
            module.hook(bind).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    decorateRow(chain.getArg(0));
                } catch (Throwable error) {
                    Log.w(TAG, "device list: 行内状态注入失败", error);
                }
                return result;
            });
            Log.i(TAG, "device list: 行内状态 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "device list: 行内状态 Hook 失败", error);
        }

        try {
            Class<?> list = Class.forName(
                    "com.meizu.earphone.biz.deviceList.activity.DeviceListActivity", false, classLoader);
            Method onCreate = list.getDeclaredMethod("onCreate", android.os.Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    watchState(chain.getThisObject());
                } catch (Throwable error) {
                    Log.w(TAG, "device list: 状态监听失败", error);
                }
                return result;
            });
            Method onDestroy = findInherited(list, "onDestroy");
            module.hook(onDestroy).intercept(chain -> {
                try {
                    unwatchState(chain.getThisObject());
                } catch (Throwable error) {
                    Log.w(TAG, "device list: 状态监听清理失败", error);
                }
                return chain.proceed();
            });
            Log.i(TAG, "device list: 列表页状态 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "device list: 列表页状态 Hook 失败", error);
        }
    }

    /** 状态变化时重绘列表（只在列表页存活期间监听）。 */
    private static void watchState(Object instance) {
        if (!(instance instanceof android.app.Activity activity)) {
            return;
        }
        ModuleState.ensure(activity);
        Runnable listener = () -> {
            try {
                android.view.View content = activity.findViewById(android.R.id.content);
                if (content == null) {
                    return;
                }
                int id = activity.getResources().getIdentifier(
                        "scan_device_recycler_view", "id", activity.getPackageName());
                android.view.View recycler = id == 0 ? null : content.findViewById(id);
                if (recycler != null) {
                    Object adapter = call(recycler, "getAdapter");
                    if (adapter != null) {
                        call(adapter, "notifyDataSetChanged");
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "device list: 刷新行失败", error);
            }
        };
        android.view.View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) {
            decor.setTag(LISTENER_TAG, listener);
        }
        ModuleState.addListener(listener);
    }

    private static void unwatchState(Object instance) {
        if (!(instance instanceof android.app.Activity activity)) {
            return;
        }
        android.view.View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        Object tag = decor == null ? null : decor.getTag(LISTENER_TAG);
        if (tag instanceof Runnable listener) {
            ModuleState.removeListener(listener);
            decor.setTag(LISTENER_TAG, null);
        }
    }

    private static void decorateRow(Object holder) {
        String summary = ModuleState.summary();
        Object view;
        try {
            java.lang.reflect.Field field = holder.getClass().getField("itemView");
            view = field.get(holder);
        } catch (Throwable error) {
            return;
        }
        if (!(view instanceof android.view.View item)) {
            return;
        }
        android.content.Context context = item.getContext();
        int titleId = context.getResources().getIdentifier(
                "dl_device_item_title", "id", context.getPackageName());
        int statusId = context.getResources().getIdentifier(
                "dl_device_item_connect_status", "id", context.getPackageName());
        if (titleId == 0 || statusId == 0) {
            return;
        }
        android.view.View titleView = item.findViewById(titleId);
        android.view.View statusView = item.findViewById(statusId);
        if (!(titleView instanceof android.widget.TextView title)
                || !(statusView instanceof android.widget.TextView status)) {
            return;
        }
        String shownName = String.valueOf(title.getText());
        if (!looksLikeOurs(shownName)) {
            return;
        }
        if (summary != null) {
            status.setText(summary);
        }
        int iconId = context.getResources().getIdentifier(
                "icon", "id", context.getPackageName());
        android.view.View iconView = iconId == 0 ? null : item.findViewById(iconId);
        if (iconView instanceof android.widget.ImageView icon) {
            Artwork.apply(icon, Artwork.CASE, 160);
        }
    }

    /** 宿主可能没在自己的类里覆盖生命周期方法，沿父类往上找。 */
    private static Method findInherited(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找。
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    /** 反射调用无参方法（宿主里的 RecyclerView / Adapter 不在模块的编译类路径上）。 */
    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static boolean looksLikeOurs(String deviceName) {
        if (deviceName == null) {
            return false;
        }
        String ours = ourName();
        if (ours != null && ours.equals(deviceName)) {
            return true;
        }
        String normalized = deviceName.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return normalized.contains("oppo") || normalized.contains("enco");
    }

    /** 有第三方设备时，绕开"添加设备"引导页，直接进设备列表。 */
    private static void redirect(Object activity) {
        if (!(activity instanceof android.app.Activity host)) {
            return;
        }
        boolean available;
        try {
            available = device() != null;
        } catch (Throwable error) {
            Log.w(TAG, "device list: 解析设备失败", error);
            available = false;
        }
        Log.i(TAG, "device list: 入口检查 available=" + available);
        if (!available) {
            return;
        }
        android.view.View decor = host.getWindow() == null ? null : host.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        decor.postDelayed(() -> {
            try {
                android.content.Intent intent = new android.content.Intent()
                        .setClassName(host, "com.meizu.earphone.biz.deviceList.activity.DeviceListActivity")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                host.startActivity(intent);
                Log.i(TAG, "device list: 已跳转设备列表");
            } catch (Throwable error) {
                Log.w(TAG, "device list: 启动设备列表失败", error);
            }
        }, 800L);
    }

    private static boolean hasDevice() {
        if (preferences == null) {
            return false;
        }
        String address = preferences.getString("address", null);
        return address != null && !address.isEmpty();
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
        Field field = findField(viewModel.getClass(), "c", "f5336c");
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
            Field deviceField = findField(item.getClass(), "b", "f5246b");
            deviceField.setAccessible(true);
            Object audioDevice = deviceField.get(item);
            if (audioDevice != null && id.equals(deviceId(audioDevice))) {
                Field status = findField(audioDevice.getClass(), "l");
                status.setAccessible(true);
                status.setInt(audioDevice, 2);
            }
        }
    }

    /** jadx 会把混淆字段显示成 fxxxxx，运行时要按原始单字母名取。 */
    private static Field findField(Class<?> type, String... names) throws NoSuchFieldException {
        for (String name : names) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续尝试下一个名字。
            }
        }
        throw new NoSuchFieldException(String.join("/", names));
    }

    private static String deviceId(Object device) throws Exception {
        Field field = findField(device.getClass(), "a", "f4497a");
        field.setAccessible(true);
        return (String) field.get(device);
    }

    /** 当前第三方耳机地址（无设备时为 null）。 */
    static String ourAddress() {
        resolveIdentity();
        return resolvedAddress;
    }

    /** 当前第三方耳机显示名（无设备时为 null）。 */
    static String ourName() {
        resolveIdentity();
        return resolvedName;
    }

    /**
     * 解析耳机地址与名称：优先用 SystemUI 会话写下的远程偏好，
     * 读不到时退回宿主自己的蓝牙配对列表（宿主是系统应用，通常有权限）。
     */
    private static synchronized void resolveIdentity() {
        if (resolvedAddress != null && !resolvedAddress.isEmpty()) {
            return;
        }
        String address = preferences == null ? null : preferences.getString("address", null);
        String name = preferences == null ? null : preferences.getString("name", null);
        if (address == null || address.isEmpty()) {
            try {
                android.bluetooth.BluetoothAdapter adapter =
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    for (android.bluetooth.BluetoothDevice device : adapter.getBondedDevices()) {
                        String deviceName = device.getName();
                        if (deviceName == null) {
                            continue;
                        }
                        String normalized = deviceName.toLowerCase(java.util.Locale.ROOT)
                                .replace(" ", "");
                        if (normalized.contains("oppo") || normalized.contains("enco")) {
                            address = device.getAddress();
                            name = deviceName;
                            Log.i(TAG, "device list: 从蓝牙配对列表取到 " + deviceName);
                            break;
                        }
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "device list: 查询配对设备失败", error);
            }
        }
        if (address == null || address.isEmpty()) {
            return;
        }
        if (name == null || name.isEmpty()) {
            name = "OPPO Enco X2";
        }
        resolvedAddress = address;
        resolvedName = name;
    }

    /** 供其它 Hook 复用的设备实例（设备中心推送需要同一个 AudioDevice）。 */
    static Object injectedDevice() {
        try {
            return device();
        } catch (Throwable error) {
            Log.w(TAG, "device list: 构建设备实例失败", error);
            return null;
        }
    }

    /** 构造一条 AudioDevice；地址未知时返回 null（不注入空设备）。 */
    private static synchronized Object device() throws Exception {
        if (injected != null) {
            return injected;
        }
        if (loader == null) {
            return null;
        }
        resolveIdentity();
        String address = resolvedAddress;
        String name = resolvedName;
        if (address == null || address.isEmpty()) {
            Log.i(TAG, "device list: 没有可用设备地址");
            return null;
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
