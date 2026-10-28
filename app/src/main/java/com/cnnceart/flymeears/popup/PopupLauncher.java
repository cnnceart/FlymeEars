package com.cnnceart.flymeears.popup;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;

/**
 * 唤起 Flyme 原生耳机弹窗（{@code com.meizu.bluetooth.sdk.MzfpDailogActivity}）。
 *
 * <p>逆向结论（TWS.apk 100.12.23，详见 docs/reverse-engineering.md）：
 * <ul>
 *   <li>弹窗导出、无权限校验，是底部 gravity 的对话框；</li>
 *   <li>电量来自 Intent 里的 {@code MzfpDevice} Parcelable，完全由调用方决定；</li>
 *   <li>名称与配图有两条路：{@code NETWORK_ENABLE=false} 时写死成 MEIZU TWS 2311；
 *       {@code NETWORK_ENABLE=true} 时改用 {@code MzfpModelInfoBean} 的
 *       {@code modelName} 与 {@code discoveryResource}（Glide 加载）。</li>
 * </ul>
 *
 * <p>因此这里走 rich 路径：不 Hook 宿主，直接把名称设成真实设备名、把图片指向
 * 本模块的 {@link ArtProvider}，弹窗即为原生外观 + 我们的数据。
 */
public final class PopupLauncher {

    private static final String TAG = "FlymeEars";
    private static final String PACKAGE = "com.meizu.earphone";
    private static final String ACTIVITY = "com.meizu.bluetooth.sdk.MzfpDailogActivity";

    private PopupLauncher() {
    }

    /** 找到已配对的 OPPO / Enco 耳机。 */
    public static BluetoothDevice findEncoDevice() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return null;
            }
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                if (name == null) {
                    continue;
                }
                String normalized = name.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
                if (normalized.contains("oppo") || normalized.contains("enco")) {
                    return device;
                }
            }
        } catch (SecurityException error) {
            Log.w(TAG, "popup: 缺少蓝牙权限");
        }
        return null;
    }

    public static boolean show(Context context, BluetoothDevice device, int left, int right, int box) {
        if (device == null) {
            Log.w(TAG, "popup: 没有可用的 OPPO/Enco 设备");
            return false;
        }
        String name = displayName(device);
        try {
            Context earphone = context.createPackageContext(PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader loader = earphone.getClassLoader();

            Object mzfpDevice = buildDevice(loader, device, left, right, box);
            Intent intent = baseIntent(loader, mzfpDevice);

            try {
                Object modelInfo = buildModelInfo(loader, name);
                try {
                    Uri art = ArtProvider.uri(ArtProvider.CASE);
                    context.grantUriPermission(PACKAGE, art, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                    // Provider 已导出，授权失败不影响读取。
                }
                intent.putExtra("DIALOG_MODEL_INFO", (Parcelable) modelInfo);
                intent.putExtra("NETWORK_ENABLE", true);
            } catch (Throwable error) {
                // 拿不到 ModelInfo 时退回内置外观：名称会显示成 MEIZU TWS 2311。
                Log.w(TAG, "popup: 构造 ModelInfo 失败，退回内置外观", error);
                intent.putExtra("NETWORK_ENABLE", false);
            }

            context.startActivity(intent);
            Log.i(TAG, "popup: 已唤起原生弹窗 " + device.getAddress() + " / " + name);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "popup: 唤起失败", error);
            return false;
        }
    }

    private static String displayName(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.isEmpty() ? "OPPO Enco X2" : name;
    }

    private static Intent baseIntent(ClassLoader loader, Object mzfpDevice) {
        return new Intent()
                .setClassName(PACKAGE, ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("DIALOG_TYPE_KEY", 1)
                .putExtra("MZFP_DEVICE_KEY", (Parcelable) mzfpDevice)
                .putExtra("IS_CONNECTED", true);
    }

    private static Object buildDevice(ClassLoader loader, BluetoothDevice device,
                                      int left, int right, int box) throws Exception {
        Class<?> type = Class.forName("com.meizu.bluetooth.sdk.MzfpDevice", true, loader);
        Object value = type.getConstructor(BluetoothDevice.class).newInstance(device);
        set(type, value, "setDeviceId", String.class, device.getAddress());
        set(type, value, "setLeftBattery", int.class, left);
        set(type, value, "setRightBattery", int.class, right);
        set(type, value, "setBoxBattery", int.class, box);
        set(type, value, "setShowTime", long.class, System.currentTimeMillis());
        return value;
    }

    /**
     * 用 Parcel 往返构造 {@code MzfpModelInfoBean}：该类的构造器只有 {@code (Parcel)}，
     * 但 {@code CREATOR} 与 {@code writeToParcel} 的字段顺序是稳定的，按序写入再读回即可。
     */
    private static Object buildModelInfo(ClassLoader loader, String modelName) throws Exception {
        Class<?> beanType = Class.forName("com.meizu.bluetooth.sdk.MzfpModelInfoBean", true, loader);
        Class<?> dataType = Class.forName("com.meizu.bluetooth.sdk.MzfpModelInfoBean$ModelData", true, loader);
        Class<?> itemType = Class.forName("com.meizu.bluetooth.sdk.MzfpModelInfoBean$ResourceItem", true, loader);

        Object item = readFromParcel(itemType, parcel -> {
            parcel.writeString(ArtProvider.uri(ArtProvider.CASE).toString());
            parcel.writeString(null);
            parcel.writeString(null);
            parcel.writeStringList(null);
        });

        Object data = readFromParcel(dataType, parcel -> {
            parcel.writeInt(0);
            parcel.writeString(null);
            parcel.writeString(null);
            parcel.writeString(null);
            parcel.writeInt(0);
            parcel.writeParcelable(null, 0);
            parcel.writeString("enco-x2");
            parcel.writeString(modelName);
            parcel.writeString(null);
            parcel.writeInt(0);
            parcel.writeTypedList(Collections.singletonList((Parcelable) item));
            parcel.writeString(null);
            parcel.writeString(null);
        });

        return readFromParcel(beanType, parcel -> {
            parcel.writeByte((byte) 1);
            parcel.writeString(null);
            parcel.writeInt(0);
            parcel.writeParcelable((Parcelable) data, 0);
        });
    }

    private interface ParcelWriter {
        void write(Parcel parcel) throws Exception;
    }

    private static Object readFromParcel(Class<?> type, ParcelWriter writer) throws Exception {
        Parcel parcel = Parcel.obtain();
        try {
            writer.write(parcel);
            parcel.setDataPosition(0);
            Object creator = type.getField("CREATOR").get(null);
            Method create = creator.getClass().getMethod("createFromParcel", Parcel.class);
            return create.invoke(creator, parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static void set(Class<?> type, Object target, String method, Class<?> parameter, Object value)
            throws ReflectiveOperationException {
        Method setter = type.getMethod(method, parameter);
        setter.invoke(target, value);
    }
}
