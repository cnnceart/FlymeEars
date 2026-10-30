package io.github.cnnceart.flymeears.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.cnnceart.flymeears.EncoIntents;
import io.github.cnnceart.flymeears.bt.EncoSnapshot;
import io.github.libxposed.api.XposedModule;

/**
 * 让魅族耳机 App 的 SDK 层「以为」第三方耳机是一副已连接的魅族耳机。
 *
 * <p>不重绘任何界面：CfgActivity 的列表由
 * {@code CfgBaseViewModel.loadConnectedStatusData} 根据
 * {@code MzBluetoothWrapper.isConnected()} + {@code AudioFunctionStatusModel} +
 * 能力集合生成。这里只替换这三样数据的来源，原生页面就会自己长出
 * 电量 / 名称 / 降噪三态等设置项。
 *
 * <p>状态来自 SystemUI 会话的 {@link EncoIntents#ACTION_STATE} 广播，
 * {@link ModuleState} 缓存；写入设置项时把点击转成
 * {@link EncoIntents#ACTION_SET_MODE} 广播回 SystemUI。
 */
public final class SdkStateHooks {

    private static final String TAG = "FlymeEars";
    private static final String WRAPPER =
            "com.meizu.earphone.audiodo.mzbluetooth.MzBluetoothWrapper";
    private static final String DEVICE = "com.meizu.earphone.audiodo.model.AudioDevice";
    private static final String STATUS =
            "com.meizu.earphone.audiodo.model.AudioFunctionStatusModel";
    private static final String BATTERY = "com.meizu.bluetooth.sdk.ElectricityOfDevice";
    private static final String CFG_ACTIVITY =
            "com.meizu.earphone.biz.configuration.activity.CfgActivity";

    /** 原生「智能」降噪档位（l5.a 的 17）；X2 目前没有可用的强度映射。 */
    private static final int ANC_INTENSITY_SMART = 17;
    /**
     * 用户刚下发降噪后的一小段时间内不要把状态回写进模型。
     *
     * <p>页面重新渲染会把分段选择器设成模型值，这个程序性选中又会触发一次
     * {@code setAncSwitch}，形成「刚点降噪 → 回读还是关闭 → 又发一次关闭」的回声。
     */
    private static final long ANC_SETTLE_MS = 10_000L;
    private static volatile long ancCommandAt;
    /** 用户刚下发的模式/档位；只有回读和它一致（或超时）才采纳设备值。 */
    private static volatile int ancCommandMode = -1;
    private static volatile int ancCommandLevel = -1;

    private static ClassLoader loader;
    private static volatile WeakReference<Activity> cfgActivity = new WeakReference<>(null);
    private static volatile Object remotePrefImpl;
    private static volatile Runnable cardListener;
    private static volatile Object wrapperInstance;
    private static volatile Runnable wearListener;
    private static long lastCardPush;
    private static String lastCardSignature;

    private SdkStateHooks() {
    }

