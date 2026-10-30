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

    private static ClassLoader loader;
    private static volatile WeakReference<Activity> cfgActivity = new WeakReference<>(null);
    private static volatile Runnable cfgListener;
    private static volatile Activity cfgListenerOwner;
    private static volatile Object remotePrefImpl;
    private static volatile Runnable cardListener;
    private static volatile Object wrapperInstance;
    private static volatile Runnable wearListener;
    private static boolean debugReceiverReady;
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
            installWearing(module);
            installRename(module);
            installBatteryCharging(module);
            installBatteryUnit(module);
            installWearPush(module);
            installCfgActivity(module);
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
                sendMode(chain.getThisObject(), mode);
                Log.i(TAG, "sdk: 原生降噪切换 -> " + EncoSnapshot.modeName(mode));
                return true;
            }
            return chain.proceed();
        });

        Method intensityMethod = wrapper.getDeclaredMethod("setAncMode", String.class, int.class);
        module.hook(intensityMethod).intercept(chain -> {
            String deviceId = (String) chain.getArg(0);
            if (isOurs(deviceId)) {
                // X2 的强度档位尚未验证，先保证「降噪」处于开启。
                sendMode(chain.getThisObject(), EncoSnapshot.MODE_ANC);
                Log.i(TAG, "sdk: 原生降噪强度 -> 统一按降噪处理");
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
                    Object feature = feature("g", "f6851g");
                    if (feature != null) {
                        @SuppressWarnings("unchecked")
                        java.util.Set<Object> mutable = (java.util.Set<Object>) set;
                        mutable.add(feature);
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "sdk: 注入音效能力失败", error);
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

    /** 把佩戴状态推给宿主的回调分发器，让 BaseActivity/CfgActivity 的提示逻辑生效。 */
    private static void installWearPush(XposedModule module) throws Exception {
        Class<?> application = Class.forName("android.app.Application", false, loader);
        Method onCreate = application.getDeclaredMethod("onCreate");
        module.hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (chain.getThisObject() instanceof android.app.Application app) {
                    ModuleState.ensure(app);
                    if (wearListener == null) {
                        wearListener = SdkStateHooks::pushWear;
                        ModuleState.addListener(wearListener);
                    }
                    registerDebugRename(app);
                }
            } catch (Throwable error) {
                Log.w(TAG, "sdk: 佩戴推送初始化失败", error);
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

    /** 调试用改名通道：直接调用原生 changeDeviceName，避免 adb 输入法丢字。 */
    private static void registerDebugRename(Context app) {
        Log.i(TAG, "sdk: registerDebugRename 调用 ready=" + debugReceiverReady);
        if (debugReceiverReady) {
            return;
        }
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    EncoIntents.ACTION_DEBUG_RENAME);
            app.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String name = intent.getStringExtra(EncoIntents.EXTRA_DEBUG_NAME);
                    Log.i(TAG, "sdk: 收到调试改名 " + name);
                    Activity activity = cfgActivity.get();
                    if (name == null || name.isEmpty() || activity == null) {
                        return;
                    }
                    try {
                        Method change = findMethod(activity.getClass(), "changeDeviceName", 1);
                        change.invoke(activity, name);
                        Log.i(TAG, "sdk: 调试改名 -> " + name);
                    } catch (Throwable error) {
                        Log.w(TAG, "sdk: 调试改名失败", error);
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
            debugReceiverReady = true;
            Log.i(TAG, "sdk: 调试改名通道已注册");
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 注册调试改名失败", error);
        }
    }

    private static void pushWear() {
        try {
            Object wrapper = wrapperInstance;
            if (wrapper == null) {
                wrapper = singletonWrapper();
                wrapperInstance = wrapper;
            }
            if (wrapper == null) {
                return;
            }
            Object callback = readField(wrapper, "callbackHandler");
            Object status = wearingStatus();
            String deviceId = DeviceListHooks.ourAddress();
            if (callback == null || status == null || deviceId == null) {
                return;
            }
            findMethod(callback.getClass(), "earphoneWearingStatusChanged", 2)
                    .invoke(callback, deviceId, status);
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 推送佩戴状态失败", error);
        }
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

    private static Object singletonWrapper() {
        try {
            Class<?> companion = Class.forName(
                    "com.meizu.earphone.audiodo.mzbluetooth.MzBluetoothWrapper$Companion",
                    false, loader);
            return findMethod(companion, "getInstance", 0).invoke(null);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object wearingStatus() {
        try {
            Class<?> type = Class.forName(
                    "com.meizu.bluetooth.sdk.EarphoneWearingStatus", true, loader);
            Object value = type.getConstructor(boolean.class, boolean.class,
                            boolean.class, boolean.class)
                    .newInstance(ModuleState.inBoxLeft(), ModuleState.wearLeft(),
                            ModuleState.inBoxRight(), ModuleState.wearRight());
            type.getMethod("setEffective", boolean.class).invoke(value, true);
            return value;
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 构造佩戴状态失败", error);
            return null;
        }
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
                watch(activity);
                try {
                    registerDebugRename(activity.getApplicationContext());
                } catch (Throwable error) {
                    Log.w(TAG, "sdk: 注册调试改名失败", error);
                }
                if (wearListener == null) {
                    wearListener = SdkStateHooks::pushWear;
                    ModuleState.addListener(wearListener);
                }
            }
            return result;
        });

        Method onDestroy = findInherited(cfg, "onDestroy");
        module.hook(onDestroy).intercept(chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof Activity activity) {
                unwatch(activity);
            }
            return result;
        });
    }

    private static void watch(Activity activity) {
        unwatch(null);
        ModuleState.ensure(activity);
        Runnable listener = () -> refresh(activity);
        cfgActivity = new WeakReference<>(activity);
        cfgListener = listener;
        cfgListenerOwner = activity;
        ModuleState.addListener(listener);
        // 主动问一次当前状态，避免打开详情页时还没有任何广播。
        activity.sendBroadcast(new Intent(EncoIntents.ACTION_CYCLE)
                .setPackage("com.android.systemui")
                .putExtra(EncoIntents.EXTRA_QUERY_ONLY, true));
    }

    private static void unwatch(Activity activity) {
        Activity owner = cfgListenerOwner;
        if (owner == null || (activity != null && owner != activity)) {
            return;
        }
        Runnable listener = cfgListener;
        if (listener != null) {
            ModuleState.removeListener(listener);
        }
        cfgListener = null;
        cfgListenerOwner = null;
        cfgActivity = new WeakReference<>(null);
    }

    private static void refresh(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        try {
            Object device = readField(activity, "device");
            if (device == null) {
                return;
            }
            applyState(device);
            Object viewModel = call(activity, "getViewModel");
            Object model = call(device, "n");
            if (model != null) {
                Method changed = findMethod(viewModel.getClass(), "refreshDateByChanged", 1);
                changed.invoke(viewModel, model);
            }
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 刷新详情页失败", error);
        }
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
            if (mode == EncoSnapshot.MODE_OFF
                    || mode == EncoSnapshot.MODE_ANC
                    || mode == EncoSnapshot.MODE_TRANSPARENCY) {
                setField(model, mode, "c", "f4508c");
                if (mode == EncoSnapshot.MODE_ANC) {
                    setField(model, ANC_INTENSITY_SMART, "d", "f4509d");
                }
            }
            int eq = ModuleState.eq();
            if (eq >= 0) {
                setField(model, eq, "q", "f4519q");
            }
            Log.i(TAG, "sdk: 状态模型 -> " + name + " / " + EncoSnapshot.modeName(mode));
        } catch (Throwable error) {
            Log.w(TAG, "sdk: 写状态模型失败", error);
        }
    }

    private static Object battery() throws Exception {
        Class<?> type = Class.forName(BATTERY, true, loader);
        Constructor<?> constructor = type.getConstructor(int.class, boolean.class,
                int.class, boolean.class, int.class, boolean.class);
        return constructor.newInstance(
                ModuleState.box(), ModuleState.boxCharging(),
                ModuleState.left(), ModuleState.leftCharging(),
                ModuleState.right(), ModuleState.rightCharging());
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
                    .postDelayed(SdkStateHooks::pushCard, 600L);
            return result;
        });
        Log.i(TAG, "sdk: 设备中心推送 Hook 已挂载");
    }

    private static void pushCard() {
        Object impl = remotePrefImpl;
        if (impl == null) {
            return;
        }
        // 插件收到回调后会重新绑定服务，可能再次触发 registerCallback；这里做去抖。
        long now = android.os.SystemClock.elapsedRealtime();
        boolean connected = ModuleState.hasState() && ModuleState.connected();
        String signature = connected + "/" + ModuleState.mode() + "/" + ModuleState.eq() + "/"
                + ModuleState.wearLeft() + "/" + ModuleState.wearRight() + "/"
                + ModuleState.inBoxLeft() + "/" + ModuleState.inBoxRight() + "/"
                + ModuleState.left() + "/" + ModuleState.right() + "/" + ModuleState.box();
        if (now - lastCardPush < 1500L && signature.equals(lastCardSignature)) {
            return;
        }
        lastCardPush = now;
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

    private static void setField(Object target, Object value, String... names) throws Exception {
        for (String name : names) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 继续尝试下一个名字。
            }
        }
        throw new NoSuchFieldException(String.join("/", names));
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = findMethod(target.getClass(), name, 0);
        return method.invoke(target);
    }

    private static Method findMethod(Class<?> type, String name, int parameters) {
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
