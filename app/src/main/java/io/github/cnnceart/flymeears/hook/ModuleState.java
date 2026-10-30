package io.github.cnnceart.flymeears.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.cnnceart.flymeears.EncoIntents;
import io.github.cnnceart.flymeears.bt.EncoSnapshot;

/**
 * 耳机进程内的状态缓存。
 *
 * <p>SystemUI 会话通过 {@link EncoIntents#ACTION_STATE} 广播把电量与降噪模式推给
 * {@code com.meizu.earphone}。设备列表行、详情页、扫描页都读这一份数据，
 * 避免每个界面各自注册接收器、各自等待下一次推送。
 */
public final class ModuleState {

    private static final String TAG = "FlymeEars";

    private static final Object LOCK = new Object();
    /** 弱引用：界面销毁后不必依赖 onDestroy 精确注销。 */
    private static final CopyOnWriteArrayList<WeakReference<Runnable>> LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean installed;
    private static volatile boolean hasState;
    private static volatile boolean connected;
    private static volatile String name;
    private static volatile int mode;
    private static volatile int ancLevel = -1;
    private static volatile boolean lowLatency;
    private static volatile boolean dualConnection;
    private static volatile boolean personalSound;
    private static volatile int codec = -1;
    private static volatile boolean hiRes;
    private static volatile int eq = -1;
    private static volatile boolean wearLeft;
    private static volatile boolean wearRight;
    private static volatile boolean inBoxLeft;
    private static volatile boolean inBoxRight;
    /** 佩戴/在盒值是否已经收到过有效回读；false 时不要把它当成“未佩戴”推给宿主。 */
    private static volatile boolean wearValid;
    private static volatile boolean leftCharging;
    private static volatile boolean rightCharging;
    private static volatile boolean boxCharging;
    private static volatile int left = -1;
    private static volatile int right = -1;
    private static volatile int box = -1;
    /** 0x0112 多设备表：MAC -> 名称，当前设备排第一。 */
    private static volatile java.util.Map<String, String> dualDevices =
            java.util.Collections.emptyMap();

    private ModuleState() {
    }

