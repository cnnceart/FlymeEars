package io.github.cnnceart.flymeears.hook;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.util.Log;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用模块自己的 OPPO Enco X2 素材替换宿主的魅族耳机图。
 *
 * <p>素材经 {@code content://io.github.cnnceart.flymeears.art/...} 提供（弹窗用的同一套），
 * 宿主进程能直接读；按尺寸缓存，避免每次绑定都解码大图。
 */
public final class Artwork {

    private static final String TAG = "FlymeEars";
    private static final String AUTHORITY = io.github.cnnceart.flymeears.BuildConfig.APPLICATION_ID + ".art";
    private static final ConcurrentHashMap<String, Bitmap> CACHE = new ConcurrentHashMap<>();

    public static final String CASE = "enco_case.png";
    /** 设备中心卡片用：黑色开盖盒 + 耳机，和白色魅族盒区别明显。 */
    public static final String CARD = "enco_card.png";
    public static final String PAIR = "enco_art.png";

    private Artwork() {
    }

    /** 读取并缩放素材；失败返回 null，调用方保留原图。 */
    public static Bitmap get(Context context, String name, int maxPixels) {
        String key = name + "@" + maxPixels;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream probe = open(context, name)) {
                BitmapFactory.decodeStream(probe, null, bounds);
            }
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= maxPixels) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream input = open(context, name)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
            if (bitmap == null) {
                return null;
            }
            if (Math.max(bitmap.getWidth(), bitmap.getHeight()) > maxPixels) {
                float scale = maxPixels / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            }
            CACHE.put(key, bitmap);
            return bitmap;
        } catch (Throwable error) {
            Log.w(TAG, "art: 读取 " + name + " 失败", error);
            return null;
        }
    }

    /**
     * 优先从模块 APK 的 Java 资源读（不受 Android 11 包可见性影响），
     * 读不到再退回 content:// provider。
     */
    private static InputStream open(Context context, String name) throws Exception {
        InputStream resource = Artwork.class.getResourceAsStream("/art/" + name);
        if (resource != null) {
            return resource;
        }
        return context.getContentResolver().openInputStream(
                Uri.parse("content://" + AUTHORITY + "/" + name));
    }

    /** 同 {@link #apply}，但返回是否真的换上了（失败时调用方应保留原图）。 */
    public static boolean applyChecked(ImageView view, String name, int maxPixels) {
        if (view == null) {
            return false;
        }
        Bitmap bitmap = get(view.getContext(), name, maxPixels);
        if (bitmap == null) {
            return false;
        }
        view.setImageDrawable(new BitmapDrawable(view.getResources(), bitmap));
        return true;
    }

    /** 把 ImageView 换成素材；找不到素材时什么都不做。 */
    public static void apply(ImageView view, String name, int maxPixels) {
        if (view == null) {
            return;
        }
        Bitmap bitmap = get(view.getContext(), name, maxPixels);
        if (bitmap != null) {
            view.setImageDrawable(new BitmapDrawable(view.getResources(), bitmap));
        }
    }

    /** 按资源名找 id（宿主包里的布局 id）。 */
    public static int id(Context context, String name) {
        Resources resources = context.getResources();
        return resources.getIdentifier(name, "id", context.getPackageName());
    }
}
