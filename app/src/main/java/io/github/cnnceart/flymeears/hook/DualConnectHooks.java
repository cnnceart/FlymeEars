package io.github.cnnceart.flymeears.hook;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import io.github.cnnceart.flymeears.EncoIntents;
import io.github.libxposed.api.XposedModule;

/**
 * 「设备双连」页的状态回填。
 *
 * <p>原生 {@code CfgDoubleConnectActivity.j(model)} 用 {@code model.i} 设置开关、
 * {@code model.j} 显示设备名；观察者只在这两个值同时变化时才重绘，所以模块在
 * 开关下发后更新模型并主动把页面重新渲染一次，避免开关卡在旧值。
 */
public final class DualConnectHooks {

    private static final String TAG = "FlymeEars";
    private static final String PAGE =
            "com.meizu.earphone.biz.configuration.activity.CfgDoubleConnectActivity";

    private static volatile WeakReference<Activity> page = new WeakReference<>(null);

    private DualConnectHooks() {
    }

    public static void install(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> type = Class.forName(PAGE, false, loader);
        Method onCreate = type.getDeclaredMethod("onCreate", android.os.Bundle.class);
        module.hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof Activity activity) {
                page = new WeakReference<>(activity);
                // 让 SystemUI 会话补一次 0x0112，双连设备表才能填上名字。
                try {
                    activity.sendBroadcast(new Intent(EncoIntents.ACTION_CYCLE)
                            .setPackage("com.android.systemui")
                            .putExtra(EncoIntents.EXTRA_QUERY_ONLY, true));
                } catch (Throwable error) {
                    Log.w(TAG, "dual: 请求状态失败", error);
                }
            }
            return result;
        });
        Class<?> wrapper = Class.forName(Host.WRAPPER, false, loader);
        Method request = wrapper.getDeclaredMethod("requestMultipleConnectionDevice", String.class);
        module.hook(request).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId)) {
                // 真实设备会回 0x0112；这里直接用会话缓存刷新页面。
                render();
                return true;
            }
            return chain.proceed();
        });
        Log.i(TAG, "dual: 设备双连页 Hook 已挂载");
    }

    /** 开关下发后同步模型，并让页面重新读一遍。 */
    static void onToggled(boolean on) {
        try {
            Object model = Host.model();
            if (model != null) {
                Host.setField(model, on, "i", "f4513i");
            }
        } catch (Throwable error) {
            Log.w(TAG, "dual: 写双连状态失败", error);
        }
        Log.i(TAG, "dual: 原生开关 -> " + on);
        render();
    }

    private static void render() {
        Activity activity = page.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        try {
            Object device = DeviceListHooks.injectedDevice();
            if (device == null) {
                return;
            }
            SdkStateHooks.applyDeviceState(device);
            Object model = Host.call(device, "n");
            if (model != null) {
                Host.findMethod(activity.getClass(), "j", 1).invoke(activity, model);
            }
        } catch (Throwable error) {
            Log.w(TAG, "dual: 刷新双连页失败", error);
        }
    }

    private static boolean isOurs(String deviceId) {
        String ours = DeviceListHooks.ourAddress();
        return deviceId != null && ours != null && ours.equalsIgnoreCase(deviceId);
    }
}
