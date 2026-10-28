package io.github.cnnceart.flymeears.hook;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.cnnceart.flymeears.EncoIntents;
import io.github.cnnceart.flymeears.bt.EncoSnapshot;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 在魅族耳机 App 的「设备详情页」（CfgActivity）里补一块第三方耳机状态区。
 *
 * <p>原生 CfgActivity 的内容全部来自魅族 SDK，第三方设备拿不到任何能力，
 * 所以页面是空的。这里直接在内容容器顶部插入我们的面板：
 * 电量（左/右/盒）+ 当前降噪模式 + 三个模式按钮。
 *
 * <p>状态由 SystemUI 会话广播给宿主进程，{@link ModuleState} 缓存后分发；
 * 按钮点击则回发 {@link EncoIntents#ACTION_SET_MODE}，不重复占用 RFCOMM。
 */
public final class DeviceDetailHooks {

    private static final String TAG = "FlymeEars";
    private static final int LISTENER_TAG = 0x0f1e0001;
    private static final String CFG_ACTIVITY =
            "com.meizu.earphone.biz.configuration.activity.CfgActivity";

    private DeviceDetailHooks() {
    }

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(CFG_ACTIVITY, false, loader);
            Method onCreate = type.getDeclaredMethod("onCreate", android.os.Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    attach(chain.getThisObject());
                } catch (Throwable error) {
                    Log.w(TAG, "detail: 注入面板失败", error);
                }
                return result;
            });

            Method onDestroy = type.getDeclaredMethod("onDestroy");
            module.hook(onDestroy).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    detach(chain.getThisObject());
                } catch (Throwable error) {
                    Log.w(TAG, "detail: 清理失败", error);
                }
                return result;
            });
            Log.i(TAG, "detail: 面板 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "detail: Hook 失败", error);
        }
    }

    private static void attach(Object instance) {
        if (!(instance instanceof Activity activity)) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        if (content.findViewWithTag("flymeears-panel") != null) {
            return;
        }

        LinearLayout panel = buildPanel(activity);
        panel.setTag("flymeears-panel");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 88);
        params.leftMargin = dp(activity, 16);
        params.rightMargin = dp(activity, 16);
        params.gravity = Gravity.TOP;
        content.addView(panel, params);

        ModuleState.ensure(activity);
        Runnable listener = () -> renderFromState(panel);
        panel.setTag(LISTENER_TAG, listener);
        ModuleState.addListener(listener);
        Log.i(TAG, "detail: 面板已插入 " + activity.getClass().getName());
        // 主动问一次当前状态（只读，不切换模式），避免面板空白等待下一次推送。
        activity.sendBroadcast(new Intent(EncoIntents.ACTION_CYCLE)
                .setPackage("com.android.systemui")
                .putExtra(EncoIntents.EXTRA_QUERY_ONLY, true));
    }

    private static void detach(Object instance) {
        if (!(instance instanceof Activity activity)) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        View panel = content.findViewWithTag("flymeears-panel");
        if (panel == null) {
            return;
        }
        Object listener = panel.getTag(LISTENER_TAG);
        if (listener instanceof Runnable runnable) {
            ModuleState.removeListener(runnable);
        }
        content.removeView(panel);
    }

    private static LinearLayout buildPanel(Activity activity) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1C1C1E);
        background.setCornerRadius(dp(activity, 20));
        panel.setBackground(background);

        TextView title = new TextView(activity);
        title.setText(ModuleState.name() == null ? "OPPO Enco X2" : ModuleState.name());
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTag("flymeears-title");
        panel.addView(title);

        TextView battery = new TextView(activity);
        battery.setTextColor(0xFFB0B0B5);
        battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        battery.setPadding(0, dp(activity, 6), 0, 0);
        battery.setText("电量读取中…");
        battery.setTag("flymeears-battery");
        panel.addView(battery);

        TextView mode = new TextView(activity);
        mode.setTextColor(0xFFB0B0B5);
        mode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mode.setPadding(0, dp(activity, 4), 0, dp(activity, 12));
        mode.setText("降噪模式：读取中…");
        mode.setTag("flymeears-mode");
        panel.addView(mode);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setTag("flymeears-buttons");
        panel.addView(buttons);

        addButton(activity, buttons, "关闭", EncoSnapshot.MODE_OFF);
        addButton(activity, buttons, "降噪", EncoSnapshot.MODE_ANC);
        addButton(activity, buttons, "通透", EncoSnapshot.MODE_TRANSPARENCY);
        return panel;
    }

    private static void addButton(Activity activity, LinearLayout parent, String text, int mode) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(Color.WHITE);
        button.setTag(mode);
        button.setBackground(buttonBackground(activity, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = dp(activity, 10);
        button.setOnClickListener(view -> view.getContext().sendBroadcast(
                new Intent(EncoIntents.ACTION_SET_MODE)
                        .setPackage("com.android.systemui")
                        .putExtra(EncoIntents.EXTRA_MODE, mode)));
        parent.addView(button, params);
    }

    private static GradientDrawable buttonBackground(android.content.Context context, boolean active) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(active ? 0xFF0A59F7 : 0xFF2C2C2E);
        background.setCornerRadius(dp(context, 18));
        return background;
    }

    private static void renderFromState(LinearLayout panel) {
        if (!ModuleState.hasState()) {
            return;
        }
        render(panel, ModuleState.name(), ModuleState.left(), ModuleState.right(),
                ModuleState.box(), ModuleState.mode(), ModuleState.connected());
    }

    private static void render(LinearLayout panel, String name, int left, int right,
                               int caseBattery, int mode, boolean connected) {
        TextView title = panel.findViewWithTag("flymeears-title");
        if (title != null && name != null && !name.isEmpty()) {
            title.setText(name);
        }
        TextView battery = panel.findViewWithTag("flymeears-battery");
        if (battery != null) {
            StringBuilder text = new StringBuilder("电量  ");
            text.append(left < 0 ? "左 —" : "左 " + left + "%");
            text.append("   ");
            text.append(right < 0 ? "右 —" : "右 " + right + "%");
            if (caseBattery >= 0) {
                text.append("   盒 ").append(caseBattery).append('%');
            }
            battery.setText(text);
        }
        TextView modeView = panel.findViewWithTag("flymeears-mode");
        if (modeView != null) {
            modeView.setText(connected ? "降噪模式：" + EncoSnapshot.modeName(mode) : "耳机未连接");
        }
        LinearLayout buttons = panel.findViewWithTag("flymeears-buttons");
        if (buttons != null) {
            for (int i = 0; i < buttons.getChildCount(); i++) {
                View child = buttons.getChildAt(i);
                Object value = child.getTag();
                boolean active = connected && value instanceof Integer tag && tag == mode;
                child.setBackground(buttonBackground(panel.getContext(), active));
                child.setAlpha(connected ? 1f : 0.4f);
            }
        }
    }

    private static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
