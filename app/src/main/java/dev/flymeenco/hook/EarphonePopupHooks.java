package dev.flymeenco.hook;

import android.bluetooth.BluetoothDevice;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 在 {@code com.meizu.earphone} 进程里修正原生弹窗的内容。
 *
 * <p>{@code MzfpDailogActivity} 的 {@code MzfpDeviceConnectedFragment} 只按
 * {@code NETWORK_ENABLE} 决定名称与配图：为 false 时名称固定成 "MEIZU TWS 2311"、
 * 配图固定成内置的魅族耳机图。我们不联网、也不想伪造 ModelInfo，于是直接在这两个
 * 私有字段赋值之后覆盖成真实设备名；电量本来就来自我们构造的 {@code MzfpDevice}。
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
                    apply(chain.getThisObject());
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

    private static void apply(Object fragment) throws ReflectiveOperationException {
        Class<?> type = fragment.getClass();
        Object mzfpDevice = field(type, fragment, "mMzfpDevice");
        if (mzfpDevice == null) {
            return;
        }
        String name = deviceName(mzfpDevice);
        if (name == null || name.isEmpty()) {
            return;
        }

        Object nameView = field(type, fragment, "nameTextView");
        if (nameView instanceof TextView text) {
            text.setText(name);
        }

        Object iconView = field(type, fragment, "deviceIconImageView");
        if (iconView instanceof ImageView icon) {
            android.graphics.drawable.Drawable art = moduleArtwork(icon);
            if (art != null) {
                icon.setImageDrawable(art);
            }
            icon.setContentDescription(name);
        }
    }

    /** 从本模块 APK 取中性耳机图，避免显示魅族自家产品图。 */
    private static android.graphics.drawable.Drawable moduleArtwork(ImageView icon) {
        try {
            android.content.Context module = icon.getContext()
                    .createPackageContext("dev.flymeenco", 0);
            return module.getDrawable(dev.flymeenco.R.drawable.enco_generic);
        } catch (Throwable error) {
            Log.w(TAG, "popup hook: 取模块配图失败，保留原图", error);
            return null;
        }
    }

    private static String deviceName(Object mzfpDevice) throws ReflectiveOperationException {
        Method getter = mzfpDevice.getClass().getMethod("getBluetoothDevice");
        Object device = getter.invoke(mzfpDevice);
        if (device instanceof BluetoothDevice bluetooth) {
            String value = bluetooth.getName();
            return value == null ? bluetooth.getAddress() : value;
        }
        return null;
    }

    private static Object field(Class<?> type, Object target, String name)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
