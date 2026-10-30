package io.github.cnnceart.flymeears.hook;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.cnnceart.flymeears.EncoIntents;
import io.github.cnnceart.flymeears.protocol.OppoWireCodec;
import io.github.libxposed.api.XposedModule;

/**
 * 给原生「高品质音频」页加上第三行 LDAC。
 *
 * <p>原生布局 {@code cfg_activity_high_quality.xml} 只有 AAC / LHDC 两行，
 * {@code CfgHighQualityActivity.m(model)} 按 {@code model.o}（1=AAC、2=LHDC）切换勾选。
 * 这里在 onCreate 之后克隆 LHDC 行插到它下面，点击时下发
 * {@code ACTION_SET_CODEC(3)}，勾选态同样由 {@code model.o == 3} 决定 —— 页面没有新增
 * 任何业务逻辑，只是多了一个可点的行。
 */
public final class CodecPageHooks {

    private static final String TAG = "FlymeEars";
    private static final String PAGE =
            "com.meizu.earphone.biz.configuration.activity.CfgHighQualityActivity";
    private static final int ROW_TAG = 0x0f1e0011;
    private static final int INDICATOR_TAG = 0x0f1e0012;
    private static final String LDAC_TITLE = "LDAC";
    private static final String LDAC_DESC = "高解析无线音频，需耳机与手机同时支持";

    private CodecPageHooks() {
    }

