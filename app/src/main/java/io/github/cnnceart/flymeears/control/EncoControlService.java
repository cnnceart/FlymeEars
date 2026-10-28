package io.github.cnnceart.flymeears.control;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.service.controls.Control;
import android.service.controls.ControlsProviderService;
import android.service.controls.actions.ControlAction;
import android.service.controls.templates.StatelessTemplate;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import io.github.cnnceart.flymeears.bt.EncoLink;
import io.github.cnnceart.flymeears.bt.EncoSnapshot;
import io.github.cnnceart.flymeears.ui.MainActivity;

/**
 * 把 OPPO Enco X2 发布成 Flyme 设备中心里的一张原生卡片。
 *
 * <p>Flyme 的「设备中心」基于 Android Device Controls（{@link ControlsProviderService}），
 * 魅族自己的耳机也是这么发布的（{@code TWSControlService}）。
 *
 * <p>协议要求：
 * <ul>
 *   <li>{@link #createPublisherForAllAvailable()} 只能发**无状态**控件
 *       （{@link Control.StatelessBuilder}），否则框架会一直等 onLoad 直到超时；</li>
 *   <li>{@link #createPublisherFor(List)} 才发带模板的状态控件。</li>
 * </ul>
 */
public final class EncoControlService extends ControlsProviderService implements EncoLink.Listener {

    public static final String CONTROL_ID = "enco-x2";
    private static final String TAG = "FlymeEars";
    private static final int ICON_SIZE = 256;

    private record Subscriber(Flow.Subscriber<? super Control> subscriber, boolean stateful) {
    }

    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    private volatile EncoLink link;
    private volatile EncoSnapshot snapshot;
    private Bitmap icon;

    @Override
    public Flow.Publisher<Control> createPublisherForAllAvailable() {
        Log.i(TAG, "control: createPublisherForAllAvailable");
        return publisher(false);
    }

    @Override
    public Flow.Publisher<Control> createPublisherFor(List<String> controlIds) {
        Log.i(TAG, "control: createPublisherFor " + controlIds);
        return publisher(true);
    }

    @Override
    public void performControlAction(String controlId, ControlAction action,
                                     Consumer<Integer> consumer) {
        int current = snapshot == null ? 0 : snapshot.mode;
        int next = switch (current) {
            case EncoSnapshot.MODE_ANC -> EncoSnapshot.MODE_TRANSPARENCY;
            case EncoSnapshot.MODE_TRANSPARENCY -> EncoSnapshot.MODE_OFF;
            default -> EncoSnapshot.MODE_ANC;
        };
        Log.i(TAG, "control: 点击切换 -> " + EncoSnapshot.modeName(next));
        EncoLink currentLink = link;
        if (currentLink != null) {
            currentLink.requestMode(next);
        }
        consumer.accept(ControlAction.RESPONSE_OK);
    }

    private Flow.Publisher<Control> publisher(boolean stateful) {
        return subscriber -> {
            Subscriber entry = new Subscriber(subscriber, stateful);
            subscribers.add(entry);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    emit(subscriber, stateful, "request");
                }

                @Override
                public void cancel() {
                    subscribers.remove(entry);
                }
            });
            // 订阅当下先发一次：不同版本的框架有的等 request，有的只看首次 onNext。
            emit(subscriber, stateful, "subscribe");
            ensureLink();
        };
    }

    private void emit(Flow.Subscriber<? super Control> subscriber, boolean stateful, String reason) {
        try {
            Control control = build(stateful);
            subscriber.onNext(control);
            Log.i(TAG, "control: 已推送(" + reason + ") " + control.getTitle()
                    + " / " + control.getSubtitle());
        } catch (Throwable error) {
            Log.w(TAG, "control: 推送失败(" + reason + ")", error);
        }
    }

    private void ensureLink() {
        if (link != null) {
            return;
        }
        synchronized (this) {
            if (link != null) {
                return;
            }
            BluetoothDevice device = findEncoDevice();
            if (device == null) {
                Log.i(TAG, "control: 没有已配对的 OPPO/Enco 耳机");
                return;
            }
            EncoLink created = new EncoLink();
            link = created;
            created.start(device.getAddress(), device.getName(), this);
        }
    }

    private BluetoothDevice findEncoDevice() {
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
            Log.w(TAG, "control: 缺少蓝牙权限", error);
        }
        return null;
    }

    private Control build(boolean stateful) {
        PendingIntent appIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        EncoSnapshot value = snapshot;
        String title = value != null && value.name != null ? value.name : "OPPO Enco X2";
        CharSequence subtitle = subtitle(value);
        Icon artwork = icon();

        if (!stateful) {
            return new Control.StatelessBuilder(CONTROL_ID, appIntent)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setStructure("FlymeEars")
                    .setCustomIcon(artwork)
                    .build();
        }
        return new Control.StatefulBuilder(CONTROL_ID, appIntent)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setStructure("FlymeEars")
                .setControlTemplate(new StatelessTemplate(CONTROL_ID))
                .setStatus(Control.STATUS_OK)
                .setCustomIcon(artwork)
                .build();
    }

    private CharSequence subtitle(EncoSnapshot value) {
        if (value == null || !value.connected) {
            return "未连接";
        }
        StringBuilder text = new StringBuilder(EncoSnapshot.modeName(value.mode));
        if (value.leftBattery >= 0) {
            text.append(" · 左 ").append(value.leftBattery).append('%');
        }
        if (value.rightBattery >= 0) {
            text.append(" · 右 ").append(value.rightBattery).append('%');
        }
        if (value.caseBattery >= 0) {
            text.append(" · 盒 ").append(value.caseBattery).append('%');
        }
        return text.toString();
    }

    private Icon icon() {
        if (icon == null) {
            try {
                Bitmap raw = BitmapFactory.decodeStream(getResources().openRawResource(
                        io.github.cnnceart.flymeears.R.raw.enco_art));
                if (raw != null) {
                    icon = Bitmap.createScaledBitmap(raw, ICON_SIZE, ICON_SIZE, true);
                }
            } catch (Throwable error) {
                Log.w(TAG, "control: 生成图标失败", error);
            }
        }
        return icon == null ? null : Icon.createWithBitmap(icon);
    }

    @Override
    public void onSnapshot(EncoSnapshot value) {
        snapshot = value;
        for (Subscriber entry : subscribers) {
            try {
                entry.subscriber().onNext(build(entry.stateful()));
            } catch (Throwable error) {
                Log.w(TAG, "control: 推送失败", error);
            }
        }
    }

    @Override
    public void onLog(String message) {
        Log.i(TAG, "control: " + message);
    }

    @Override
    public void onDestroy() {
        EncoLink current = link;
        link = null;
        if (current != null) {
            current.stop();
        }
        super.onDestroy();
    }
}