    /** 幂等：在宿主进程里挂一次状态广播接收器。 */
    public static void ensure(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            try {
                Context app = context.getApplicationContext();
                IntentFilter filter = new IntentFilter(EncoIntents.ACTION_STATE);
                app.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        update(intent);
                    }
                }, filter, Context.RECEIVER_EXPORTED);
                installed = true;
                Log.i(TAG, "state: 已监听耳机状态广播");
            } catch (Throwable error) {
                Log.w(TAG, "state: 注册状态广播失败", error);
            }
        }
    }

    /**
     * 只覆盖 intent 里实际带了的字段。
     *
     * <p>断开的广播只带 {@code connected=false} 与少量开关；缺省的字段必须沿用上一次的
     * 有效值，否则会把 ANC 模式覆盖成 0（未知）、把佩戴覆盖成 false。
     */
    private static void update(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.hasExtra(EncoIntents.EXTRA_MODE)) {
            int value = intent.getIntExtra(EncoIntents.EXTRA_MODE, mode);
            if (EncoIntents.validMode(value)) {
                mode = value;
            }
        }
        if (intent.hasExtra(EncoIntents.EXTRA_ANC_LEVEL)) {
            int value = intent.getIntExtra(EncoIntents.EXTRA_ANC_LEVEL, ancLevel);
            if (EncoIntents.validAncLevel(value)) {
                ancLevel = value;
            }
        }
        if (intent.hasExtra(EncoIntents.EXTRA_EQ)) {
            int value = intent.getIntExtra(EncoIntents.EXTRA_EQ, eq);
            if (EncoIntents.validEq(value)) {
                eq = value;
            }
        }
        if (intent.hasExtra(EncoIntents.EXTRA_CODEC)) {
            int value = intent.getIntExtra(EncoIntents.EXTRA_CODEC, codec);
            if (EncoIntents.validCodec(value)) {
                codec = value;
            }
        }
        if (intent.hasExtra(EncoIntents.EXTRA_LOW_LATENCY)) {
            lowLatency = intent.getBooleanExtra(EncoIntents.EXTRA_LOW_LATENCY, lowLatency);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_DUAL_CONNECTION)) {
            dualConnection = intent.getBooleanExtra(EncoIntents.EXTRA_DUAL_CONNECTION, dualConnection);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_PERSONAL_SOUND)) {
            personalSound = intent.getBooleanExtra(EncoIntents.EXTRA_PERSONAL_SOUND, personalSound);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_HI_RES)) {
            hiRes = intent.getBooleanExtra(EncoIntents.EXTRA_HI_RES, hiRes);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_WEAR_VALID)) {
            wearValid = intent.getBooleanExtra(EncoIntents.EXTRA_WEAR_VALID, wearValid);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_WEAR_LEFT)) {
            wearLeft = intent.getBooleanExtra(EncoIntents.EXTRA_WEAR_LEFT, wearLeft);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_WEAR_RIGHT)) {
            wearRight = intent.getBooleanExtra(EncoIntents.EXTRA_WEAR_RIGHT, wearRight);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_IN_BOX_LEFT)) {
            inBoxLeft = intent.getBooleanExtra(EncoIntents.EXTRA_IN_BOX_LEFT, inBoxLeft);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_IN_BOX_RIGHT)) {
            inBoxRight = intent.getBooleanExtra(EncoIntents.EXTRA_IN_BOX_RIGHT, inBoxRight);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_LEFT_CHARGING)) {
            leftCharging = intent.getBooleanExtra(EncoIntents.EXTRA_LEFT_CHARGING, leftCharging);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_RIGHT_CHARGING)) {
            rightCharging = intent.getBooleanExtra(EncoIntents.EXTRA_RIGHT_CHARGING, rightCharging);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_BOX_CHARGING)) {
            boxCharging = intent.getBooleanExtra(EncoIntents.EXTRA_BOX_CHARGING, boxCharging);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_LEFT)) {
            int value = intent.getIntExtra(EncoIntents.EXTRA_LEFT, left);
            if (EncoIntents.validBattery(value)) {
                left = value;
            }
        }
        if (intent.hasExtra(EncoIntents.EXTRA_RIGHT)) {
            int value = intent.getIntExtra(EncoIntents.EXTRA_RIGHT, right);
            if (EncoIntents.validBattery(value)) {
                right = value;
            }
        }
        if (intent.hasExtra(EncoIntents.EXTRA_CASE)) {
            int value = intent.getIntExtra(EncoIntents.EXTRA_CASE, box);
            if (EncoIntents.validBattery(value)) {
                box = value;
            }
        }
        if (intent.hasExtra(EncoIntents.EXTRA_CONNECTED)) {
            connected = intent.getBooleanExtra(EncoIntents.EXTRA_CONNECTED, connected);
        }
        if (intent.hasExtra(EncoIntents.EXTRA_DUAL_DEVICES)) {
            dualDevices = parseDualDevices(intent.getStringArrayExtra(EncoIntents.EXTRA_DUAL_DEVICES));
        }
        String value = intent.getStringExtra(EncoIntents.EXTRA_NAME);
        if (value != null && !value.isEmpty()) {
            name = value;
        }
        hasState = true;
        for (WeakReference<Runnable> reference : LISTENERS) {
            Runnable listener = reference.get();
            if (listener == null) {
                LISTENERS.remove(reference);
            } else {
                MAIN.post(listener);
            }
        }
    }

    private static java.util.Map<String, String> parseDualDevices(String[] entries) {
        if (entries == null || entries.length == 0) {
            return java.util.Collections.emptyMap();
        }
        java.util.LinkedHashMap<String, String> devices = new java.util.LinkedHashMap<>();
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            int split = entry.indexOf('\u0001');
            if (split <= 0 || split + 1 >= entry.length()) {
                continue;
            }
            devices.put(entry.substring(0, split), entry.substring(split + 1));
        }
        return devices;
    }

    /** 注册变化回调；注册时立刻回调一次（如果已有状态）。 */
    public static void addListener(Runnable listener) {
        if (listener == null) {
            return;
        }
        LISTENERS.add(new WeakReference<>(listener));
        if (hasState) {
            MAIN.post(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        for (WeakReference<Runnable> reference : LISTENERS) {
            if (reference.get() == listener) {
                LISTENERS.remove(reference);
            }
        }
    }

    public static boolean hasState() {
        return hasState;
    }

    public static boolean connected() {
        return connected;
    }

    public static int mode() {
        return mode;
    }

    /** 降噪档位（17 智能 / 18 深度 / 19 均衡 / 20 轻度）；-1 未知。 */
    public static int ancLevel() {
        return ancLevel;
    }

    public static boolean lowLatency() {
        return lowLatency;
    }

    public static boolean dualConnection() {
        return dualConnection;
    }

    public static boolean personalSound() {
        return personalSound;
    }

    public static int codec() {
        return codec;
    }

    public static boolean hiRes() {
        return hiRes;
    }

    public static int eq() {
        return eq;
    }

    public static boolean wearLeft() {
        return wearLeft;
    }

    public static boolean wearRight() {
        return wearRight;
    }

    public static boolean inBoxLeft() {
        return inBoxLeft;
    }

    public static boolean inBoxRight() {
        return inBoxRight;
    }

    /** 是否收到过 0x0109 的佩戴/在盒回读。 */
    public static boolean wearValid() {
        return wearValid;
    }

    public static boolean leftCharging() {
        return leftCharging;
    }

    public static boolean rightCharging() {
        return rightCharging;
    }

    public static boolean boxCharging() {
        return boxCharging;
    }

    public static int left() {
        return left;
    }

    public static int right() {
        return right;
    }

    public static int box() {
        return box;
    }

    /** 0x0112 解析结果（只读副本）。 */
    public static java.util.Map<String, String> dualDevices() {
        return dualDevices;
    }

    public static String name() {
        return name;
    }

    public static String modeName() {
        return EncoSnapshot.modeName(mode);
    }

    /** 设备列表行用的单行摘要；没有状态时返回 null（保留原生文案）。 */
    public static String summary() {
        if (!hasState) {
            return null;
        }
        if (!connected) {
            return "未连接";
        }
        StringBuilder text = new StringBuilder("已连接 · ").append(modeName());
        if (left >= 0) {
            text.append(" · 左").append(left).append('%');
        }
        if (right >= 0) {
            text.append(" 右").append(right).append('%');
        }
        if (box >= 0) {
            text.append(" 盒").append(box).append('%');
        }
        return text.toString();
    }
}
