# FlymeEars

把**第三方蓝牙耳机**接进**魅族耳机 App**（`com.meizu.earphone`）的 LSPosed 模块，
首个目标是 **OPPO Enco X2**（魅族 21 Pro / Flyme 12.6 / Android 16）。

> 状态：设备列表（含行内电量/降噪）、原生连接弹窗、控制中心降噪磁贴、
> 「添加设备」扫描页发现均已验证；设备详情页改为 SDK 层伪装，
> 原生页面自己渲染电量与噪声控制（不再叠加自绘面板）。
>
> 许可证：**GPL-3.0**（OPPO 协议实现与耳机渲染素材来自 GPL-3.0 的 HyperEars）。
>
> 说明：逆向笔记与架构文档（`docs/`）**不入库**，仅在本地保留，避免公开厂商私有实现细节。

## 致谢

本项目建立在别人的工作之上：

- [HyperEars](https://github.com/silverpoetry/HyperEars)（GPL-3.0）—— OPPO Enco 私有 RFCOMM 协议实现与耳机渲染素材，本项目的协议层是它的 Java 移植
- [flyme-mod](https://github.com/rikumi/flyme-mod)（MIT）—— 魅族耳机 SDK 的 Binder 通道用法参考
- [MelodyCodecTweaker](https://github.com/Andrea-lyz/MelodyCodecTweaker)（Apache-2.0）—— 宿主注入与诊断结构参考
- [LSPosed](https://github.com/LSPosed/LSPosed) / libxposed（Apache-2.0）—— 模块框架
- 上游协议研究（经 HyperEars 转引）：[1812z/OppoPods](https://github.com/1812z/OppoPods)、[Star-ZER0/Pods-Protocol-Reverse-Engineering](https://github.com/Star-ZER0/Pods-Protocol-Reverse-Engineering)

完整出处与许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
商标与产品名称仅用于兼容性描述，归各自权利人所有。


## 能力矩阵（真机验证）

| 能力 | 载体 | 状态 |
|---|---|---|
| 连接弹窗 | Flyme `MzfpDailogActivity`（原生底部弹窗） | ✅ 名称/配图/三件套电量，实测 88/87/95、100/90/80 |
| 设备列表 | 原生 `DeviceListActivity` 行内显示电量/降噪 | ✅「已连接 · 降噪 · 左80% 右80% 盒90%」，状态变化实时刷新 |
| 设备详情 | 原生 `CfgActivity` 零重绘：SDK 层伪装成已连接，页面自己长出电量/噪声控制/高级功能 | ✅ 80/80/90、三态 ANC 切换回读，顶部不再被遮挡 |
| 添加设备 | 原生扫描页发现第三方耳机，点「连接」直达详情 | ✅ 走原生 `DeviceDiscovered` 事件，提示与雷达随结果切换 |
| 设备中心卡片 | Flyme 控制中心的耳机卡片 | ✅「使用中」+ 三件套电量 + 当前模式同步（推送 `deviceConnectionSuccessful`/`onNoiseControlModeChanged`）|
| 耳机图标 | 设备列表 / 详情页 / 设备中心 / 连接弹窗 | ✅ 全部替换为 OPPO Enco X2 素材（模块内置，不联网）|
| 声效调节 | 原生 CfgActivity「高级功能」行 + 原生弹窗 | ✅ 默认/高音增强/均衡调音/自定义 → `0x0406`，`0x0504` 通知与 `0x010F` 回读一致 |
| 佩戴/在盒 | `0x0109` 查询 + `0x0204(type=02)` 上报 | ✅ 4=在盒 / 5=出盒未戴 / 7=佩戴，已接到 `queryWearingStatus`、BaseActivity 回调与设备中心推送 |
| 重命名 | 原生 CfgActivity「重命名」弹窗 | ✅ 写入 App 设备仓库并持久化（不写耳机自身蓝牙名）|
| 高品质音频 | 协议层读取/切换编码 | ✅ `0x0114` 读、`0x041A` 写、`0x0123` 位图；原生行 UI 待接（编码枚举待对齐）|
| 降噪控制 | 控制中心「降噪」磁贴 + 详情页按钮（标签带电量） | ✅ `0x8404 00` 确认，回读切到「降噪」/「通透」 |
| 电量 | 弹窗 + 磁贴标签 + 列表行 + 详情面板 | ✅ 30 秒周期刷新，主动上报即时更新 |
| 免欢律 | OPPO 私有 RFCOMM 直连 | ✅ 欢律已 `pm disable-user` |
| 桌面图标 | activity-alias 可隐藏 + 磁贴入口 | ✅ 隐藏/恢复均验证 |

### 已知限制

- **设备中心卡片**：Flyme 设备中心（`DeviceCenterController`）对 provider 加载有自己的门控，
  连魅族自家 `com.meizu.mznfcpay.CustomControlService` 也会 `Timeout waiting onLoad`，
  因此本模块的 `ControlsProviderService` 无法出现在设备中心（代码保留在 `control/`，清单中已停用）。
- **蓝牙音量胶囊**：连着耳机时按音量键弹出的是"蓝牙设备音量"侧边胶囊，
  不经过 `QSVolumeControllerImpl`，所以降噪图标无法画进该形态。
- 磁贴需要在控制中心编辑页手动拖到面板上。


## 为什么需要它

Flyme 的系统耳机组件只识别魅族自家耳机：

- `com.meizu.earphone` 提供连接弹窗、设备页、降噪控制与 `MzBluetoothService` SDK；
- SystemUI 的音量面板（`com.flyme.systemui.volume.QSVolumeControllerImpl`）负责降噪图标与切换；
- 第三方耳机不在其识别表里，只能退回 OPPO 的「欢律」App。

本模块做两件事：

1. **直连耳机**：用 OPPO Enco 私有 RFCOMM 协议读取电量、降噪状态并下发切换，不依赖任何厂商 App；
2. **接入 Flyme**：把上面这份状态喂给音量面板/弹窗/设备卡片，让系统组件以为这是一副"自己的"耳机。

## 架构

```text
┌──────────────────────────────────────────────────────────┐
│ com.android.systemui                                      │
│   音量面板 (QSVolumeControllerImpl)  ← 降噪图标与点击       │
│        ▲                                                  │
│        │ EncoSession（进程内单例）                          │
│        ▼                                                  │
│   EncoLink ── RFCOMM ──► OPPO Enco X2                     │
│        ▲   UUID 0000079a-d102-11e1-9b23-00025b00a5a5      │
│        │                                                  │
│   OppoWireCodec（0xAA 帧 / 电量 0x0106 / ANC 0x010C,0x0404）│
└──────────────────────────────────────────────────────────┘
        ▲                          ▲
        │ 探针/注入                 │ 弹窗与设备卡片
┌───────┴──────────┐      ┌────────┴───────────────────┐
│ com.meizu.earphone│      │ com.meizu.connectivitysettings │
└──────────────────┘      └────────────────────────────┘
```

作用域（`META-INF/xposed/scope.list`）：`com.android.systemui`、
`com.meizu.earphone`、`com.meizu.connectivitysettings`。
调试构建额外列出 `com.heytap.headset`，用于记录官方 App 的 RFCOMM 帧；
不勾选就不生效，发布前会移除。

## 已实现（v0.2）

| 模块 | 内容 | 验证方式 |
|---|---|---|
| `protocol/OppoWireCodec` | OPPO 私有帧编解码：握手、电量、ANC 读写、主动上报、分片/噪声容错 | 7 个单元测试对齐上游参考帧 |
| `protocol/OppoWireConfig` | 型号映射（标准 / Air2 Pro 反向），按设备名选择 | 单元测试 |
| `protocol/AncMapper` | 原始 ANC 值 ↔ Flyme 三态语义，含强度档位与固件特异性编码 | 单元测试 |
| `bt/EncoLink` | RFCOMM 连接、初始查询、读循环、模式回读、状态广播 | **真机已验证**（90/90/100，三态互通） |
| `bt/EncoSnapshot` | Flyme 语义状态（1 关闭 / 2 降噪 / 3 通透 + 三件套电量） | **真机已验证** |
| `popup/PopupLauncher` | 构造 `MzfpDevice` + `MzfpModelInfoBean`，唤起 Flyme 原生弹窗 | **真机已验证** |
| `popup/ArtProvider` | 以 `content://` 向弹窗提供充电盒配图（免联网） | **真机已验证** |
| `hook/EarphonePopupHooks` | 宿主进程内替换左右单耳配图 | **真机已验证** |
| `hook/DeviceListHooks` | 注入设备仓库、置连接态、行内电量/降噪、入口跳转 | **真机已验证** |
| `hook/SdkStateHooks` | 伪装 `isConnected`/状态模型/电量/ANC 写入，原生详情页与设备中心联动 | **真机已验证** |
| `hook/ScanHooks` | 扫描页注入设备；「连接」直达原生详情页 | **真机已验证** |
| `hook/ModuleState` | 宿主进程内缓存 SystemUI 广播的耳机状态 | **真机已验证** |
| `XposedInit` | libxposed 102 入口 + 目标包探针 | 真机已验证 |

## 构建

环境：JDK 17、Android SDK `platforms;android-37.0`、Gradle 9.6.0、AGP 9.4.1。

```powershell
$env:JAVA_HOME = 'D:\software\jdk17'
.\gradlew.bat testDebugUnitTest assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

`compileSdk` 必须是 37：`io.github.libxposed:service:102.0.0` 的 AAR 元数据要求 API 37+。

## 真机验证步骤

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
# LSPosed 里启用模块，勾选三个作用域，重启手机（或重启 SystemUI）
adb logcat -s FlymeEars
```

- 打开「FlymeEars」App → 连接 Enco X2 → 看是否出现"协议就绪"与电量；
- 按音量键 → 观察音量面板是否出现降噪图标（下一阶段才会接管）；
- 探针日志会打印 `QSVolumeControllerImpl` 的真实方法签名，作为下一步 Hook 的依据。

## 路线图

1. **v0.2** 真机确认协议：验证 X2 的 ANC 值是否标准映射，修正 `OppoWireConfig`。
2. **v0.3** 音量面板接管：复刻 flyme-mod 的图标绘制，数据源换成 `EncoLink`。
3. **v0.4** 连接弹窗：逆向 `com.meizu.earphone`，注入 OPPO 设备条目。
4. **v0.5** 电量与设备卡片：`com.meizu.connectivitysettings` 侧展示电量。
5. **v1.0** 去欢律：移除 `com.heytap.headset` 依赖，纯直连。

## 参考项目

- [rikumi/flyme-mod](https://github.com/rikumi/flyme-mod) —— Flyme 注入点、魅族耳机 Binder 协议、音量面板图标（GPL-3.0）
- [silverpoetry/HyperEars](https://github.com/silverpoetry/HyperEars) —— OPPO Enco 私有协议实现、Adapter 分层（GPL-3.0）
- [Andrea-lyz/MelodyCodecTweaker](https://github.com/Andrea-lyz/MelodyCodecTweaker) —— 宿主注入与诊断/反馈包设计

## 许可

本项目的协议实现移植自 HyperEars（GPL-3.0），因此整体以 **GPL-3.0** 发布。
