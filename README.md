# FlymeEars

把**第三方蓝牙耳机**接进魅族耳机 App 的 LSPosed 模块。

装上之后，OPPO Enco X2 这类耳机在 Flyme 里会像魅族自家耳机一样工作：
连接时弹出原生弹窗、出现在设备列表、显示电量、可以切降噪，
不需要常驻 OPPO 的「欢律」。

## 功能

- **连接弹窗** —— 原生弹窗显示耳机名称、配图与左/右耳、充电盒电量
- **设备列表** —— 第三方耳机正常显示，行内带电量与降噪状态
- **设备详情** —— 原生页面提供电量、降噪三态与四档降噪方式（智能/深度/均衡/轻度），以及高级功能
- **设备双连** —— 原生「设备双连」页开关可下发 0x0403，页面列出耳机记录的两台设备；状态跟随耳机重启/回连保留
- **高品质音频** —— 原生页在 AAC / LHDC 之外注入第三行 LDAC，勾选态由 0x0114 回读
- **自定义 EQ** —— 原生「自定义 EQ」页的 10 段滑杆映射到耳机 6 段 × 双声道补偿曲线（0x0411 + 0x040E）
- **设备中心** —— 耳机卡片显示图标与当前状态（经魅族耳机 App 识别后中转，非模块直接注册）
- **添加设备** —— 原生扫描页能发现第三方耳机，点「连接」走原生连接流程（连接成功页 → 详情）
- **原生入口** —— 声效调节、佩戴检测、重命名、高品质音频、设备双连、自定义 EQ 均可使用

## 安装

1. 安装 APK（从 Releases 下载，或按下方「构建」自行编译）
2. 在 LSPosed 中启用 FlymeEars
3. 勾选作用域：`com.android.systemui`、`com.meizu.earphone`、`com.meizu.connectivitysettings`
4. 重启手机

模块直接通过耳机私有 RFCOMM 协议通信，不依赖任何厂商 App。

## 兼容性

- 已实机验证：魅族 21 Pro（Flyme 12.6 / Android 16）+ **OPPO Enco X2**（固件 154.154.120）
- 其他 OPPO Enco 型号按型号映射处理，尚未逐一验证

## OPPO Enco X2 已适配能力

| 能力 | 原生入口 | 协议 | 状态 |
|---|---|---|---|
| 连接弹窗（名称/配图/电量） | 连接耳机时自动弹出 | 0x0106 + 私有握手 | ✅ |
| 设备列表 + 行内电量/降噪 | 耳机 App 首页 → 我的设备 | 状态广播 | ✅ |
| 详情页电量（左/右/盒 + 充电） | 设备详情 → 电量卡片 | 0x8106 | ✅ |
| 降噪三态（关闭/降噪/通透） | 详情页 → 噪声控制 | 0x0404 / 0x810C | ✅ |
| 降噪四档（智能/深度/均衡/轻度） | 详情页 → 降噪方式 | 0x0404 档位值 | ✅ |
| 音效预设 EQ | 详情页 → 声效调节 | 0x0406 / 0x010F | ✅ |
| 自定义 EQ（10 段曲线） | 声效调节 → 自定义 | 0x0411 + 0x040E + 0x0116/0x0415 + 0x0403 0B | ✅（听感建议自测） |
| 高品质音频 AAC / LHDC / LDAC | 详情页 → 高品质音频 | 0x041A / 0x0114 | ✅ |
| Hi-Res（LHDC 5.0 开关） | 高品质音频 → LHDC 行 | setLhdcAudioMode 17/18 | ✅ |
| 低延迟（游戏模式） | 详情页 → 低延迟模式 | 0x0403 06 | ✅ |
| 设备双连 | 详情页 → 设备双连 | 0x0403 11 + 0x0112 | ✅ |
| 佩戴/在盒检测 | 状态驱动（提示/弹窗） | 0x0109 / 0x0204 | ✅ |
| 重命名 | 详情页 → 重命名 | App 侧持久化 | ✅ |
| 设备中心卡片（电量/降噪/图标） | 控制中心耳机卡片 | Binder 回调 + 状态模型 | ✅ |
| 协议级：查找耳机 | 暂无 UI 入口 | 0x0400 | 已验证，未接 UI |

已知限制：

- 自定义 EQ 走的是耳机的 6 段听力补偿曲线，10 段滑杆按频段折叠，曲线为近似映射；
- 充电状态在盒内不置 bit7，模块按「在盒=充电」推断，进盒瞬间可能有 2–3 秒延迟（RFCOMM 重连）；
- 降噪切换需要佩戴耳机，未佩戴时原生页面会提示并回滚。

## 工作原理

模块注入魅族耳机 App 与 SystemUI，把耳机的真实状态包装成原生耳机状态；
耳机侧由模块通过 OPPO 私有 RFCOMM 协议直连，读取电量、降噪、佩戴、编码与双连设备表。

状态回传走魅族 SDK 自己的回调链，而不是去调用页面方法：

```text
耳机帧 -> EncoLink 解析 -> EncoSession 广播 -> ModuleState
        -> MzBluetoothWrapper 的 SDK 回调对象（写状态模型 + 通知 LiveData）
        -> BluetoothConnectionCallbackHandler（连接 / 降噪 / 佩戴事件）
        -> 原生页面按自己的逻辑刷新
```

所以页面刷新、断开弹窗、高品质页勾选等行为都与真机一致；模块不重绘任何原生界面。

## 构建

需要 JDK 17 与 Android SDK 37（`platforms;android-37.0`）。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

产物位于 `app\build\outputs\apk\debug\app-debug.apk`。

## 致谢

- [HyperEars](https://github.com/silverpoetry/HyperEars)（GPL-3.0-only）—— OPPO Enco 私有协议实现与耳机渲染素材；本项目的协议层移植自它
- [flyme-mod](https://github.com/rikumi/flyme-mod)（MIT）—— 魅族耳机 SDK 的 Binder 通道用法参考
- [MelodyCodecTweaker](https://github.com/Andrea-lyz/MelodyCodecTweaker)（Apache-2.0）—— 宿主注入与诊断结构参考
- [LSPosed](https://github.com/LSPosed/LSPosed) / libxposed（Apache-2.0）—— 模块框架

完整出处与许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可

Copyright (C) 2026 cnnceart

本项目以 **GPL-3.0-only** 发布，完整条款见 [LICENSE](LICENSE)。

协议层移植自 HyperEars（GPL-3.0-only），因此本项目作为衍生作品同样只能按 GPL-3.0-only 分发，而非「GPL-3.0 或更高版本」。