    public static void install(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> page = Class.forName(PAGE, false, loader);
        Method onCreate = page.getDeclaredMethod("onCreate", android.os.Bundle.class);
        module.hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (chain.getThisObject() instanceof Activity activity) {
                    injectRow(activity);
                    applyCurrent(activity);
                }
            } catch (Throwable error) {
                Log.w(TAG, "codec: 注入 LDAC 行失败", error);
            }
            return result;
        });

        Class<?> model = Class.forName(Host.STATUS, false, loader);
        Method apply = page.getDeclaredMethod("m", model);
        module.hook(apply).intercept(chain -> {
            Object result = chain.proceed();
            try {
                if (chain.getThisObject() instanceof Activity activity) {
                    applyRow(activity, chain.getArg(0));
                }
            } catch (Throwable error) {
                Log.w(TAG, "codec: 刷新 LDAC 勾选失败", error);
            }
            return result;
        });
        Log.i(TAG, "codec: 高品质页 LDAC 行 Hook 已挂载");
    }

    /** 克隆 LHDC 行：同一份 XML、同样的文字样式，只是标题/说明/勾选由模块维护。 */
    private static void injectRow(Activity activity) {
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        Resources resources = activity.getResources();
        String pkg = activity.getPackageName();
        View existing = (View) decor.getTag(ROW_TAG);
        if (existing != null && existing.getParent() != null) {
            return;
        }
        int layoutId = resources.getIdentifier("cfg_activity_high_quality", "layout", pkg);
        int lhdcId = resources.getIdentifier("lhdc", "id", pkg);
        if (layoutId == 0 || lhdcId == 0) {
            Log.w(TAG, "codec: 找不到高品质页资源");
            return;
        }
        View cloneRoot = activity.getLayoutInflater().inflate(layoutId, null, false);
        View row = cloneRoot.findViewById(lhdcId);
        if (!(row instanceof LinearLayout line) || !(row.getParent() instanceof ViewGroup parent)) {
            Log.w(TAG, "codec: 克隆 LHDC 行失败");
            return;
        }
        parent.removeView(row);
        rebrand(row);
        row.setId(View.generateViewId());
        ImageView indicator = findIndicator(row);
        if (indicator != null) {
            indicator.setId(View.generateViewId());
            row.setTag(INDICATOR_TAG, indicator);
        }

        View lhdcRow = activity.findViewById(lhdcId);
        int lhdcModeId = resources.getIdentifier("lhdc_mode", "id", pkg);
        View lhdcMode = lhdcModeId == 0 ? null : activity.findViewById(lhdcModeId);
        ViewGroup container = (ViewGroup) line.getParent();
        if (container == null) {
            // 行已经从克隆树里摘下来，容器要按页面结构回找。
            container = lhdcRow != null && lhdcRow.getParent() instanceof ViewGroup group
                    ? group : null;
        }
        if (container == null) {
            Log.w(TAG, "codec: 找不到高品质页容器");
            return;
        }
        int index = lhdcRow == null ? container.getChildCount() : container.indexOfChild(lhdcRow) + 1;
        if (lhdcMode != null && container.indexOfChild(lhdcMode) >= 0
                && index > container.indexOfChild(lhdcMode)) {
            index = container.indexOfChild(lhdcMode);
        }
        container.addView(row, Math.min(index, container.getChildCount()));
        // 列表视觉：AAC 头部、LHDC 中间、LDAC 收尾。
        int middle = resources.getIdentifier("immersive_item_bkg_middle", "drawable", pkg);
        int foot = resources.getIdentifier("immersive_item_bkg_foot", "drawable", pkg);
        if (lhdcRow != null && middle != 0) {
            lhdcRow.setBackgroundResource(middle);
        }
        if (foot != 0) {
            row.setBackgroundResource(foot);
        }
        decor.setTag(ROW_TAG, row);
        row.setOnClickListener(view -> select(activity));
        Log.i(TAG, "codec: 已插入 LDAC 行 index=" + container.indexOfChild(row));
    }

    private static void rebrand(View row) {
        if (!(row instanceof ViewGroup group) || group.getChildCount() == 0) {
            return;
        }
        View first = group.getChildAt(0);
        if (first instanceof ViewGroup textGroup) {
            if (textGroup.getChildAt(0) instanceof TextView title) {
                title.setText(LDAC_TITLE);
            }
            if (textGroup.getChildAt(1) instanceof TextView desc) {
                desc.setText(LDAC_DESC);
            }
        }
        View indicator = group.getChildCount() > 1 ? group.getChildAt(1) : null;
        if (indicator instanceof ImageView image) {
            image.setVisibility(View.GONE);
        }
    }

    private static ImageView findIndicator(View row) {
        if (row instanceof ViewGroup group && group.getChildCount() > 1
                && group.getChildAt(1) instanceof ImageView image) {
            return image;
        }
        return null;
    }

    /** 点击：和原生两行一样只下发编码，勾选等 0x0114 回读后由 model 刷新。 */
    private static void select(Activity activity) {
        Log.i(TAG, "codec: 原生页面选择 LDAC");
        try {
            activity.sendBroadcast(new Intent(EncoIntents.ACTION_SET_CODEC)
                    .setPackage("com.android.systemui")
                    .putExtra(EncoIntents.EXTRA_CODEC, OppoWireCodec.CODEC_LDAC)
                    .putExtra(EncoIntents.EXTRA_HI_RES, false));
        } catch (Throwable error) {
            Log.w(TAG, "codec: 下发 LDAC 失败", error);
        }
    }

    private static void applyCurrent(Activity activity) {
        Object model = null;
        try {
            Object device = Host.readField(activity, "b", "f4566b");
            if (device == null) {
                device = Host.device();
            }
            model = device == null ? null : Host.call(device, "n");
        } catch (Throwable error) {
            Log.w(TAG, "codec: 读取当前模型失败", error);
        }
        if (model != null) {
            applyRow(activity, model);
        }
    }

    /** model.o：1 AAC / 2 LHDC / 3 LDAC。LDAC 选中时隐藏原生两行的勾选与 Hi-Res 行。 */
    private static void applyRow(Activity activity, Object model) {
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null || model == null) {
            return;
        }
        View row = (View) decor.getTag(ROW_TAG);
        if (row == null) {
            return;
        }
        int codec = 2;
        try {
            Object value = Host.readField(model, "o", "f4517o");
            if (value instanceof Integer selected) {
                codec = selected;
            }
        } catch (Throwable error) {
            return;
        }
        boolean ldac = codec == OppoWireCodec.CODEC_LDAC;
        Object indicator = row.getTag(INDICATOR_TAG);
        if (indicator instanceof ImageView image) {
            image.setVisibility(ldac ? View.VISIBLE : View.GONE);
        }
        View aac = findViewById(activity, "aac_indicator");
        View lhdc = findViewById(activity, "lhdc_indicator");
        View lhdcMode = findViewById(activity, "lhdc_mode");
        if (ldac) {
            if (aac != null) {
                aac.setVisibility(View.GONE);
            }
            if (lhdc != null) {
                lhdc.setVisibility(View.GONE);
            }
            if (lhdcMode != null) {
                lhdcMode.setVisibility(View.GONE);
            }
        }
    }

    private static View findViewById(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : activity.findViewById(id);
    }
}
