package dev.flymeenco.ui;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.flymeenco.bt.EncoLink;
import dev.flymeenco.bt.EncoSnapshot;

/**
 * 诊断页：不依赖 Flyme，先证明「直连耳机」这条链路可用。
 *
 * <p>连上以后这里会显示协议就绪状态、电量三件套和当前降噪模式，
 * 也能直接下发模式切换。等真机验证通过，再把同一套数据接到 Flyme 音量面板。
 */
public final class MainActivity extends Activity implements EncoLink.Listener {

    private static final int REQUEST_BLUETOOTH = 1001;

    private final EncoLink link = new EncoLink();
    private final List<BluetoothDevice> candidates = new ArrayList<>();

    private TextView statusView;
    private TextView logView;
    private LinearLayout deviceList;
    private BluetoothDevice selected;
    private boolean popupOnConnect;
    private boolean popupShown;
    private int pendingMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        requestBluetoothPermission();
        popupOnConnect = getIntent() != null && getIntent().getBooleanExtra("popup_on_connect", false);
        if (getIntent() != null && getIntent().getBooleanExtra("auto_popup", false)) {
            getWindow().getDecorView().postDelayed(this::showNativePopup, 1200);
        }
        if (getIntent() != null && getIntent().getBooleanExtra("auto_link", false)) {
            getWindow().getDecorView().postDelayed(this::autoLink, 400);
        }
        String mode = getIntent() == null ? null : getIntent().getStringExtra("auto_mode");
        if (mode != null) {
            pendingMode = switch (mode) {
                case "anc" -> EncoSnapshot.MODE_ANC;
                case "transparency" -> EncoSnapshot.MODE_TRANSPARENCY;
                case "off" -> EncoSnapshot.MODE_OFF;
                default -> 0;
            };
        }
    }

    private void autoLink() {
        BluetoothDevice device = dev.flymeenco.popup.PopupLauncher.findEncoDevice();
        if (device == null) {
            appendLog("没有找到已配对的 OPPO/Enco 耳机");
            return;
        }
        appendLog("自动连接 " + device.getAddress());
        String name = device.getName();
        dev.flymeenco.protocol.OppoWireConfig config =
                "compatible".equals(getIntent().getStringExtra("auto_config"))
                        ? dev.flymeenco.protocol.OppoWireConfig.COMPATIBLE
                        : dev.flymeenco.protocol.OppoWireConfig.forDeviceName(name);
        appendLog("ANC 映射：" + config.name());
        link.start(device.getAddress(), name, config, this);
    }

    private void showNativePopup() {
        dev.flymeenco.bt.EncoSnapshot snapshot = link.snapshot();
        int left = snapshot != null && snapshot.leftBattery >= 0 ? snapshot.leftBattery : 88;
        int right = snapshot != null && snapshot.rightBattery >= 0 ? snapshot.rightBattery : 87;
        int box = snapshot != null && snapshot.caseBattery >= 0 ? snapshot.caseBattery : 95;
        boolean shown = dev.flymeenco.popup.PopupLauncher.show(
                this, dev.flymeenco.popup.PopupLauncher.findEncoDevice(), left, right, box);
        appendLog(shown ? "已请求原生弹窗" : "原生弹窗请求失败（看 logcat）");
    }

    @Override
    protected void onDestroy() {
        link.stop();
        super.onDestroy();
    }

    private android.view.View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("FlymeEnco 直连诊断");
        title.setTextSize(20);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("未连接");
        statusView.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusView);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(actions);

        actions.addView(button("关闭", v -> link.requestMode(EncoSnapshot.MODE_OFF)));
        actions.addView(button("降噪", v -> link.requestMode(EncoSnapshot.MODE_ANC)));
        actions.addView(button("通透", v -> link.requestMode(EncoSnapshot.MODE_TRANSPARENCY)));
        actions.addView(button("刷新", v -> link.refresh()));
        actions.addView(button("断开", v -> link.stop()));
        actions.addView(button("原生弹窗", v -> showNativePopup()));

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        return root;
    }

    private Button button(String label, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
        } else {
            loadDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadDevices();
            } else {
                appendLog("未授予蓝牙权限，无法枚举已配对设备");
            }
        }
    }

    private void loadDevices() {
        deviceList.removeAllViews();
        candidates.clear();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                appendLog("设备不支持蓝牙");
                return;
            }
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                if (name == null) {
                    continue;
                }
                String normalized = name.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
                if (normalized.contains("oppo") || normalized.contains("enco")) {
                    candidates.add(device);
                }
            }
        } catch (SecurityException error) {
            appendLog("枚举设备被拒绝：" + error.getMessage());
            return;
        }

        if (candidates.isEmpty()) {
            appendLog("没有找到已配对的 OPPO / Enco 耳机，请先在系统蓝牙里配对");
            return;
        }
        for (BluetoothDevice device : candidates) {
            Button button = button(device.getName() + "\n" + device.getAddress(), v -> connect(device));
            button.setAllCaps(false);
            deviceList.addView(button);
        }
    }

    private void connect(BluetoothDevice device) {
        selected = device;
        appendLog("开始连接 " + device.getAddress());
        link.start(device.getAddress(), device.getName(), this);
    }

    @Override
    public void onSnapshot(EncoSnapshot snapshot) {
        statusView.setText(snapshot.describe());
        if (pendingMode != 0 && snapshot.protocolReady) {
            int target = pendingMode;
            pendingMode = 0;
            appendLog("请求模式：" + EncoSnapshot.modeName(target));
            link.requestMode(target);
        }
        if (popupOnConnect && !popupShown && snapshot.protocolReady && snapshot.hasBattery()) {
            popupShown = true;
            appendLog("收到真实电量，准备原生弹窗：" + snapshot.describe());
            getWindow().getDecorView().postDelayed(this::showNativePopup, 600);
        }
    }

    @Override
    public void onLog(String message) {
        appendLog(message);
    }

    private void appendLog(String message) {
        logView.append(message + "\n");
    }
}
