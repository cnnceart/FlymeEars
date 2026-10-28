package dev.flymeenco;

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

    private static final String TAG = "FlymeEnco";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "FlymeEnco 模块已加载", null);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        ClassLoader loader = param.getClassLoader();
        log(Log.INFO, TAG, "package ready: " + packageName, null);

        if ("com.android.systemui".equals(packageName)) {
            probe(loader, "com.flyme.systemui.volume.QSVolumeControllerImpl");
        } else if ("com.meizu.earphone".equals(packageName)) {
            probe(loader, "com.meizu.bluetooth.sdk.MzBluetoothService");
        } else if ("com.meizu.connectivitysettings".equals(packageName)) {
            probe(loader, "com.meizu.settings.bluetooth.BluetoothDeviceDetailFragment");
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
