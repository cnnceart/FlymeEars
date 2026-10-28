package dev.flymeenco.hook;

import android.bluetooth.BluetoothDevice;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 在 {@code com.meizu.earphone} 进程里修正原生弹窗内容。
 *
 * <p>弹窗布局 {@code mzfp_device_connected_fragment} 有三张静态魅族产品图：
 * {@code iv_tws_box}（充电盒）、{@code iv_tws_left}、{@code iv_tws_right}。
 * 名称与充电盒图由 Intent 里的 {@code ModelInfoBean} 决定（见 {@code PopupLauncher}），
 * 这里只补左右单耳，避免显示魅族 PANDAER 耳机。
 *
 * <p>注意：宿主进程不一定持有 {@code BLUETOOTH_CONNECT}，任何取设备名的操作都要容错，
 * 否则会抛 {@code SecurityException} 打断弹窗初始化。
 */
public final class EarphonePopupHooks {

    private static final String TAG = "FlymeEnco";
    private static final String FRAGMENT = "com.meizu.bluetooth.sdk.MzfpDeviceConnectedFragment";

    private EarphonePopupHooks() {
    }

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(FRAGMENT, false, loader);
            Method onViewCreated = type.getDeclaredMethod("onViewCreated", View.class,
                    android.os.Bundle.class);
            module.hook(onViewCreated).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    apply(chain.getThisObject(), (View) chain.getArg(0));
                } catch (Throwable error) {
                    Log.w(TAG, "popup hook: 修正弹窗内容失败", error);
                }
                return result;
            });
            Log.i(TAG, "popup hook: 已挂载 " + FRAGMENT);
        } catch (ClassNotFoundException error) {
            Log.i(TAG, "popup hook: 未找到 " + FRAGMENT);
        } catch (Throwable error) {
            Log.e(TAG, "popup hook: 安装失败", error);
        }
    }

    private static void apply(Object fragment, View root) {
        if (root == null) {
            return;
        }
        replace(root, "iv_tws_left", dev.flymeenco.R.raw.enco_bud_left);
        replace(root, "iv_tws_right", dev.flymeenco.R.raw.enco_bud_right);
        applyName(fragment);
    }

    private static void replace(View root, String idName, int rawResource) {
        int id = root.getResources().getIdentifier(idName, "id", "com.meizu.earphone");
        if (id == 0) {
            Log.i(TAG, "popup hook: 布局里没有 " + idName);
            return;
        }
        View target = root.findViewById(id);
        if (!(target instanceof ImageView icon)) {
            return;
        }
        Drawable art = artwork(icon, rawResource);
        if (art == null) {
            return;
        }
        // 单只耳机比原来的窄，保持等比并居中，避免被拉伸。
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageDrawable(art);
    }

    /** 名称优先来自 ModelInfoBean；这里只作为兜底，允许因权限不足而跳过。 */
    private static void applyName(Object fragment) {
        try {
            Class<?> type = fragment.getClass();
            Object mzfpDevice = field(type, fragment, "mMzfpDevice");
            Object nameView = field(type, fragment, "nameTextView");
            if (!(nameView instanceof TextView text) || text.getText().length() > 0) {
                return;
            }
            String name = deviceName(mzfpDevice);
            if (name != null && !name.isEmpty()) {
                text.setText(name);
            }
        } catch (SecurityException error) {
            Log.i(TAG, "popup hook: 宿主无蓝牙权限，名称沿用 ModelInfo");
        } catch (Throwable error) {
            Log.w(TAG, "popup hook: 名称兜底失败", error);
        }
    }

    private static String deviceName(Object mzfpDevice) throws ReflectiveOperationException {
        if (mzfpDevice == null) {
            return null;
        }
        Method getter = mzfpDevice.getClass().getMethod("getBluetoothDevice");
        Object device = getter.invoke(mzfpDevice);
        if (device instanceof BluetoothDevice bluetooth) {
            String value = bluetooth.getName();
            return value == null ? bluetooth.getAddress() : value;
        }
        return null;
    }

    private static Drawable artwork(ImageView icon, int rawResource) {
        try {
            android.content.res.Resources resources = icon.getContext()
                    .createPackageContext("dev.flymeenco", 0).getResources();
            Bitmap bitmap = BitmapFactory.decodeStream(resources.openRawResource(rawResource));
            return bitmap == null ? null : new BitmapDrawable(resources, bitmap);
        } catch (Throwable error) {
            Log.w(TAG, "popup hook: 取模块配图失败", error);
            return null;
        }
    }

    private static Object field(Class<?> type, Object target, String name)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
