package io.github.cnnceart.flymeears.popup;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 向 Flyme 耳机弹窗（Glide）提供本模块的耳机配图。
 *
 * <p>弹窗在 {@code NETWORK_ENABLE=true} 时会用 Glide 加载
 * {@code ResourceItem.discoveryResource}。这里把它指向本 Provider 的 {@code content://}，
 * 于是不联网也能显示真实产品图；左右单耳由 {@code EarphonePopupHooks} 直接替换。
 */
public final class ArtProvider extends ContentProvider {

    public static final String AUTHORITY = io.github.cnnceart.flymeears.BuildConfig.APPLICATION_ID + ".art";

    public static final String CASE = "enco_case.png";
    public static final String BUD_LEFT = "enco_bud_left.png";
    public static final String BUD_RIGHT = "enco_bud_right.png";
    public static final String PAIR = "enco_art.png";

    /** 换图时递增，让宿主按新的 content URI 重新解码，绕过位图缓存。 */
    private static final int ART_VERSION = 2;

    public static Uri uri(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + name + "?v=" + ART_VERSION);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private static final Object CACHE_LOCK = new Object();

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) {
            name = PAIR;
        }
        int resource = switch (name) {
            case CASE -> io.github.cnnceart.flymeears.R.raw.enco_case;
            case BUD_LEFT -> io.github.cnnceart.flymeears.R.raw.enco_bud_left;
            case BUD_RIGHT -> io.github.cnnceart.flymeears.R.raw.enco_bud_right;
            default -> io.github.cnnceart.flymeears.R.raw.enco_art;
        };
        try {
            // 文件名带 ART_VERSION：换图时用新文件，旧文件只写一次；
            // 同一文件由 CACHE_LOCK 串行化，避免并发 truncate 出半张图。
            File cached = new File(appContext().getCacheDir(), "v" + ART_VERSION + "_" + name);
            synchronized (CACHE_LOCK) {
                if (!cached.exists() || cached.length() == 0L) {
                    File temp = new File(cached.getParentFile(), cached.getName() + ".tmp");
                    try (InputStream input = appContext().getResources().openRawResource(resource);
                         FileOutputStream output = new FileOutputStream(temp, false)) {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = input.read(buffer)) > 0) {
                            output.write(buffer, 0, read);
                        }
                    }
                    if (!temp.renameTo(cached)) {
                        temp.delete();
                        try (InputStream input = appContext().getResources().openRawResource(resource);
                             FileOutputStream output = new FileOutputStream(cached, false)) {
                            byte[] buffer = new byte[16 * 1024];
                            int read;
                            while ((read = input.read(buffer)) > 0) {
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                }
            }
            return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception error) {
            throw new java.io.FileNotFoundException("art " + name + ": " + error);
        }
    }

    private Context appContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("no context");
        }
        return context;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
