package dev.flymeears.hook;

import android.bluetooth.BluetoothDevice;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dev.flymeears.popup.ArtProvider;
import io.github.libxposed.api.XposedModule;

/**
 * 在 {@code com.meizu.earphone} 进程里修正原生弹窗内容。
 *
 * <p>弹窗布局 {@code mzfp_device_connected_fragment} 有三张静态魅族产品图：
 * {@code iv_tws_box}（充电盒）、{@code iv_tws_left}、{@code iv_tws_right}。
 * 名称与充电盒图由 Intent 里的 {@code ModelInfoBean} 决定（见 {@code PopupLauncher}），
 * 这里统一把三张图换成模块 {@link ArtProvider} 提供的图片。
 *
 * <p>注意两点：
 * <ul>
 *   <li>宿主进程没有 {@code BLUETOOTH_CONNECT}，取设备名必须容错；</li>
 *   <li>宿主看不到本模块的包（Android 包可见性），所以不能读模块资源，
 *       只能通过导出的 {@code content://} 取图。</li>
 * </ul>
 */
public final class EarphonePopupHooks {

    private static final String TAG = "FlymeEars";
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
        replace(root, "iv_tws_box", ArtProvider.CASE);
        replace(root, "iv_tws_left", ArtProvider.BUD_LEFT);
        replace(root, "iv_tws_right", ArtProvider.BUD_RIGHT);
        hideMissingComponents(root, fragment);
        applyName(fragment);
        applyFunctionButton(fragment);
    }

    /**
     * 原生的「功能介绍」讲的是魅族耳机的功能，对 OPPO 耳机没有意义。
     * 改成「耳机设置」并打开本模块的设置页，让弹窗成为可用的入口。
     */
    private static void applyFunctionButton(Object fragment) {
        try {
            Object button = field(fragment.getClass(), fragment, "functionButton");
            if (!(button instanceof android.widget.Button target)) {
                return;
            }
            target.setText("耳机设置");
            target.setOnClickListener(view -> {
                try {
                    android.content.Intent intent = new android.content.Intent()
                            .setClassName(dev.flymeears.BuildConfig.APPLICATION_ID,
                                    "dev.flymeears.ui.MainActivity")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    view.getContext().startActivity(intent);
                    // 弹窗使命完成，收起来。
                    android.app.Activity activity = (android.app.Activity) view.getContext();
                    activity.finish();
                } catch (Throwable error) {
                    Log.w(TAG, "popup hook: 打开设置失败", error);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "popup hook: 改写入按钮失败", error);
        }
    }

    /**
     * 耳机在耳时充电盒不上报电量（MzfpDevice 里是 -1）。原生代码只会跳过绘制，
     * 视图仍保留默认的 "100"，所以按数值把整行隐藏掉。
     */
    private static void hideMissingComponents(View root, Object fragment) {
        try {
            Class<?> type = fragment.getClass();
            Object mzfpDevice = field(type, fragment, "mMzfpDevice");
            if (mzfpDevice == null) {
                return;
            }
            int box = (int) mzfpDevice.getClass().getMethod("getBoxBattery").invoke(mzfpDevice);
            if (box < 0) {
                hide(root, "box_charge_content");
                hide(root, "iv_box_icon");
            }
            int left = (int) mzfpDevice.getClass().getMethod("getLeftBattery").invoke(mzfpDevice);
            if (left < 0) {
                hide(root, "left_charge_content");
                hide(root, "iv_left_icon");
            }
            int right = (int) mzfpDevice.getClass().getMethod("getRightBattery").invoke(mzfpDevice);
            if (right < 0) {
                hide(root, "right_charge_content");
                hide(root, "iv_right_icon");
            }
        } catch (Throwable error) {
            Log.w(TAG, "popup hook: 隐藏缺失电量失败", error);
        }
    }

    private static void hide(View root, String idName) {
        int id = root.getResources().getIdentifier(idName, "id", "com.meizu.earphone");
        if (id == 0) {
            return;
        }
        View target = root.findViewById(id);
        if (target != null) {
            target.setVisibility(View.INVISIBLE);
        }
    }

    private static void replace(View root, String idName, String artName) {
        int id = root.getResources().getIdentifier(idName, "id", "com.meizu.earphone");
        if (id == 0) {
            Log.i(TAG, "popup hook: 布局里没有 " + idName);
            return;
        }
        View target = root.findViewById(id);
        if (!(target instanceof ImageView icon)) {
            return;
        }
        Uri uri = ArtProvider.uri(artName);
        try {
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageURI(null);
            icon.setImageURI(uri);
            if (icon.getDrawable() == null) {
                Log.w(TAG, "popup hook: " + idName + " 读取为空 " + uri);
            }
        } catch (Throwable error) {
            Log.w(TAG, "popup hook: " + idName + " 设定失败 " + uri, error);
        }
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

    private static Object field(Class<?> type, Object target, String name)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