    public static void install(XposedModule module, ClassLoader classLoader) {
        loader = classLoader;
        try {
            installConnected(module);
            installAddress(module);
            installCacheDevice(module);
            installStatusModel(module);
            installBattery(module);
            installAnc(module);
            installEq(module);
            installExtras(module);
            installDisconnect(module);
            installWearing(module);
            installRename(module);
            installBatteryCharging(module);
            installBatteryUnit(module);
            installWearPush(module);
            installCfgActivity(module);
            CodecPageHooks.install(module, loader);
            DualConnectHooks.install(module, loader);
            EqHooks.install(module, loader);
            CardNameHooks.install(module, loader);
            installDeviceCenter(module);
            Log.i(TAG, "sdk: 宿主 SDK 伪装 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "sdk: Hook 失败", error);
        }
    }

    /** isConnected：第三方设备的连接态以 SystemUI 会话为准。 */
    private static void installConnected(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Method method = wrapper.getDeclaredMethod("isConnected", String.class);
        module.hook(method).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId)) {
                wrapperInstance = chain.getThisObject();
                boolean connected = ModuleState.hasState()
                        ? ModuleState.connected()
                        : audioRoutePresent(chain.getThisObject(), deviceId);
                Log.i(TAG, "sdk: isConnected(" + deviceId + ") -> " + connected);
                return connected;
            }
            return chain.proceed();
        });
    }

    /** SDK 不认识第三方设备，地址查询返回 null 会拖垮详情页与设备中心。 */
    private static void installAddress(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Method method = wrapper.getDeclaredMethod("getDeviceAddress", String.class);
        module.hook(method).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId)) {
                return DeviceListHooks.ourAddress();
            }
            return chain.proceed();
        });
    }

    /**
     * getCacheDevice：详情页/设备中心按 deviceId 回查设备实例。
     *
     * <p>SDK 只认自己回调过的设备，第三方耳机永远查不到，`CfgActivity` 因此走
     * “未连接”分支。这里直接返回注入的那条 {@code AudioDevice}，让上层把它当成
     * 一台已存在的设备，后续状态与事件都挂在这条实例上。
     */
    private static void installCacheDevice(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Method method = wrapper.getDeclaredMethod("getCacheDevice", String.class);
        module.hook(method).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId)) {
                Object injected = DeviceListHooks.injectedDevice();
                if (injected != null) {
                    Log.i(TAG, "sdk: getCacheDevice(" + deviceId + ") -> 注入设备");
                    return injected;
                }
            }
            return chain.proceed();
        });
    }

    /** 首次读取状态时，把 SDK 查到的空值替换成我们的真实电量/名称/模式。 */
    private static void installStatusModel(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Class<?> deviceType = Class.forName(DEVICE, false, loader);
        Method method = wrapper.getDeclaredMethod("readStatusModelFromMeizuSDK", deviceType);
        module.hook(method).intercept(chain -> {
            Object result = chain.proceed();
            Object device = chain.getArg(0);
            if (isOurs(deviceId(device))) {
                applyState(device);
            }
            return result;
        });
    }

    /** 设备中心卡片（TWSControlService）读电量的入口。 */
    private static void installBattery(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Method method = wrapper.getDeclaredMethod("queryBattery", String.class);
        module.hook(method).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId) && ModuleState.hasState()) {
                return battery();
            }
            return chain.proceed();
        });
    }

    /** 原生降噪三态按钮：转成广播交给 SystemUI 会话下发 0x0404。 */
    private static void installAnc(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Method switchMethod = wrapper.getDeclaredMethod("setAncSwitch", String.class, int.class);
        module.hook(switchMethod).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId)) {
                int mode = ((Number) chain.getArg(1)).intValue();
                if (duplicateAnc(mode)) {
                    Log.i(TAG, "sdk: 忽略重复降噪指令 -> " + EncoSnapshot.modeName(mode));
                    return true;
                }
                ancCommandAt = android.os.SystemClock.elapsedRealtime();
                ancCommandMode = mode;
                sendMode(chain.getThisObject(), mode);
                // 设备中心卡片切完会立刻 generateStateModel() 再读一次，
                // 不回写模型的话它会弹回旧档位。
                applyOptimisticAnc(mode, 0);
                Log.i(TAG, "sdk: 原生降噪切换 -> " + EncoSnapshot.modeName(mode));
                return true;
            }
            return chain.proceed();
        });

        Method intensityMethod = wrapper.getDeclaredMethod("setAncMode", String.class, int.class);
        module.hook(intensityMethod).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId)) {
                int level = ((Number) chain.getArg(1)).intValue();
                ancCommandAt = android.os.SystemClock.elapsedRealtime();
                ancCommandLevel = level;
                ancCommandMode = EncoSnapshot.MODE_ANC;
                sendAncLevel(chain.getThisObject(), level);
                applyOptimisticAnc(EncoSnapshot.MODE_ANC, level);
                Log.i(TAG, "sdk: 原生降噪档位 -> " + level);
                return true;
            }
            return chain.proceed();
        });
    }

    /**
     * 原生「音效」行（SOUND_EFFECT_EQ_MODE）：放出能力位，并把弹窗选择
     * 转到 0x0406；状态从 0x010F/0x0504 回读写入 f4519q。
     */
    private static void installEq(XposedModule module) throws Exception {
        Class<?> glide = Class.forName("com.bumptech.glide.f", false, loader);
        Class<?> deviceType = Class.forName(DEVICE, false, loader);
        Method capabilities = glide.getDeclaredMethod("h", deviceType);
        module.hook(capabilities).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object device = chain.getArg(0);
                if (result instanceof java.util.Set<?> set && isOurs(deviceId(device))) {
                    @SuppressWarnings("unchecked")
                    java.util.Set<Object> mutable = (java.util.Set<Object>) set;
                    for (String name : new String[]{"g", "j", "n", "k"}) {
                        Object feature = feature(name);
                        if (feature != null) {
                            mutable.add(feature);
                        }
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "sdk: 注入能力失败", error);
            }
            return result;
        });

        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Method setEq = wrapper.getDeclaredMethod("setEQMode", String.class, int.class);
        module.hook(setEq).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            int mode = ((Number) chain.getArg(1)).intValue();
            if (isOurs(deviceId)) {
                sendEq(chain.getThisObject(), mode);
                try {
                    Object device = DeviceListHooks.injectedDevice();
                    Object model = device == null ? null : call(device, "n");
                    if (model != null) {
                        setField(model, mode, "q", "f4519q");
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "sdk: 写 EQ 状态失败", error);
                }
                Log.i(TAG, "sdk: 原生音效切换 -> " + mode);
                return null;
            }
            return chain.proceed();
        });
    }

    /** 游戏模式 / 设备双连 / 黄金听感 / 高品质音频编码。 */
    private static void installExtras(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        hookFeatureSwitch(module, wrapper, "setLowLatency",
                io.github.cnnceart.flymeears.protocol.OppoWireCodec.FEATURE_GAME_MODE);
        hookFeatureSwitch(module, wrapper, "setMultipleConnectionEnable",
                io.github.cnnceart.flymeears.protocol.OppoWireCodec.FEATURE_DUAL_CONNECTION);

        Method activate = wrapper.getDeclaredMethod("activatePersonalSound", String.class);
        module.hook(activate).intercept(chain -> {
            if (isOurs((String) chain.getArg(0))) {
                sendFeature(chain.getThisObject(),
                        io.github.cnnceart.flymeears.protocol.OppoWireCodec.FEATURE_PERSONAL_SOUND,
                        true);
                Log.i(TAG, "sdk: 黄金听感 -> 开");
                return true;
            }
            return chain.proceed();
        });
        Method deactivate = wrapper.getDeclaredMethod("deactivatePersonalSound", String.class);
        module.hook(deactivate).intercept(chain -> {
            if (isOurs((String) chain.getArg(0))) {
                sendFeature(chain.getThisObject(),
                        io.github.cnnceart.flymeears.protocol.OppoWireCodec.FEATURE_PERSONAL_SOUND,
                        false);
                Log.i(TAG, "sdk: 黄金听感 -> 关");
                return true;
            }
            return chain.proceed();
        });

        Method highQuality = wrapper.getDeclaredMethod(
                "setHighQualityMode", String.class, int.class);
        module.hook(highQuality).intercept(chain -> {
            if (isOurs((String) chain.getArg(0))) {
                if (!ModuleState.connected()) {
                    Log.i(TAG, "sdk: 未连接，忽略高品质音频");
                    return null;
                }
                int model = ((Number) chain.getArg(1)).intValue();
                // 原生 1=AAC / 2=LHDC；3 由模块注入的 LDAC 行使用。
                int codec = switch (model) {
                    case 1 -> io.github.cnnceart.flymeears.protocol.OppoWireCodec.CODEC_AAC;
                    case 3 -> io.github.cnnceart.flymeears.protocol.OppoWireCodec.CODEC_LDAC;
                    default -> io.github.cnnceart.flymeears.protocol.OppoWireCodec.CODEC_LHDC;
                };
                sendCodec(chain.getThisObject(), codec, false);
                Log.i(TAG, "sdk: 高品质音频 -> " + model);
                return null;
            }
            return chain.proceed();
        });

        Method lhdc = wrapper.getDeclaredMethod("setLhdcAudioMode", String.class, int.class);
        module.hook(lhdc).intercept(chain -> {
            if (isOurs((String) chain.getArg(0))) {
                int mode = ((Number) chain.getArg(1)).intValue();
                // 17 = 普通 LHDC，18 = Hi-Res（LHDC 5.0）。
                boolean hiRes = mode == 18;
                sendCodec(chain.getThisObject(),
                        io.github.cnnceart.flymeears.protocol.OppoWireCodec.CODEC_LHDC, hiRes);
                Log.i(TAG, "sdk: LHDC 模式 -> " + mode + (hiRes ? "（Hi-Res）" : ""));
                return null;
            }
            return chain.proceed();
        });

        Method lhdcEnable = wrapper.getDeclaredMethod("setLhdcEnable", String.class, boolean.class);
        module.hook(lhdcEnable).intercept(chain -> {
            if (isOurs((String) chain.getArg(0))) {
                boolean on = (Boolean) chain.getArg(1);
                sendCodec(chain.getThisObject(),
                        io.github.cnnceart.flymeears.protocol.OppoWireCodec.CODEC_LHDC, on);
                Log.i(TAG, "sdk: Hi-Res -> " + on);
                return true;
            }
            return chain.proceed();
        });
    }

    private static void hookFeatureSwitch(XposedModule module, Class<?> wrapper,
                                          String name, int feature) throws Exception {
        Method method = wrapper.getDeclaredMethod(name, String.class, boolean.class);
        module.hook(method).intercept(chain -> {
            if (isOurs((String) chain.getArg(0))) {
                if (!ModuleState.connected()) {
                    Log.i(TAG, "sdk: 未连接，忽略 " + name);
                    return true;
                }
                boolean on = (Boolean) chain.getArg(1);
                sendFeature(chain.getThisObject(), feature, on);
                if (feature == io.github.cnnceart.flymeears.protocol.OppoWireCodec
                        .FEATURE_DUAL_CONNECTION) {
                    DualConnectHooks.onToggled(on);
                }
                Log.i(TAG, "sdk: " + name + " -> " + on);
                return true;
            }
            return chain.proceed();
        });
    }

    /**
     * 用户点完降噪立刻把值写回模型，并让设备中心卡片重读。
     *
     * <p>卡片走 {@code IRemotePrefService.setAncSwitch → generateStateModel} 这条
     * 同步链路：如果不先改模型，它读到的是上一档，界面就会弹回去。
     */
    private static void applyOptimisticAnc(int mode, int level) {
        try {
            Object model = Host.model();
            if (model != null) {
                if (mode > 0) {
                    setField(model, mode, "c", "f4508c");
                }
                if (level > 0) {
                    setField(model, level, "d", "f4509d");
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 回写降噪状态失败", error);
        }
        // 不主动推卡片：推送会让插件重新设置单选项，监听器没被完全屏蔽时会再发一次指令。
        // 卡片自己点完就显示新值，下次 generateStateModel() 也会读到刚写回的模型。
    }

    private static int lastAncMode = -1;
    private static long lastAncModeAt;

    /** 同一档位 400ms 内重复下发（单选项重设会再触发一次监听）直接丢掉。 */
    private static boolean duplicateAnc(int mode) {
        long now = android.os.SystemClock.elapsedRealtime();
        boolean duplicate = mode == lastAncMode && now - lastAncModeAt < 400L;
        lastAncMode = mode;
        lastAncModeAt = now;
        return duplicate;
    }

    /** 让设备中心卡片立刻重读状态（跳过 1.5s 去抖）。 */
    static void pushCardNow() {
        lastCardPush = 0L;
        lastCardSignature = null;
        pushCard();
    }

    private static void sendFeature(Object wrapper, int feature, boolean on) {
        try {
            Context context = (Context) readField(wrapper, "context");
            if (context == null) {
                return;
            }
            context.sendBroadcast(new Intent(EncoIntents.ACTION_SET_FEATURE)
                    .setPackage("com.android.systemui")
                    .putExtra(EncoIntents.EXTRA_FEATURE, feature)
                    .putExtra(EncoIntents.EXTRA_ON, on));
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 下发功能开关失败", error);
        }
    }

    private static void sendCodec(Object wrapper, int codec) {
        sendCodec(wrapper, codec, ModuleState.hiRes());
    }

    private static void sendCodec(Object wrapper, int codec, boolean hiRes) {
        try {
            Context context = (Context) readField(wrapper, "context");
            if (context == null) {
                return;
            }
            context.sendBroadcast(new Intent(EncoIntents.ACTION_SET_CODEC)
                    .setPackage("com.android.systemui")
                    .putExtra(EncoIntents.EXTRA_CODEC, codec)
                    .putExtra(EncoIntents.EXTRA_HI_RES, hiRes));
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 下发编码失败", error);
        }
    }

    /** 断开连接：详情页「断开连接」和设备中心卡片都会走到这里。 */
    private static void installDisconnect(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Method method = wrapper.getDeclaredMethod("disconnectDevice", String.class);
        module.hook(method).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId)) {
                try {
                    Context context = (Context) readField(chain.getThisObject(), "context");
                    if (context != null) {
                        context.sendBroadcast(new Intent(EncoIntents.ACTION_DISCONNECT)
                                .setPackage("com.android.systemui"));
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "sdk: 请求断开失败", error);
                }
                Log.i(TAG, "sdk: 原生断开连接 -> 交给会话处理");
                return true;
            }
            return chain.proceed();
        });
    }

    /** 原生佩戴状态查询（0x0109 的 4/5/7 语义）。 */
    private static void installWearing(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Method method = wrapper.getDeclaredMethod("queryWearingStatus", String.class);
        module.hook(method).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId)) {
                Object status = wearingStatus();
                if (status != null) {
                    return status;
                }
            }
            return chain.proceed();
        });
    }

    /** 应用进程起来后挂一条全局状态监听：ModuleState 一变就按 SDK 回调链推给宿主。 */
    private static void installWearPush(XposedModule module) throws Exception {
        Class<?> application = Class.forName("android.app.Application", false, loader);
        Method onCreate = application.getDeclaredMethod("onCreate");
        module.hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (chain.getThisObject() instanceof android.app.Application app) {
                    ModuleState.ensure(app);
                    if (wearListener == null) {
                        // 状态一有变化就走 SDK 回调链推给宿主（连接/降噪/电量/佩戴/编码…）。
                        wearListener = SdkPush::sync;
                        ModuleState.addListener(wearListener);
                    }

                }
            } catch (Throwable error) {
                Log.w(TAG, "sdk: 状态推送初始化失败", error);
            }
            return result;
        });
    }

    /**
     * 原生详情页的电量行把 isCharging 写死成 false（CfgBaseViewModel:277），
     * 这里在三格电量模型构造后按真实充电状态重建 k5.b。
     */
    private static void installBatteryCharging(XposedModule module) throws Exception {
        Class<?> row = Class.forName("t5.a", false, loader);
        Class<?> cell = Class.forName("k5.b", false, loader);
        java.lang.reflect.Constructor<?> constructor =
                row.getDeclaredConstructor(cell, cell, cell);
        module.hook(constructor).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Activity activity = cfgActivity.get();
                if (activity == null || !isOurs((String) call(activity, "getDeviceId"))) {
                    return result;
                }
                setField(result, withCharging(cell, chain.getArg(0), ModuleState.boxCharging()),
                        "f8833d", "d");
                setField(result, withCharging(cell, chain.getArg(1), ModuleState.leftCharging()),
                        "e", "e");
                setField(result, withCharging(cell, chain.getArg(2), ModuleState.rightCharging()),
                        "f8834f", "f");
                Log.i(TAG, "sdk: 电量行充电状态 -> " + ModuleState.boxCharging() + "/"
                        + ModuleState.leftCharging() + "/" + ModuleState.rightCharging());
            } catch (Throwable error) {
                Log.w(TAG, "sdk: 电量充电状态修正失败", error);
            }
            return result;
        });
    }

    /**
     * 原生 BatteryUnitView.setCharging 无视入参，永远隐藏闪电并关掉充电色，
     * 这里按真实状态重新实现：显示闪电 + 电池填充变绿。
     */
    private static void installBatteryUnit(XposedModule module) throws Exception {
        Class<?> unit = Class.forName("com.meizu.earphone.widgets.BatteryUnitView", false, loader);
        Method setCharging = unit.getDeclaredMethod("setCharging", boolean.class);
        module.hook(setCharging).intercept(chain -> {
            boolean charging = (Boolean) chain.getArg(0);
            Object self = chain.getThisObject();
            try {
                Object icon = readField(self, "q", "f4760q");
                if (icon instanceof android.view.View view) {
                    view.setVisibility(charging ? android.view.View.VISIBLE
                            : android.view.View.GONE);
                }
                Object battery = readField(self, "r", "f4761r");
                if (battery != null) {
                    findMethod(battery.getClass(), "setCharging", 1)
                            .invoke(battery, charging);
                }
            } catch (Throwable error) {
                Log.w(TAG, "sdk: 电量充电图标失败", error);
            }
            return null;
        });
    }

    private static Object withCharging(Class<?> cell, Object model, boolean charging)
            throws Exception {
        Object title = readField(model, "a", "f7177a");
        Object level = readField(model, "b", "f7178b");
        return cell.getConstructor(String.class, float.class, boolean.class)
                .newInstance(title, level, charging);
    }

    /** 原生「重命名」：第三方设备写不进耳机，但可以完整支持 App 侧改名。 */
    private static void installRename(XposedModule module) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Method method = wrapper.getDeclaredMethod("setDeviceName", String.class, String.class);
        module.hook(method).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            String name = (String) chain.getArg(1);
            if (isOurs(deviceId) && name != null && !name.isEmpty()) {
                try {
                    Object device = DeviceListHooks.injectedDevice();
                    if (device != null) {
                        setField(device, name, "d", "f4500d");
                        Object model = call(device, "n");
                        if (model != null) {
                            setField(model, name, "b", "f4507b");
                        }
                    }
                    DeviceListHooks.updateName(name);
                    Log.i(TAG, "sdk: 原生重命名 -> " + name);
                } catch (Throwable error) {
                    Log.w(TAG, "sdk: 重命名写回失败", error);
                }
                return true;
            }
            return chain.proceed();
        });
    }

    private static Object wearingStatus() {
        return Host.wearingStatus();
    }

    private static Object feature(String... names) throws Exception {
        Class<?> type = Class.forName("i5.a", false, loader);
        for (String name : names) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(null);
            } catch (NoSuchFieldException ignored) {
                // 继续试别名。
            }
        }
        return null;
    }

    private static void sendEq(Object wrapper, int mode) {
        try {
            Context context = (Context) readField(wrapper, "context");
            if (context == null) {
                return;
            }
            context.sendBroadcast(new Intent(EncoIntents.ACTION_SET_EQ)
                    .setPackage("com.android.systemui")
                    .putExtra(EncoIntents.EXTRA_EQ, mode));
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 下发 EQ 失败", error);
        }
    }

    /**
     * 详情页存活期间跟随 SystemUI 广播刷新原生列表。
     *
     * <p>页面首次加载完全交给原生：`isReady` 回调里会自己走
     * refreshDeviceAddress → setupDeviceData → loadDeviceData，
     * 设备实例由 {@link #installCacheDevice} 提供，这里只负责后续状态刷新。
     */
    private static void installCfgActivity(XposedModule module) throws Exception {
        Class<?> cfg = Class.forName(CFG_ACTIVITY, false, loader);
        Method onCreate = cfg.getDeclaredMethod("onCreate", android.os.Bundle.class);
        module.hook(onCreate).intercept(chain -> {
            // onCreate 内部就会构建电量行，先把当前 Activity 交给
            // installBatteryCharging 的构造器 Hook 使用。
            if (chain.getThisObject() instanceof Activity opening) {
                cfgActivity = new WeakReference<>(opening);
            }
            Object result = chain.proceed();
            Log.i(TAG, "sdk: CfgActivity.onCreate 命中");
            if (chain.getThisObject() instanceof Activity activity) {
                ModuleState.ensure(activity);
                // 页面状态完全由 SDK 回调（SdkPush）驱动，这里只保留电量充电图标的修正。
                activity.sendBroadcast(new Intent(EncoIntents.ACTION_CYCLE)
                        .setPackage("com.android.systemui")
                        .putExtra(EncoIntents.EXTRA_QUERY_ONLY, true));
            }
            return result;
        });

        Method onDestroy = findInherited(cfg, "onDestroy");
        module.hook(onDestroy).intercept(chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof Activity activity && cfgActivity.get() == activity) {
                cfgActivity = new WeakReference<>(null);
            }
            return result;
        });
    }

    /** 当前详情页（可能为 null）。 */
    static Activity currentCfgActivity() {
        return cfgActivity.get();
    }

    /** 供其它 Hook 复用：把 ModuleState 写进宿主的状态模型。 */
    static void applyDeviceState(Object device) {
        applyState(device);
    }

    /** 把 ModuleState 写进 AudioFunctionStatusModel。 */
    private static void applyState(Object device) {
        try {
            Object model = call(device, "n");
            if (model == null) {
                return;
            }
            String name = ModuleState.name();
            if (name != null && !name.isEmpty()) {
                setField(model, name, "b", "f4507b");
                setField(device, name, "d", "f4500d");
            }
            setField(model, battery(), "a", "f4506a");
            int mode = ModuleState.mode();
            boolean confirmed = ancCommandMode < 0 || mode == ancCommandMode;
            boolean settling = android.os.SystemClock.elapsedRealtime() - ancCommandAt
                    < ANC_SETTLE_MS;
            if (confirmed || !settling) {
                // 回读和刚下发的值一致（或等待超时）才采纳设备状态，避免刚切完就被旧回读顶回去。
                ancCommandMode = -1;
                if (mode == EncoSnapshot.MODE_OFF
                        || mode == EncoSnapshot.MODE_ANC
                        || mode == EncoSnapshot.MODE_TRANSPARENCY) {
                    setField(model, mode, "c", "f4508c");
                    if (mode == EncoSnapshot.MODE_ANC) {
                        int level = ModuleState.ancLevel();
                        setField(model, level > 0 ? level : ANC_INTENSITY_SMART, "d", "f4509d");
                    }
                }
            }
            int eq = ModuleState.eq();
            if (eq >= 0) {
                setField(model, eq, "q", "f4519q");
            }
            setField(model, 1, "v", "f4524v");
            setField(model, ModuleState.lowLatency(), "r", "f4520r");
            int codec = ModuleState.codec();
            if (codec == io.github.cnnceart.flymeears.protocol.OppoWireCodec.CODEC_LHDC) {
                setField(model, 2, "o", "f4517o");
            } else if (codec == io.github.cnnceart.flymeears.protocol.OppoWireCodec.CODEC_AAC) {
                setField(model, 1, "o", "f4517o");
            } else if (codec == io.github.cnnceart.flymeears.protocol.OppoWireCodec.CODEC_LDAC) {
                setField(model, 3, "o", "f4517o");
            }
            setField(model, ModuleState.hiRes() ? 18 : 17, "p", "f4518p");
            setField(model, ModuleState.dualConnection(), "i", "f4513i");
            java.util.Map<String, String> devices = ModuleState.dualDevices();
            if (!devices.isEmpty()) {
                setField(model, new java.util.LinkedHashMap<>(devices), "j", "f4514j");
            }
            Log.i(TAG, "sdk: 状态模型 -> " + name + " / " + EncoSnapshot.modeName(mode));
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 写状态模型失败", error);
        }
    }

    private static Object battery() throws Exception {
        return Host.battery();
    }

    private static void sendMode(Object wrapper, int mode) {
        try {
            Context context = (Context) readField(wrapper, "context");
            if (context == null) {
                return;
            }
            context.sendBroadcast(new Intent(EncoIntents.ACTION_SET_MODE)
                    .setPackage("com.android.systemui")
                    .putExtra(EncoIntents.EXTRA_MODE, mode));
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 下发模式失败", error);
        }
    }

    /** 降噪档位（17~20）：转成广播交给 SystemUI 会话下发 0x0404 的档位值。 */
    private static void sendAncLevel(Object wrapper, int level) {
        try {
            Context context = (Context) readField(wrapper, "context");
            if (context == null) {
                return;
            }
            context.sendBroadcast(new Intent(EncoIntents.ACTION_SET_ANC_LEVEL)
                    .setPackage("com.android.systemui")
                    .putExtra(EncoIntents.EXTRA_ANC_LEVEL, level));
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 下发降噪档位失败", error);
        }
    }

    /**
     * 设备中心卡片（SystemUI 里的 CustomTilePlugin）只在耳机 App 推送
     * deviceConnectionSuccessful / onNoiseControlModeChanged 时刷新。
     * 第三方耳机没有 SDK 回调，所以这里借用同一个 RemoteCallbackList 主动推。
     */
    private static void installDeviceCenter(XposedModule module) throws Exception {
        Class<?> service = Class.forName(
                "com.meizu.earphone.biz.devicecontrol.RemotePrefService", false, loader);
        Method onCreate = service.getDeclaredMethod("onCreate");
        module.hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object impl = readField(chain.getThisObject(), "a", "f4671a");
                if (impl != null) {
                    remotePrefImpl = impl;
                    Context context = (Context) chain.getThisObject();
                    ModuleState.ensure(context);
                    if (cardListener == null) {
                        cardListener = SdkStateHooks::pushCard;
                        ModuleState.addListener(cardListener);
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "sdk: 设备中心服务初始化失败", error);
            }
            return result;
        });

        Class<?> impl = Class.forName(
                "com.meizu.earphone.biz.devicecontrol.remote.RemotePrefServiceImpl",
                false, loader);
        Class<?> callback = Class.forName(
                "com.meizu.earphone.biz.devicecontrol.remote.IBluetoothConnectionStatusCallback",
                false, loader);
        Method register = impl.getDeclaredMethod("registerCallback", callback);
        module.hook(register).intercept(chain -> {
            Object result = chain.proceed();
            // 插件刚绑定时先推一次，让它拿到电量和当前模式。
            new android.os.Handler(android.os.Looper.getMainLooper())
                    // 卡片每次打开都会重新注册回调；这里必须强制推一次，
                    // 否则去重逻辑会认为"状态没变"而什么都不发，卡片就停留在
                    // 无电量 / 未连接 / 音量不可调的初始状态。
                    .postDelayed(SdkStateHooks::pushCardNow, 600L);
            return result;
        });
        Log.i(TAG, "sdk: 设备中心推送 Hook 已挂载");
    }

    private static void pushCard() {
        Object impl = remotePrefImpl;
        if (impl == null) {
            return;
        }
        // 只在状态真的变了才推：每隔几秒把同一份状态推一遍会让卡片反复重绘，
        // 用户刚在卡片上切完降噪会被"旧值"顶回去（看起来就是回弹）。
        boolean connected = ModuleState.hasState() && ModuleState.connected();
        String signature = connected + "/" + ModuleState.mode() + "/" + ModuleState.eq() + "/"
                + ModuleState.wearLeft() + "/" + ModuleState.wearRight() + "/"
                + ModuleState.inBoxLeft() + "/" + ModuleState.inBoxRight() + "/"
                + ModuleState.left() + "/" + ModuleState.right() + "/" + ModuleState.box() + "/"
                + ModuleState.leftCharging() + "/" + ModuleState.rightCharging() + "/"
                + ModuleState.boxCharging() + "/" + ModuleState.codec() + "/"
                + ModuleState.lowLatency() + "/" + ModuleState.dualConnection();
        if (signature.equals(lastCardSignature)) {
            return;
        }
        lastCardSignature = signature;
        try {
            Object callback = readField(impl, "bcmCallback");
            Object device = DeviceListHooks.injectedDevice();
            String deviceId = DeviceListHooks.ourAddress();
            if (callback == null || device == null || deviceId == null) {
                return;
            }
            // 未连接时必须走 deviceDisconnected：卡片才会显示「连接」入口，
            // 降噪按钮也才会置灰。之前无条件推 deviceConnectionSuccessful，
            // 卡片就一直是「已连接 + 降噪点亮」的样式。
            if (!connected) {
                findMethod(callback.getClass(), "deviceDisconnected", 1)
                        .invoke(callback, deviceId);
                Log.i(TAG, "sdk: 已向设备中心推送未连接");
                return;
            }
            findMethod(callback.getClass(), "deviceConnectionSuccessful", 1)
                    .invoke(callback, device);
            findMethod(callback.getClass(), "onNoiseControlModeChanged", 2)
                    .invoke(callback, deviceId, ModuleState.mode());
            Object wearing = wearingStatus();
            if (wearing != null) {
                findMethod(callback.getClass(), "earphoneWearingStatusChanged", 2)
                        .invoke(callback, deviceId, wearing);
            }
            Log.i(TAG, "sdk: 已向设备中心推送状态");
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 推送设备中心失败", error);
        }
    }

    /** ModuleState 还没收到广播时，用系统音频路由判断耳机是否真的在连着。 */
    private static boolean audioRoutePresent(Object wrapper, String address) {
        try {
            Context context = (Context) readField(wrapper, "context");
            if (context == null) {
                return false;
            }
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) {
                return false;
            }
            for (AudioDeviceInfo output : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (output.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        && output.getType() != AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    continue;
                }
                if (address.equalsIgnoreCase(output.getAddress())) {
                    return true;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 查询音频路由失败", error);
        }
        return false;
    }

    private static boolean isOurs(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return false;
        }
        String ours = DeviceListHooks.ourAddress();
        return ours != null && ours.equalsIgnoreCase(deviceId);
    }

    private static String deviceId(Object device) {
        try {
            Object value = readField(device, "a", "f4497a");
            return value == null ? null : String.valueOf(value);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object readField(Object target, String... names) throws Exception {
        return Host.readField(target, names);
    }

    private static void setField(Object target, Object value, String... names) throws Exception {
        Host.setField(target, value, names);
    }

    private static Object call(Object target, String name) throws Exception {
        return Host.call(target, name);
    }

    private static Method findMethod(Class<?> type, String name, int parameters) {
        return Host.findMethod(type, name, parameters);
    }

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
}