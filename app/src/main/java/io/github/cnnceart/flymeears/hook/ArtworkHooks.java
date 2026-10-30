package io.github.cnnceart.flymeears.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 把宿主里的魅族耳机产品图换成 OPPO Enco X2 素材。
 *
 * <p>目标：
 * <ul>
 *   <li>设备列表行图标、扫描结果行图标（宿主进程）；</li>
 *   <li>详情页顶部的 3D 产品图（宿主进程，用静态图覆盖 SpatialView）；</li>
 *   <li>设备中心卡片的大图（SystemUI 进程，插件通过 setImageResource 设置）。</li>
 * </ul>
 */
public final class ArtworkHooks {

    private static final String TAG = "FlymeEars";
    private static final int OVERLAY_TAG = 0x0f1e0004;
    private static boolean cardArtLogged;
    private static boolean cardSeen;

    private ArtworkHooks() {
    }

    /** com.meizu.earphone 进程：详情页 3D 图、扫描页图标。 */
    public static void installEarphone(XposedModule module, ClassLoader loader) {
        try {
            Class<?> viewHolder = Class.forName(
                    "androidx.recyclerview.widget.RecyclerView$ViewHolder", false, loader);
            // 详情页产品图（CfgActivity 的列表第一项）
            Class<?> cfgAdapter = Class.forName("p5.i", false, loader);
            Method bind = cfgAdapter.getDeclaredMethod("onBindViewHolder", viewHolder, int.class);
            module.hook(bind).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    coverHero(chain.getArg(0));
                } catch (Throwable error) {
                    Log.w(TAG, "art: 详情页配图失败", error);
                }
                return result;
            });
            // 扫描结果行图标
            Class<?> scanAdapter = Class.forName("j6.e", false, loader);
            Method scanBind = scanAdapter.getDeclaredMethod("onBindViewHolder", viewHolder, int.class);
            module.hook(scanBind).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    paintIcon(chain.getArg(0), "scan_item_device_icon", 160);
                } catch (Throwable error) {
                    Log.w(TAG, "art: 扫描行图标失败", error);
                }
                return result;
            });
            Log.i(TAG, "art: 宿主配图 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "art: 宿主配图 Hook 失败", error);
        }
    }

    /** SystemUI 进程：设备中心卡片的大图。 */
    public static void installSystemUi(XposedModule module, ClassLoader loader) {
        try {
            Method setImageResource = ImageView.class.getDeclaredMethod(
                    "setImageResource", int.class);
            module.hook(setImageResource).intercept(chain -> {
                ImageView view = (ImageView) chain.getThisObject();
                int id = ((Number) chain.getArg(0)).intValue();
                if (isPluginBox(view) || isCardBoxResource(view, id)) {
                    if (!cardSeen) {
                        cardSeen = true;
                        Log.i(TAG, "art: 命中卡片大图 hook id=" + id);
                    }
                    if (Artwork.applyChecked(view, Artwork.CARD, 500)) {
                        if (!cardArtLogged) {
                            cardArtLogged = true;
                            Log.i(TAG, "art: 设备中心卡片大图已替换");
                        }
                        return null;
                    }
                    Log.w(TAG, "art: 卡片素材不可用，保留原图");
                }
                return chain.proceed();
            });
            Log.i(TAG, "art: 设备中心卡片配图 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "art: 设备中心配图 Hook 失败", error);
        }

        // 耳机在耳时充电盒不上报电量，原生插件会直接显示 "-1%"；改成 "--"。
        try {
            Method setText = android.widget.TextView.class.getDeclaredMethod(
                    "setText", CharSequence.class);
            module.hook(setText).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof android.widget.TextView view
                        && !IN_BATTERY_TEXT.get()) {
                    decorateBatteryText(view);
                }
                return result;
            });
            Log.i(TAG, "art: 设备中心电量占位 Hook 已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "art: 设备中心电量占位 Hook 失败", error);
        }
    }

    /** setText 里再 setText 会重入，用标记跳过。 */
    private static final ThreadLocal<Boolean> IN_BATTERY_TEXT = ThreadLocal.withInitial(() -> false);

    /**
     * 卡片的电量文字由插件按 model 直接写，充电态它不管；这里补/撤闪电。
     *
     * <p>先去掉旧符号再按当前状态重加，避免堆叠或状态变了不撤。
     */
    private static void decorateBatteryText(android.widget.TextView view) {
        String side = batterySide(view);
        if (side == null) {
            return;
        }
        CharSequence current = view.getText();
        String base = current == null ? "" : current.toString().replace("⚡", "");
        if (base.equals("-1%")) {
            base = "--";
        }
        boolean charging = charging(
                io.github.cnnceart.flymeears.systemui.EncoSession.currentSnapshot(), side);
        String next = charging ? base + "⚡" : base;
        String shown = current == null ? "" : current.toString();
        if (!next.equals(shown)) {
            IN_BATTERY_TEXT.set(true);
            try {
                view.setText(next);
            } finally {
                IN_BATTERY_TEXT.set(false);
            }
        }
    }

    private static String batterySide(android.widget.TextView view) {
        android.content.Context context = view.getContext();
        if (context == null || !"com.meizu.earphone".equals(context.getPackageName())) {
            return null;
        }
        int id = view.getId();
        for (String name : new String[]{"left_charge_tv", "right_charge_tv", "box_charge_tv"}) {
            if (id == context.getResources().getIdentifier(name, "id", "com.meizu.earphone")) {
                return name.startsWith("left") ? "left"
                        : name.startsWith("right") ? "right" : "box";
            }
        }
        return null;
    }

    private static boolean charging(io.github.cnnceart.flymeears.bt.EncoSnapshot snapshot, String side) {
        // 会话里已经按「在盒=充电」补过态，快照只有设备原始 bit7。
        return io.github.cnnceart.flymeears.systemui.EncoSession.chargingOf(side)
                || switch (side) {
                    case "left" -> snapshot != null && snapshot.leftCharging;
                    case "right" -> snapshot != null && snapshot.rightCharging;
                    default -> snapshot != null && snapshot.caseCharging;
                };
    }

    private static boolean isPluginBattery(android.widget.TextView view) {
        android.content.Context context = view.getContext();
        if (context == null || !"com.meizu.earphone".equals(context.getPackageName())) {
            return false;
        }
        int id = view.getId();
        for (String name : new String[]{"left_charge_tv", "right_charge_tv", "box_charge_tv"}) {
            if (id == context.getResources().getIdentifier(name, "id", "com.meizu.earphone")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 设备中心卡片的大图是插件自己的 drawable（{@code dc_earphone_box_2311} 等），
     * 插件跑在 SystemUI 里、Context 是插件 Context，按资源名匹配比按包名稳。
     */
    private static boolean isCardBoxResource(ImageView view, int id) {
        if (view == null || id == 0) {
            return false;
        }
        try {
            String name = view.getResources().getResourceName(id);
            return name != null && name.contains("dc_earphone_box");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPluginBox(ImageView view) {
        if (view == null) {
            return false;
        }
        // 插件跑在 SystemUI 里，Context 是插件 Context；资源 id 直接按宿主包名查。
        try {
            android.content.res.Resources resources = view.getResources();
            int id = resources.getIdentifier(
                    "iv_plugin_earphone_box", "id", "com.meizu.earphone");
            return id != 0 && view.getId() == id;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void paintIcon(Object holder, String idName, int size) {
        if (!(holder instanceof android.view.View) && holder != null) {
            // ViewHolder 不是 View，需要读 itemView
        }
        Object view = itemView(holder);
        if (!(view instanceof ImageView icon)) {
            return;
        }
        Context context = icon.getContext();
        int id = context.getResources().getIdentifier(idName, "id", context.getPackageName());
        if (id == 0) {
            return;
        }
        Artwork.apply(icon, Artwork.CASE, size);
    }

    /**
     * 详情页顶部是 SpatialView 里的 3D 魅族耳机；用静态产品图盖住它。
     * 只做一次，复用 itemView 时不会重复添加。
     */
    private static void coverHero(Object holder) {
        Object view = itemView(holder);
        if (!(view instanceof FrameLayout root)) {
            return;
        }
        Context context = root.getContext();
        int spatialId = context.getResources().getIdentifier(
                "cfg_spatialView", "id", context.getPackageName());
        if (spatialId == 0) {
            return;
        }
        View spatial = root.findViewById(spatialId);
        if (spatial == null || root.getTag(OVERLAY_TAG) != null) {
            return;
        }
        ImageView art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        art.setBackgroundColor(Color.TRANSPARENT);
        int height = Math.round(300 * context.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(art, params);
        Artwork.apply(art, Artwork.PAIR, 900);
        spatial.setVisibility(View.GONE);
        root.setTag(OVERLAY_TAG, art);
        Log.i(TAG, "art: 详情页产品图已替换");
    }

    private static Object itemView(Object holder) {
        try {
            return holder.getClass().getField("itemView").get(holder);
        } catch (Throwable error) {
            return null;
        }
    }
}
