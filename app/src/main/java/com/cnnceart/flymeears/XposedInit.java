package com.cnnceart.flymeears;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed 入口。
 *
 * <p>当前阶段只做「探针」：在 Flyme SystemUI 里确认音量面板控制器的真实结构，
 * 为后续替换降噪图标与接管点击做准备。所有反射都包在 try/catch 里，
 * 类不存在时只记日志，不影响宿主。
 */
public final class XposedInit extends XposedModule {

    private static final String TAG = "FlymeEars";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "FlymeEars 模块已加载", null);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        ClassLoader loader = param.getClassLoader();
        log(Log.INFO, TAG, "package ready: " + packageName, null);
        // 同时写 logcat，便于不借助 LSPosed 日志做真机排查。
        android.util.Log.i(TAG, "package ready: " + packageName);

        if ("com.android.systemui".equals(packageName)) {
            probe(loader, "com.flyme.systemui.volume.QSVolumeControllerImpl");
            installStep("session", () -> startSystemUiSession(loader));
            installStep("volume", () -> com.cnnceart.flymeears.systemui.VolumePanelHooks.install(this, loader));
            installStep("deviceCenter", () -> allowDeviceCenter(loader));
        } else if ("com.meizu.earphone".equals(packageName)) {
            com.cnnceart.flymeears.hook.EarphonePopupHooks.install(this, loader);
            com.cnnceart.flymeears.hook.DeviceListHooks.install(this, loader);
        } else if ("com.meizu.connectivitysettings".equals(packageName)) {
            probe(loader, "com.meizu.settings.bluetooth.BluetoothDeviceDetailFragment");
        }
    }

    /** 接收磁贴指令（走广播，避免再开一条 RFCOMM）。 */
    private void registerControlReceiver(android.app.Application application) {
        android.content.IntentFilter filter = new android.content.IntentFilter(
                com.cnnceart.flymeears.EncoIntents.ACTION_CYCLE);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                com.cnnceart.flymeears.systemui.EncoSession session =
                        com.cnnceart.flymeears.systemui.EncoSession.instance();
                if (session != null) {
                    session.cycleMode();
                    android.util.Log.i(TAG, "session: 磁贴触发模式切换");
                }
            }
        };
        application.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED);
        Log.i(TAG, "session: 磁贴指令接收器已注册");
    }

    /** 每个注入点独立兜底，避免一个失败拖垮其它能力。 */
    private void installStep(String what, Runnable action) {
        try {
            action.run();
            android.util.Log.i(TAG, "install step ok: " + what);
        } catch (Throwable error) {
            android.util.Log.e(TAG, "install step failed: " + what, error);
            log(Log.ERROR, TAG, "install step failed: " + what, error);
        }
    }

    /** 在 SystemUI 启动时挂上耳机会话（自动直连 + 原生弹窗）。 */
    private void startSystemUiSession(ClassLoader loader) {
        try {
            Class<?> app = Class.forName("com.android.systemui.SystemUIApplication", false, loader);
            Method onCreate = app.getDeclaredMethod("onCreate");
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    android.app.Application application = (android.app.Application) chain.getThisObject();
                    com.cnnceart.flymeears.systemui.EncoSession.start(application,
                            getRemotePreferences("flymeears"));
                    registerControlReceiver(application);
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "session: 启动失败", error);
                }
                return result;
            });
            log(Log.INFO, TAG, "session: 已挂载 SystemUIApplication", null);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "session: 挂载失败", error);
        }
    }

    /**
     * 放行设备中心的白名单。
     *
     * <p>{@code DeviceCenterController.isServiceInWhitelist} 只接受系统应用或
     * {@code config_controlsWhitelistPackages} 里的包；该数组编译在 SystemUI 资源中，
     * 无法通过设置修改，所以在这里放行本模块包名，让我们的 Device Controls 卡片可见。
     */
    private void allowDeviceCenter(ClassLoader loader) {
        try {
            Class<?> controller = Class.forName(
                    "com.flyme.systemui.controls.DeviceCenterController", false, loader);
            Class<?> serviceInfo = Class.forName(
                    "com.android.systemui.controls.ControlsServiceInfo", false, loader);
            Method check = controller.getDeclaredMethod("isServiceInWhitelist", serviceInfo);
            hook(check).intercept(chain -> {
                try {
                    Object info = chain.getArg(0);
                    Object component = serviceInfo.getField("componentName").get(info);
                    if (component instanceof android.content.ComponentName name
                            && BuildConfig.APPLICATION_ID.equals(name.getPackageName())) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // 拿不到组件名时交回原逻辑。
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "device center: 白名单已放行 com.cnnceart.flymeears", null);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "device center: 白名单 Hook 失败", error);
        }
    }

    private void probe(ClassLoader loader, String className) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            log(Log.INFO, TAG, "探针命中 " + className, null);
            for (Method method : type.getDeclaredMethods()) {
                log(Log.INFO, TAG, "  " + describe(method), null);
            }
        } catch (ClassNotFoundException error) {
            log(Log.INFO, TAG, "探针未命中（类不存在）：" + className, null);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "探针失败：" + className, error);
        }
    }

    private static String describe(Method method) {
        StringBuilder text = new StringBuilder(method.getName()).append('(');
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(parameters[i].getSimpleName());
        }
        return text.append(") -> ").append(method.getReturnType().getSimpleName()).toString();
    }
}
