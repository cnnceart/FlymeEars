package io.github.cnnceart.flymeears;

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
        android.util.Log.i(TAG, "package ready: " + packageName + " pid="
                + android.os.Process.myPid());

        if ("com.android.systemui".equals(packageName)) {
            installStep("session", () -> startSystemUiSession(loader));
            installStep("artwork", () -> io.github.cnnceart.flymeears.hook.ArtworkHooks
                    .installSystemUi(this, loader));
        } else if ("com.meizu.earphone".equals(packageName)) {
            installStep("popup", () -> io.github.cnnceart.flymeears.hook.EarphonePopupHooks
                    .install(this, loader));
            installStep("device-list", () -> io.github.cnnceart.flymeears.hook.DeviceListHooks
                    .install(this, loader));
            installStep("sdk-state", () -> io.github.cnnceart.flymeears.hook.SdkStateHooks
                    .install(this, loader));
            installStep("scan", () -> io.github.cnnceart.flymeears.hook.ScanHooks
                    .install(this, loader));
            installStep("artwork", () -> io.github.cnnceart.flymeears.hook.ArtworkHooks
                    .installEarphone(this, loader));
        } else if ("com.meizu.connectivitysettings".equals(packageName)) {
            probe(loader, "com.meizu.settings.bluetooth.BluetoothDeviceDetailFragment");
        }
    }

    /** 接收磁贴 / 详情页指令（走广播，避免再开一条 RFCOMM）。 */
    private void registerControlReceiver(android.app.Application application) {
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(io.github.cnnceart.flymeears.EncoIntents.ACTION_CYCLE);
        filter.addAction(io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_MODE);
        filter.addAction(io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_ANC_LEVEL);
        filter.addAction(io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_FEATURE);
        filter.addAction(io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_CODEC);
        filter.addAction(io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_EQ);
        filter.addAction(io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_CURVE);
        filter.addAction(io.github.cnnceart.flymeears.EncoIntents.ACTION_DISCONNECT);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                io.github.cnnceart.flymeears.systemui.EncoSession session =
                        io.github.cnnceart.flymeears.systemui.EncoSession.instance();
                if (session == null) {
                    return;
                }
                if (io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_EQ
                        .equals(intent.getAction())) {
                    int eq = intent.getIntExtra(
                            io.github.cnnceart.flymeears.EncoIntents.EXTRA_EQ, -1);
                    if (EncoIntents.validEq(eq)) {
                        session.setEq(eq);
                        android.util.Log.i(TAG, "session: 设置 EQ " + eq);
                    }
                    return;
                }
                if (io.github.cnnceart.flymeears.EncoIntents.ACTION_DISCONNECT
                        .equals(intent.getAction())) {
                    session.disconnect();
                    android.util.Log.i(TAG, "session: 收到断开请求");
                    return;
                }
                if (io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_CURVE
                        .equals(intent.getAction())) {
                    int[] curve = intent.getIntArrayExtra(
                            io.github.cnnceart.flymeears.EncoIntents.EXTRA_CURVE);
                    if (curve != null && curve.length > 0 && curve.length <= 32) {
                        session.setCurve(curve);
                        android.util.Log.i(TAG, "session: 自定义 EQ " + java.util.Arrays.toString(curve));
                    }
                    return;
                }
                if (io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_MODE
                        .equals(intent.getAction())) {
                    int mode = intent.getIntExtra(
                            io.github.cnnceart.flymeears.EncoIntents.EXTRA_MODE, 0);
                    if (EncoIntents.validMode(mode)) {
                        session.requestMode(mode);
                        android.util.Log.i(TAG, "session: 详情页切换模式 " + mode);
                    }
                    return;
                }
                if (io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_ANC_LEVEL
                        .equals(intent.getAction())) {
                    int level = intent.getIntExtra(
                            io.github.cnnceart.flymeears.EncoIntents.EXTRA_ANC_LEVEL, -1);
                    if (EncoIntents.validAncLevel(level)) {
                        session.requestAncLevel(level);
                        android.util.Log.i(TAG, "session: 降噪档位 " + level);
                    }
                    return;
                }
                if (io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_FEATURE
                        .equals(intent.getAction())) {
                    int feature = intent.getIntExtra(
                            io.github.cnnceart.flymeears.EncoIntents.EXTRA_FEATURE, -1);
                    boolean on = intent.getBooleanExtra(
                            io.github.cnnceart.flymeears.EncoIntents.EXTRA_ON, false);
                    if (EncoIntents.validFeature(feature)) {
                        session.requestFeature(feature, on);
                        android.util.Log.i(TAG, "session: 功能 0x"
                                + Integer.toHexString(feature) + " -> " + on);
                    }
                    return;
                }
                if (io.github.cnnceart.flymeears.EncoIntents.ACTION_SET_CODEC
                        .equals(intent.getAction())) {
                    int codec = intent.getIntExtra(
                            io.github.cnnceart.flymeears.EncoIntents.EXTRA_CODEC, -1);
                    boolean hiRes = intent.getBooleanExtra(
                            io.github.cnnceart.flymeears.EncoIntents.EXTRA_HI_RES, false);
                    if (EncoIntents.validCodec(codec)) {
                        session.requestCodec(codec, hiRes);
                        android.util.Log.i(TAG, "session: 编码 0x"
                                + Integer.toHexString(codec) + " hiRes=" + hiRes);
                    }
                    return;
                }
                if (intent.getBooleanExtra(
                        io.github.cnnceart.flymeears.EncoIntents.EXTRA_QUERY_ONLY, false)) {
                    session.publishState();
                    android.util.Log.i(TAG, "session: 详情页查询当前状态");
                    return;
                }
                session.cycleMode();
                android.util.Log.i(TAG, "session: 磁贴触发模式切换");
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
                    String process = application.getProcessName();
                    if (process != null && process.contains(":")) {
                        // 每个 SystemUI 子进程都起会话会重复建 RFCOMM、重复注册控制接收器。
                        android.util.Log.i(TAG, "session: 子进程 " + process + " 跳过会话");
                        return result;
                    }
                    io.github.cnnceart.flymeears.systemui.EncoSession.start(application);
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