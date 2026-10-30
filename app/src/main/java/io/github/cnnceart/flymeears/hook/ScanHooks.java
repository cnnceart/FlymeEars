package io.github.cnnceart.flymeears.hook;

import android.app.Activity;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 「添加设备」扫描页：让第三方耳机也能被搜到。
 *
 * <p>扫描结果来自魅族 SDK 的 {@code BluetoothConnectionStatusCallback}，第三方设备
 * 永远不会有回调。这里在 {@code ScanDeviceListActivity} 创建后，把我们的设备补进
 * ViewModel 的设备列表并发一条 {@code DeviceDiscovered} 事件，原生适配器、雷达
 * 隐藏、提示文案都会照常工作。
 *
 * <p>点「连接」时 SDK 不认识第三方设备。这里不再自己跳详情页，而是让
 * {@code connectToDevice} 直接返回成功，并按原生顺序驱动连接状态机：
 * 先 {@code handleConnectedStateChanged(deviceId, 1)}（连接中），
 * 再 {@code handleConnectedStateChanged(deviceId, 2)}（已连接）。
 * 扫描页、连接中页、成功页、详情页全部由原生代码自己流转。
 */
public final class ScanHooks {

    private static final String TAG = "FlymeEars";
    private static final String SCAN_ACTIVITY =
            "com.meizu.earphone.biz.scan.activity.ScanDeviceListActivity";
    private static final String ITEM = "l6.j";
    private static final String EVENT = "l6.g";
    private static final String WRAPPER =
            "com.meizu.earphone.audiodo.mzbluetooth.MzBluetoothWrapper";

    private ScanHooks() {
    }

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> scan = Class.forName(SCAN_ACTIVITY, false, loader);
            Method onCreate = scan.getDeclaredMethod("onCreate", android.os.Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof Activity activity) {
                        ModuleState.ensure(activity);
                        android.view.View decor = activity.getWindow() == null
                                ? null : activity.getWindow().getDecorView();
                        if (decor != null) {
                            decor.postDelayed(() -> {
                                try {
                                    inject(activity, loader);
                                } catch (Throwable error) {
                                    Log.w(TAG, "scan: 注入失败", error);
                                }
                            }, 600L);
                        }
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "scan: 注入失败", error);
                }
                return result;
            });
            Log.i(TAG, "scan: 扫描页 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "scan: 扫描页 Hook 失败", error);
        }

        try {
            Class<?> wrapper = Class.forName(WRAPPER, false, loader);
            Method connect = wrapper.getDeclaredMethod("connectToDevice", String.class);
            module.hook(connect).intercept(chain -> {
                try {
                    String deviceId = (String) chain.getArg(0);
                    String ours = DeviceListHooks.ourAddress();
                    if (ours != null && ours.equalsIgnoreCase(deviceId)) {
                        simulateConnect(chain.getThisObject(), deviceId);
                        Log.i(TAG, "scan: 第三方设备交给原生连接状态机 " + deviceId);
                        return true;
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "scan: 模拟连接失败", error);
                }
                return chain.proceed();
            });
            Log.i(TAG, "scan: 连接按钮 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "scan: 连接按钮 Hook 失败", error);
        }
    }

    /** 把第三方耳机塞进扫描结果列表，并触发一次原生刷新。 */
    private static void inject(Activity activity, ClassLoader loader) throws Exception {
        String address = DeviceListHooks.ourAddress();
        String name = DeviceListHooks.ourName();
        if (address == null || name == null) {
            Log.i(TAG, "scan: 还没有第三方设备信息，跳过");
            return;
        }
        Object viewModel = activity.getClass().getDeclaredMethod("j").invoke(activity);
        Method devices = findMethod(viewModel.getClass(), "f", 0);
        Object value = devices.invoke(viewModel);
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            Object id = readField(item, "b", "f7414b");
            if (address.equalsIgnoreCase(String.valueOf(id))) {
                return;
            }
        }
        Class<?> itemType = Class.forName(ITEM, true, loader);
        Object item = itemType.getConstructor(String.class, String.class, String.class)
                .newInstance(name, address, address);
        @SuppressWarnings("unchecked")
        List<Object> target = (List<Object>) list;
        target.add(item);

        Class<?> eventType = Class.forName(EVENT, true, loader);
        Object event = eventType.getConstructor(List.class).newInstance(list);
        Method emit = findMethod(viewModel.getClass(), "e", 1);
        emit.invoke(viewModel, event);
        Log.i(TAG, "scan: 已注入扫描结果 " + name + "（" + address + "）");
    }

    /**
     * 驱动原生连接状态机：先「连接中」，再「已连接」。
     *
     * <p>{@code handleConnectedStateChanged} 是 SDK 回调进入原生 UI 的入口，
     * 这里按原生时序各调一次，扫描页/连接中页/成功页会自己完成跳转。
     */
    private static void simulateConnect(Object wrapper, String deviceId) throws Exception {
        Method handle = wrapper.getClass().getDeclaredMethod(
                "handleConnectedStateChanged", String.class, int.class);
        handle.setAccessible(true);
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> invoke(handle, wrapper, deviceId, 1));
        main.postDelayed(() -> invoke(handle, wrapper, deviceId, 2), 900L);
    }

    private static void invoke(Method handle, Object wrapper, String deviceId, int state) {
        try {
            handle.invoke(wrapper, deviceId, state);
            Log.i(TAG, "scan: 连接状态 -> " + state);
        } catch (Throwable error) {
            Log.w(TAG, "scan: 连接状态 " + state + " 失败", error);
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameters) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("找不到方法 " + name + "/" + parameters);
    }

    /** jadx 的 fxxxxx 是别名，运行时字段多是单字母；两个名字都试。 */
    private static Object readField(Object target, String... names) throws Exception {
        for (String name : names) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续尝试下一个名字。
            }
        }
        throw new NoSuchFieldException(String.join("/", names));
    }
}
