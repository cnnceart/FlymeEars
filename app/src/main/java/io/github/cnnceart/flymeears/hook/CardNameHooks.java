package io.github.cnnceart.flymeears.hook;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 设备中心卡片「未连接」时显示的名字取自宿主的 {@code DC_DATA_PREF_<id>} JSON，
 * 里面存的是短名（"Enco X2"），连上后才被状态模型里的全名覆盖。
 *
 * <p>这里在 ContentProvider 返回前把 JSON 里的短名换成蓝牙配对名，卡片两种状态同名。
 */
public final class CardNameHooks {

    private static final String TAG = "FlymeEars";
    private static final String PROVIDER =
            "com.meizu.earphone.biz.devicecontrol.SPProvider";
    private static boolean logged;

    private CardNameHooks() {
    }

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> provider = Class.forName(PROVIDER, false, loader);
            Method query = provider.getDeclaredMethod("query", Uri.class, String[].class,
                    String.class, String[].class, String.class);
            module.hook(query).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    return rewrite(result);
                } catch (Throwable error) {
                    Log.w(TAG, "card: 改写卡片名称失败", error);
                    return result;
                }
            });
            Log.i(TAG, "card: 设备中心名称 Hook 已挂载");
        } catch (Throwable error) {
            Log.w(TAG, "card: 设备中心名称 Hook 失败", error);
        }
    }

    private static Object rewrite(Object result) {
        if (!(result instanceof MatrixCursor cursor) || cursor.getCount() == 0) {
            return result;
        }
        int valueIndex = cursor.getColumnIndex("value");
        if (valueIndex < 0) {
            return result;
        }
        String[] columns = cursor.getColumnNames();
        MatrixCursor out = new MatrixCursor(columns);
        String[] row = new String[columns.length];
        boolean changed = false;
        cursor.moveToPosition(-1);
        while (cursor.moveToNext()) {
            for (int i = 0; i < columns.length; i++) {
                row[i] = cursor.getString(i);
            }
            if (valueIndex < row.length) {
                String fixed = withFullName(row[valueIndex]);
                if (!fixed.equals(row[valueIndex])) {
                    row[valueIndex] = fixed;
                    changed = true;
                }
            }
            out.addRow(row.clone());
        }
        if (!changed) {
            // 没有改写就原样返回；close 之后再把同一个 cursor 交回宿主会让 provider 返回空结果。
            return result;
        }
        cursor.close();
        if (!logged) {
            logged = true;
            Log.i(TAG, "card: 卡片名称已用蓝牙全名修正");
        }
        return out;
    }

    /** 短名（去掉品牌前缀）→ 蓝牙全名；已包含全名时原样返回。 */
    static String withFullName(String json) {
        return withFullName(json, DeviceListHooks.ourName());
    }

    /** 纯函数版本，便于单测；full 为空时原样返回。 */
    static String withFullName(String json, String full) {
        if (json == null || full == null || full.isEmpty() || json.contains(full)) {
            return json;
        }
        String shortName = full.replaceFirst("(?i)^(OPPO|realme|OnePlus|MEIZU|Meizu)\\s+", "");
        if (shortName.isEmpty() || shortName.equals(full) || !json.contains(shortName)) {
            return json;
        }
        return json.replace(shortName, full);
    }
}
