# FlymeEnco

让 **OPPO Enco X2** 接入 **魅族 21 Pro（Flyme 12.6 / Android 16）** 的系统级耳机体验：
连接弹窗、音量面板降噪切换、电量显示，并逐步摆脱对「欢律」(`com.heytap.headset`) 的依赖。

> 状态：**v0.2 弹窗已通**。连接弹窗可以原生呈现 OPPO Enco X2 的名称、配图与电量；
> 降噪直连、音量面板接管与自动触发仍在进行中。

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
adb logcat -s FlymeEnco
```

- 打开「FlymeEnco」App → 连接 Enco X2 → 看是否出现"协议就绪"与电量；
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
