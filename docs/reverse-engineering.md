# Flyme 耳机组件逆向记录

设备：MEIZU 21 Pro，Flyme 12.6.0.0A，Android 16（SDK 36）。
样本：

| 包 | 版本 | 路径 |
|---|---|---|
| `com.meizu.earphone` | 100.12.23 | `/system/app/TWS/TWS.apk` |
| `com.android.systemui` | 16.26.06.25_base | `/system_ext/priv-app/SystemUI/SystemUI.apk` |
| `com.meizu.connectivitysettings` | 12.15.000 | `/system_ext/priv-app/ConnectivitySettings/ConnectivitySettings.apk` |
| `com.heytap.headset`（欢律） | 117.6.6 | `/data/app/.../base.apk` |

## 连接弹窗链路

```text
MzBluetoothService            （com.meizu.earphone，Service）
  └─ Handler MSG_MODEL_INFO_SUCCESS (what=4)
       └─ startMzfpDialogActivity(MzfpDevice, MzfpModelInfoBean, network, connected)
            └─ Intent → MzfpDailogActivity        （exported，无权限校验）
                 └─ MzfpBluetoothDialogFragment     （DialogFragment）
                      ├─ IS_CONNECTED=true  → MzfpDeviceConnectedFragment
                      └─ IS_CONNECTED=false → MzfpDeviceFoundFragment
```

### Intent 契约

| key | 类型 | 说明 |
|---|---|---|
| `DIALOG_TYPE_KEY` | int | 1 = 蓝牙；0 会直接 finish |
| `MZFP_DEVICE_KEY` | `MzfpDevice` | 设备与电量，**必填**，为空直接 finish |
| `NETWORK_ENABLE` | boolean | true 时用 `DIALOG_MODEL_INFO` 的名称与网络图 |
| `IS_CONNECTED` | boolean | 决定显示"已连接"还是"发现设备"子页 |
| `DIALOG_MODEL_INFO` | `MzfpModelInfoBean` | 仅 `NETWORK_ENABLE=true` 时使用 |

### 关键结论

1. **电量由传入的 `MzfpDevice` 决定**，不查 SDK：
   `MzfpDeviceConnectedFragment.onViewCreated` 直接读
   `getBoxBattery() / getLeftBattery() / getRightBattery()`，按 `value & 0x7F` 截断到 0..100。
   因此外部构造 `MzfpDevice` 即可显示任意电量。
2. **名称为硬编码**：`NETWORK_ENABLE=false` 时名称来自资源
   （`MEIZU TWS 2311`），配图固定 `R.drawable.tws_2311`（魅族 PANDAER 图），
   且**不会给 `nameTextView` 赋值**。
3. 弹窗是底部 gravity 的对话框（`FLAG_ALT_FOCUSABLE_IM`，dimAmount=0），
   `onDismiss` 会 `finish()` 自己。
4. `MzfpDevice` 是标准 Parcelable，字段全部有 public setter；
   外部可用 `createPackageContext("com.meizu.earphone", CONTEXT_INCLUDE_CODE)`
   借宿主 ClassLoader 构造实例后通过 `putExtra` 传给弹窗。

## 已实测

外部构造 `MzfpDevice`（电量 88/87/95）并 `startActivity`，
原生弹窗成功渲染，电量与注入值一致：

![原生弹窗实测](popup-native-1.png)

日志：

```text
FlymeEnco: popup: 已唤起原生弹窗 2C:4D:79:66:AE:74
MzfpDailogActivity: handleIntent device id: 2C:4D:79:66:AE:74
MzfpDailogActivity: DIALOG_TYPE_BLUETOOTH
MzfpBluetoothDialogFragment: onViewCreated
```

## 待解决

| 项 | 方案 | 状态 |
|---|---|---|
| 名称显示为 MEIZU TWS 2311 | hook `MzfpDeviceConnectedFragment#onViewCreated` 后覆盖 `nameTextView` | 已实现，待 LSPosed 启用验证 |
| 配图为魅族 PANDAER | hook 内替换成模块自带的中性耳机图 | 已实现，待验证 |
| 自动触发 | SystemUI 侧监听 A2DP 连接 + 直连读电量后再拉起 | 待实现 |
| 音量面板降噪图标 | 参考 flyme-mod 的 `QSVolumeControllerImpl` 注入点 | 待实现 |
| 设备卡片电量 | `com.meizu.connectivitysettings` 待逆向 | 待实现 |

## 其它线索

- `com.meizu.earphone` 没有任何 `WindowManager` / overlay 代码，弹窗完全在
  `MzfpDailogActivity` 里完成。
- 耳机 App 暴露的跨进程入口：
  - `com.meizu.bluetooth.sdk.MzBluetoothService`（`IMzBluetooth`，flyme-mod 用 transact 18/19/20/54/56/62/63）
  - `com.meizu.earphone.biz.devicecontrol.RemotePrefService`（`IRemotePrefService`）
  - `content://com.meizu.earphone.provider.dc/data`（按 deviceId 读 `DC_DATA_PREF_<id>`）
- 已配对设备：`OPPO Enco X2`，地址 `2C:4D:79:66:AE:74`。
