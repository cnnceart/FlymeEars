# FlymeEars

把**第三方蓝牙耳机**接进魅族耳机 App 的 LSPosed 模块。

装上之后，OPPO Enco X2 这类耳机在 Flyme 里会像魅族自家耳机一样工作：
连接时弹出原生弹窗、出现在设备列表、显示电量、可以切降噪，
不需要常驻 OPPO 的「欢律」。

## 功能

- **连接弹窗** —— 原生弹窗显示耳机名称、配图与左/右耳、充电盒电量
- **设备列表** —— 第三方耳机正常显示，行内带电量与降噪状态
- **设备详情** —— 原生页面提供电量、降噪三态与四档降噪方式（智能/深度/均衡/轻度），以及高级功能
- **设备中心** —— 耳机卡片显示图标与当前状态（经魅族耳机 App 识别后中转，非模块直接注册）
- **添加设备** —— 原生扫描页能发现第三方耳机，点「连接」走原生连接流程（连接成功页 → 详情）
- **原生入口** —— 声效调节、佩戴检测、重命名、高品质音频均可使用

## 安装

1. 安装 APK（从 Releases 下载，或按下方「构建」自行编译）
2. 在 LSPosed 中启用 FlymeEars
3. 勾选作用域：`com.android.systemui`、`com.meizu.earphone`、`com.meizu.connectivitysettings`
4. 重启手机

模块直接通过耳机私有 RFCOMM 协议通信，不依赖任何厂商 App。

## 兼容性

- 已实机验证：魅族 21 Pro（Flyme 12.6 / Android 16）+ OPPO Enco X2
- 其他 OPPO Enco 型号按型号映射处理，尚未逐一验证

## 工作原理

模块注入魅族耳机 App 与 SystemUI，把耳机的真实状态包装成原生耳机状态；
耳机侧由模块通过 OPPO 私有 RFCOMM 协议直连，读取电量、降噪、佩戴与编码信息。

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
