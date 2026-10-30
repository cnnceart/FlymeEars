package io.github.cnnceart.flymeears.hook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 宿主 SDK 的反射入口。
 *
 * <p>设备实例、状态模型、回调分发器都在 {@code com.meizu.earphone} 里，模块只能反射访问。
 * jadx 里的 {@code f45xx} 只是别名，运行时字段多是单字母，所以两个名字都试。
 */
final class Host {

    static final String TAG = "FlymeEars";
    static final String WRAPPER = "com.meizu.earphone.audiodo.mzbluetooth.MzBluetoothWrapper";
    static final String DEVICE = "com.meizu.earphone.audiodo.model.AudioDevice";
    static final String STATUS = "com.meizu.earphone.audiodo.model.AudioFunctionStatusModel";
    static final String BATTERY = "com.meizu.bluetooth.sdk.ElectricityOfDevice";
    static final String WEARING = "com.meizu.bluetooth.sdk.EarphoneWearingStatus";

    private static volatile Object wrapper;
    private static boolean wrapperFailureLogged;

    private Host() {
    }

    static ClassLoader loader() {
        return DeviceListHooks.classLoader();
    }

    /**
     * MzBluetoothWrapper 单例。
     *
     * <p>Kotlin 这里写成了 {@code object MzBluetoothWrapper { companion object } }：
     * 静态字段 {@code INSTANCE} 是 Companion，真正的实例由 {@code INSTANCE.getInstance()} 返回。
     */
    static Object wrapper() {
        Object local = wrapper;
        if (local != null) {
            return local;
        }
        try {
            Class<?> type = Class.forName(WRAPPER, true, loader());
            Object instance = null;
            try {
                java.lang.reflect.Field field = type.getDeclaredField("INSTANCE");
                field.setAccessible(true);
                instance = field.get(null);
            } catch (NoSuchFieldException ignored) {
                // 有些版本直接暴露静态字段或静态方法，下面继续试。
            }
            if (instance != null && !type.isInstance(instance)) {
                // INSTANCE 是 Companion：真正实例在它的 getInstance() 里。
                local = findMethod(instance.getClass(), "getInstance", 0).invoke(instance);
            } else if (instance != null) {
                local = instance;
            }
            if (local == null) {
                Method staticGet = type.getDeclaredMethod("getInstance");
                staticGet.setAccessible(true);
                local = staticGet.invoke(null);
            }
        } catch (Throwable error) {
            if (!wrapperFailureLogged) {
                wrapperFailureLogged = true;
                Log.w(TAG, "host: 取 wrapper 失败", error);
            }
        }
        wrapper = local;
        if (local != null) {
            Log.i(TAG, "host: wrapper 就绪");
        }
        return local;
    }

    /** 注入的那条 AudioDevice。 */
    static Object device() {
        return DeviceListHooks.injectedDevice();
    }

    static String deviceId() {
        return DeviceListHooks.ourAddress();
    }

    /** AudioFunctionStatusModel（设备状态模型）。 */
    static Object model() {
        Object device = device();
        if (device == null) {
            return null;
        }
        try {
            return call(device, "n");
        } catch (Throwable error) {
            Log.w(TAG, "host: 取状态模型失败", error);
            return null;
        }
    }

    /**
     * SDK 回调对象（MzBluetoothWrapper$MzBluetoothSDKCallback）。
     *
     * <p>真机状态变化时由 SDK 进程回调到这里：先写状态模型，再通知 LiveData 观察者。
     * 模块模拟状态时走同一条路，页面不需要任何直接调用。
     */
    static Object sdkCallback() {
        Object local = wrapper();
        if (local == null) {
            return null;
        }
        try {
            return readField(local, "mMzBluetoothCallback");
        } catch (Throwable error) {
            Log.w(TAG, "host: 取 SDK 回调对象失败", error);
            return null;
        }
    }

    /** 连接状态回调分发器（BluetoothConnectionCallbackHandler）。 */
    static Object callbackHandler() {
        Object local = wrapper();
        if (local == null) {
            return null;
        }
        try {
            return readField(local, "callbackHandler");
        } catch (Throwable error) {
            Log.w(TAG, "host: 取连接回调分发器失败", error);
            return null;
        }
    }

    /** 让宿主 LiveData 观察者重读状态模型。 */
    static void notifyObservers() {
        Object device = device();
        if (device == null) {
            return;
        }
        try {
            Object live = call(device, "m");
            call(live, "i");
        } catch (Throwable error) {
            Log.w(TAG, "host: 通知观察者失败", error);
        }
    }

    static Object battery() throws Exception {
        Class<?> type = Class.forName(BATTERY, true, loader());
        Constructor<?> constructor = type.getConstructor(int.class, boolean.class,
                int.class, boolean.class, int.class, boolean.class);
        return constructor.newInstance(
                ModuleState.box(), ModuleState.boxCharging(),
                ModuleState.left(), ModuleState.leftCharging(),
                ModuleState.right(), ModuleState.rightCharging());
    }

    static Object wearingStatus() {
        try {
            Class<?> type = Class.forName(WEARING, true, loader());
            Object value = type.getConstructor(boolean.class, boolean.class,
                            boolean.class, boolean.class)
                    .newInstance(ModuleState.inBoxLeft(), ModuleState.wearLeft(),
                            ModuleState.inBoxRight(), ModuleState.wearRight());
            type.getMethod("setEffective", boolean.class).invoke(value, true);
            return value;
        } catch (Throwable error) {
            Log.w(TAG, "host: 构造佩戴状态失败", error);
            return null;
        }
    }

    static Object readField(Object target, String... names) throws Exception {
        for (String name : names) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续试下一个名字。
            }
        }
        throw new NoSuchFieldException(String.join("/", names));
    }

    static void setField(Object target, Object value, String... names) throws Exception {
        for (String name : names) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 继续试下一个名字。
            }
        }
        throw new NoSuchFieldException(String.join("/", names));
    }

    static Object call(Object target, String name) throws Exception {
        return findMethod(target.getClass(), name, 0).invoke(target);
    }

    static Method findMethod(Class<?> type, String name, int parameters) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        throw new IllegalStateException("找不到方法 " + name + "/" + parameters);
    }

    /** 按名字 + 参数个数调用；找不到只记日志，不让宿主崩溃。 */
    static boolean invoke(Object target, String name, Object... args) {
        if (target == null) {
            return false;
        }
        try {
            findMethod(target.getClass(), name, args.length).invoke(target, args);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "host: 调用 " + name + " 失败", error);
            return false;
        }
    }
}