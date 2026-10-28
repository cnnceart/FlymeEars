# 第三方声明与出处

FlymeEars 以 **GPL-3.0** 发布。以下来源构成本项目的基础，特此致谢。

## 直接参考

| 项目 | 许可 | 本项目用到的部分 |
|---|---|---|
| [silverpoetry/HyperEars](https://github.com/silverpoetry/HyperEars) | GPL-3.0 | OPPO Enco 私有 RFCOMM 协议实现（`OppoWireCodec` / ANC 映射），以及耳机渲染素材（充电盒/左右耳 PNG）。本项目的协议层是它的 Java 移植 |
| [rikumi/flyme-mod](https://github.com/rikumi/flyme-mod) | MIT | 魅族耳机 SDK 的 Binder 通道用法（`MzBluetoothService` / `transact(67)` / `IMzServiceCallBack`），作为扫描页注入方向的参考 |
| [Andrea-lyz/MelodyCodecTweaker](https://github.com/Andrea-lyz/MelodyCodecTweaker) | Apache-2.0 | 宿主注入结构与诊断日志设计的参考 |
| [LSPosed/LSPosed](https://github.com/LSPosed/LSPosed) 与 libxposed | Apache-2.0 | 模块框架与 API |

## 上游协议研究（经 HyperEars 转引）

HyperEars 的致谢列出了它的协议研究来源，本项目同样受益于这些工作：

- [1812z/OppoPods](https://github.com/1812z/OppoPods) —— OPPO 耳机协议（未声明许可，仅作研究引用）
- [Star-ZER0/Pods-Protocol-Reverse-Engineering](https://github.com/Star-ZER0/Pods-Protocol-Reverse-Engineering) —— CC-BY-SA-4.0
- [DOHEX/HyperRose](https://github.com/DOHEX/HyperRose)、[Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods)
- 以及 HyperEars 仓库内 `THIRD_PARTY_NOTICES.md` 列出的其它来源

## 说明

- 本项目未复制上述项目的源代码（HyperEars 的协议层为 Kotlin，本项目为重新实现的 Java 版本，并针对 OPPO Enco X2 做了实机验证）。
- 耳机渲染素材来自 HyperEars（GPL-3.0），因此在 GPL-3.0 下随本项目一同分发。
- Meizu、Flyme、OPPO、Enco 等商标与产品名称仅用于兼容性描述，归各自权利人所有。
- 本项目的逆向分析对象（魅族耳机 App、系统 UI）版权归魅族科技所有，本项目不包含其任何代码或资源文件。

如果你认为本项目的某处引用不当，请开 issue，我们会立即处理。
