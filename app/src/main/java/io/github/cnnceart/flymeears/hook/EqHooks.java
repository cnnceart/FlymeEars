package io.github.cnnceart.flymeears.hook;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;

import io.github.cnnceart.flymeears.EncoIntents;
import io.github.cnnceart.flymeears.protocol.EqMapper;
import io.github.libxposed.api.XposedModule;

/**
 * 原生「自定义 EQ」页接 OPPO 的 12 段听力补偿曲线。
 *
 * <p>页面本身不改：{@code CfgEQActivity.onResume} 调
 * {@code MzBluetoothWrapper.getEqConfigs}，拖动滑杆调 {@code setEqConfigs}。
 * 这里把这两个方法换成模块自己的实现：
 * <ul>
 *   <li>读：返回本进程缓存的上一次曲线，页面打开时滑杆保持原位；</li>
 *   <li>写：把 10 段按对数频段折叠成 6 段，经 SystemUI 会话下发
 *       {@code 0x0411 + 0x040E + 0x0403 0B 01}。</li>
 * </ul>
 */
public final class EqHooks {

    private static final String TAG = "FlymeEars";
    /** o5.c 里「声效调节」EQ 预设对话框的 case 值。 */
    private static final int SOUND_EFFECT_EQ_DIALOG = 1;
    /** o5.c 里「音效调节」对话框的 case 值。 */
    private static final int SOUND_EFFECT_DIALOG = 7;
    /** 声效调节里的「自定义」档位。 */
    private static final int SOUND_EFFECT_CUSTOM = 3;

    private static final String CUSTOM_EQ_PAGE =
            "com.meizu.earphone.biz.configuration.activity.CfgEQActivity";

    /** EqualizerView 的 10 个频点，长度即原生页要求的数组长度。 */
    private static final int BANDS = EqMapper.NATIVE_BANDS.length;
    private static volatile int[] curve = new int[BANDS];
    /** 拖动滑杆会连续回调，合并成一次下发。 */
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable pendingSend;
    private static Object pendingWrapper;
    private static int[] pendingCurve;

    private EqHooks() {
    }

