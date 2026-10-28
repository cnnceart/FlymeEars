package io.github.cnnceart.flymeears.systemui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * 把降噪图标画进 Flyme 音量面板，并接管点击。
 *
 * <p>注入点是 {@code com.flyme.systemui.volume.QSVolumeControllerImpl}：
 * 它在音量变化时调用 {@code changeIconResource(int)} 决定图标，
 * 再由 {@code setUpIconFrame(int,int,int)} 做逐帧动画。做法与 flyme-mod 一致：
 * 记录它持有的 {@code mIcon}，在我们有心跳数据时替换成魅族 App 内置的三态 ANC 图标，
 * 并让点击在 关闭 / 降噪 / 通透 之间循环。
 *
 * <p>图标资源直接取 {@code com.meizu.earphone} 的 {@code dc_icon_anc_*}，
 * 与系统原生气质一致；取不到时保留原图标，不影响音量调节。
 */
public final class VolumePanelHooks {

    private static final String TAG = "FlymeEars";
    private static final String OWNER = "com.flyme.systemui.volume.QSVolumeControllerImpl";
    private static final String ICON_PACKAGE = "com.meizu.earphone";

    private static final Map<ImageView, Object> ICONS = new WeakHashMap<>();
    private static final Map<ImageView, Drawable> ORIGINALS = new WeakHashMap<>();

    private static Field iconField;
    private static Field streamField;
    private static boolean observerRegistered;

    private VolumePanelHooks() {
    }

    public static void install(XposedModule module, ClassLoader loader) {
        Log.i(TAG, "volume: install 开始");
        try {
            Class<?> owner = Class.forName(OWNER, false, loader);
            iconField = owner.getField("mIcon");
            streamField = owner.getField("mCurrentStreamType");
            Method changeIconResource = owner.getDeclaredMethod("changeIconResource", int.class);
            Method setUpIconFrame = owner.getDeclaredMethod("setUpIconFrame", int.class,
                    int.class, int.class);

            module.hook(changeIconResource).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    track((ImageView) iconField.get(chain.getThisObject()), chain.getThisObject());
                } catch (Throwable error) {
                    Log.w(TAG, "volume: 记录图标失败", error);
                }
                return result;
            });

            module.hook(setUpIconFrame).intercept(chain -> {
                try {
                    if (paint((ImageView) iconField.get(chain.getThisObject()),
                            chain.getThisObject())) {
                        // 我们自己接管了这一帧，跳过原动画避免闪回。
                        return null;
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "volume: 绘制图标失败", error);
                }
                return chain.proceed();
            });

            module.hook(View.class.getDeclaredMethod("performClick")).intercept(chain -> {
                if (chain.getThisObject() instanceof ImageView icon && ICONS.containsKey(icon)
                        && handleClick()) {
                    return true;
                }
                return chain.proceed();
            });

            Log.i(TAG, "volume: 降噪图标已挂载");
        } catch (Throwable error) {
            Log.e(TAG, "volume: Hook 安装失败", error);
        }
    }

    private static void track(ImageView icon, Object controller) {
        if (icon == null || ICONS.containsKey(icon)) {
            ensureObserver();
            return;
        }
        ICONS.put(icon, controller);
        ORIGINALS.put(icon, icon.getDrawable());
        ensureObserver();
    }

    private static void ensureObserver() {
        if (observerRegistered) {
            return;
        }
        EncoSession session = EncoSession.instance();
        if (session == null) {
            return;
        }
        session.addObserver(VolumePanelHooks::repaintAll);
        observerRegistered = true;
    }

    /** 状态变化后重画所有已知图标。 */
    private static void repaintAll() {
        for (Map.Entry<ImageView, Object> entry : ICONS.entrySet()) {
            try {
                paint(entry.getKey(), entry.getValue());
            } catch (Throwable error) {
                Log.w(TAG, "volume: 重绘失败", error);
            }
        }
    }

    private static boolean paint(ImageView icon, Object controller) throws ReflectiveOperationException {
        if (icon == null || controller == null) {
            return false;
        }
        EncoSession session = EncoSession.instance();
        if (session == null || !session.active() || session.mode() == 0) {
            return false;
        }
        if (streamField.getInt(controller) != AudioManager.STREAM_MUSIC) {
            return false;
        }
        Drawable art = art(icon.getContext(), session.mode());
        if (art == null) {
            return false;
        }
        try {
            icon.getClass().getMethod("cancelAnimation").invoke(icon);
        } catch (Throwable ignored) {
            // 不是 Lottie 视图时忽略。
        }
        icon.setImageDrawable(art);
        return true;
    }

    private static boolean handleClick() {
        EncoSession session = EncoSession.instance();
        if (session == null || !session.active() || session.mode() == 0) {
            return false;
        }
        session.cycleMode();
        Log.i(TAG, "volume: 点击切换降噪模式");
        return true;
    }

    private static Drawable art(Context context, int mode) {
        String name = switch (mode) {
            case 3 -> "dc_icon_anc_transparent_normal";
            case 2 -> "dc_icon_anc_on_normal";
            default -> "dc_icon_anc_off_normal";
        };
        try {
            Context app = context.createPackageContext(ICON_PACKAGE, 0);
            int id = app.getResources().getIdentifier(name, "drawable", ICON_PACKAGE);
            if (id == 0) {
                return null;
            }
            return app.getDrawable(id);
        } catch (Throwable error) {
            Log.w(TAG, "volume: 取魅族降噪图标失败 " + name, error);
            return null;
        }
    }
}