    public static void install(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> wrapper = Class.forName(Host.WRAPPER, false, loader);
        Method get = wrapper.getDeclaredMethod("getEqConfigs", String.class);
        module.hook(get).intercept(chain -> {
            if (isOurs((String) chain.getArg(0))) {
                return curve.clone();
            }
            return chain.proceed();
        });
        Method set = wrapper.getDeclaredMethod("setEqConfigs", String.class, int[].class);
        module.hook(set).intercept(chain -> {
            if (isOurs((String) chain.getArg(0))) {
                int[] configs = (int[]) chain.getArg(1);
                if (configs == null) {
                    return false;
                }
                curve = configs.clone();
                schedule(chain.getThisObject(), curve);
                return true;
            }
            return chain.proceed();
        });
        installEntry(module, loader);
        Method adjust = wrapper.getDeclaredMethod("setSoundAdjustModel", String.class, int.class);
        module.hook(adjust).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            int mode = ((Number) chain.getArg(1)).intValue();
            Log.i(TAG, "eq: setSoundAdjustModel(" + deviceId + ") -> " + mode
                    + " ours=" + isOurs(deviceId));
            if (isOurs(deviceId) && mode == SOUND_EFFECT_CUSTOM) {
                // 原生「自定义」只是设备侧的一个音效模式；换成真正能编辑的曲线页。
                openCustomEq(chain.getThisObject());
                Log.i(TAG, "eq: 声效调节自定义 -> 打开曲线页");
                return true;
            }
            return chain.proceed();
        });
        Log.i(TAG, "eq: 自定义 EQ Hook 已挂载");
    }

    /**
     * 「声效调节 → 自定义」原本跳 audiodo 校准页（{@code /cst/cstSoundEffectAdjust}）。
     *
     * <p>那条路依赖 Aspen SDK 的耳机校准数据，第三方耳机永远拿不到
     * （{@code isAudiodoServiceReady} 恒为 false），点了只会看到空页。这里把它改投到
     * 原生自带的 {@code CfgEQActivity}（{@code /customer/main}），页面与滑杆全部仍是
     * 魅族原生实现，只是换了一个能工作的入口。
     */
    private static void installEntry(XposedModule module, ClassLoader loader) throws Exception {
        // 声效调节对话框的点击监听器是 o5.c(case=7)。它在列表点击时只拿到下标，
        // 所以在这里拦住「自定义」这一项，改开原生曲线页。
        try {
            Class<?> listener = Class.forName("o5.c", false, loader);
            Method onClick = listener.getDeclaredMethod("onClick",
                    android.content.DialogInterface.class, int.class);
            module.hook(onClick).intercept(chain -> {
                Object self = chain.getThisObject();
                int caseId = ((Number) Host.readField(self, "a", "f7813a")).intValue();
                int which = ((Number) chain.getArg(1)).intValue();
                Object owner = Host.readField(self, "b", "f7814b");
                Log.i(TAG, "eq: dialog click case=" + caseId + " which=" + which
                        + " owner=" + (owner == null ? "null" : owner.getClass().getSimpleName()));
                // case 1 = 声效调节（EQ 预设）对话框，case 7 = 音效调节对话框；两者最后一项都是「自定义」。
                if ((caseId == SOUND_EFFECT_EQ_DIALOG || caseId == SOUND_EFFECT_DIALOG)
                        && which == SOUND_EFFECT_CUSTOM
                        && owner instanceof android.app.Activity page
                        && isOurs(deviceIdOf(page))) {
                    ((android.content.DialogInterface) chain.getArg(0)).dismiss();
                    openCustomEq(page);
                    Log.i(TAG, "eq: 声效调节选自定义 -> 打开原生曲线页");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            Log.w(TAG, "eq: 声效调节入口 Hook 失败", error);
        }

        Class<?> cfg = Class.forName(
                "com.meizu.earphone.biz.configuration.activity.CfgActivity", false, loader);
        Method click = cfg.getDeclaredMethod("handleCustomEQBtnClick", boolean.class);
        module.hook(click).intercept(chain -> {
            Object activity = chain.getThisObject();
            if (!(Boolean) chain.getArg(0) || !(activity instanceof android.app.Activity page)) {
                return chain.proceed();
            }
            String deviceId = deviceIdOf(page);
            Log.i(TAG, "eq: handleCustomEQBtnClick deviceId=" + deviceId
                    + " ours=" + isOurs(deviceId));
            if (!isOurs(deviceId)) {
                return chain.proceed();
            }
            try {
                Intent intent = new Intent();
                intent.setClassName(page, CUSTOM_EQ_PAGE);
                intent.putExtra("deviceId", deviceId);
                String name = DeviceListHooks.ourName();
                if (name != null) {
                    intent.putExtra("deviceName", name);
                }
                page.startActivity(intent);
                Log.i(TAG, "eq: 自定义入口改投原生自定义 EQ 页");
            } catch (Throwable error) {
                Log.w(TAG, "eq: 打开自定义 EQ 页失败", error);
            }
            return null;
        });
    }

    private static void openCustomEq(Object wrapper) {
        String name = DeviceListHooks.ourName();
        try {
            Context context = null;
            android.app.Activity activity = SdkStateHooks.currentCfgActivity();
            if (activity != null && !activity.isFinishing()) {
                context = activity;
            } else {
                context = (Context) Host.readField(wrapper, "context");
            }
            if (context == null) {
                return;
            }
            Intent intent = new Intent();
            intent.setClassName(context.getPackageName(), CUSTOM_EQ_PAGE);
            intent.putExtra("deviceId", DeviceListHooks.ourAddress());
            if (name != null) {
                intent.putExtra("deviceName", name);
            }
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable error) {
            Log.w(TAG, "eq: 打开自定义 EQ 页失败", error);
        }
    }

    private static String deviceIdOf(android.app.Activity activity) {
        try {
            return (String) Host.findMethod(activity.getClass(), "getDeviceId", 0).invoke(activity);
        } catch (Throwable error) {
            return null;
        }
    }

    /** 滑杆每一像素都会回调，等停止拖动 500ms 再发，避免把 RFCOMM 塞满。 */
    private static void schedule(Object wrapper, int[] configs) {
        pendingWrapper = wrapper;
        pendingCurve = configs.clone();
        if (pendingSend != null) {
            MAIN.removeCallbacks(pendingSend);
        }
        pendingSend = () -> {
            pendingSend = null;
            sendCurve(pendingWrapper, pendingCurve);
        };
        MAIN.postDelayed(pendingSend, 500L);
    }

    private static void sendCurve(Object wrapper, int[] configs) {
        // 原生滑杆的刻度是 0.1dB（-60..60），协议要的是整数 dB。
        int[] db = new int[configs.length];
        for (int i = 0; i < configs.length; i++) {
            db[i] = Math.round(configs[i] / 10.0f);
        }
        Log.i(TAG, "eq: 自定义 EQ -> " + Arrays.toString(configs) + " => " + Arrays.toString(db) + " dB");
        try {
            Context context = (Context) Host.readField(wrapper, "context");
            if (context == null) {
                return;
            }
            context.sendBroadcast(new Intent(EncoIntents.ACTION_SET_CURVE)
                    .setPackage("com.android.systemui")
                    .putExtra(EncoIntents.EXTRA_CURVE, db));
        } catch (Throwable error) {
            Log.w(TAG, "eq: 下发曲线失败", error);
        }
    }

    private static boolean isOurs(String deviceId) {
        String ours = DeviceListHooks.ourAddress();
        return deviceId != null && ours != null && ours.equalsIgnoreCase(deviceId);
    }
}