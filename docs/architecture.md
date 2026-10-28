# 架构与取舍（先读这一页）

## 结论：要 Hook 的是「魅族耳机 App」，不是蓝牙栈

| 组件 | 角色 | 是否注入 |
|---|---|---|
| `com.android.systemui` | 唯一可靠的「耳机已连接」信号源（A2DP 音频设备回调）；RFCOMM 链路的单一持有者；原生弹窗的调用方 | ✅ |
| `com.meizu.earphone` | 魅族耳机的「设备空间」：扫描页、设备列表、设备详情、配置页 | ✅ **核心** |
| `com.meizu.connectivitysettings` | 蓝牙设置里的设备卡片（电量可显示在系统设置） | ⬜ 可选 |
| `com.android.bluetooth` | AOSP 蓝牙栈。魅族耳机体验**不经过**这里，改它风险高收益低 | ❌ 不碰 |

判断依据：魅族耳机的列表/详情/弹窗全部由 `com.meizu.earphone` 通过自家 SDK 提供，
系统蓝牙设置只是另一个展示面。所以「把第三方耳机接进魅族耳机 App」= 注入 `com.meizu.earphone`。

## 已验证的链路

### 1. 连接 → 原生弹窗

```
蓝牙连接
  └─ SystemUI: AudioDeviceCallback(A2DP/Enco)
       └─ EncoSession.reconcile()  按名称匹配 OPPO/Enco
            └─ EncoLink  RFCOMM（先 SDP UUID，失败回退 scn 15）
                 └─ 读电量 0x8106 / 读模式 0x810C
                      └─ PopupLauncher 拉起 MzfpDailogActivity（原生弹窗）
```

### 2. 启动 App → 设备列表

```
com.meizu.earphone/.MainActivity
  ├─ 原生逻辑：读 devices_store.json（缓存）→ 空 → 跳 ScanEntranceActivity（添加设备）
  └─ 我们的 Hook：
       DeviceListHooks 用 BluetoothAdapter.getBondedDevices() 找 OPPO/Enco
       构造 AudioDevice（构造器 11 参）
       hook h5.b.c()      → 把设备塞进同步 Set（数据源）
       hook e6.a.f()      → 列表刷新后把状态置为「已连接」(l=2)
       hook MainActivity.onCreate → 同进程 startActivity(DeviceListActivity)
```

实测结果：列表显示 `OPPO Enco X2 / 已连接`，点击进入原生 `CfgActivity`。

### 3. 详情页

```
CfgActivity.onCreate
  └─ 注入面板（电量 / 当前模式 / 关闭·降噪·通透）
       状态 ← SystemUI 广播 ACTION_STATE（左/右/盒/模式）
       按钮 → 广播 ACTION_SET_MODE → SystemUI 会话下发 0x0404
```

## 踩过的弯路（新会话不要重走）

1. **设备中心（Android Device Controls）**：Flyme 对 provider 加载有门控，连魅族自家
   `com.meizu.mznfcpay.CustomControlService` 也 `Timeout waiting onLoad`。放弃。
2. **音量面板蓝牙胶囊**：连耳机时按音量键弹出的是独立「蓝牙设备音量」胶囊，
   不走 `QSVolumeControllerImpl`，降噪图标画不进去。放弃。
3. **jadx 字段名是别名**：`f4497a` 之类是 jadx 重命名，运行时真实字段是单字母 `a/b/c/l`。
   Hook 里要用 `findField(type, "a", "f4497a")` 这种双名回退。
4. **LSPosed 远程偏好跨进程不可靠**：SystemUI 写入后耳机进程读到 `null`。
   改用宿主进程自带的蓝牙权限（`getBondedDevices()`）直接取地址。
5. **MainActivity 的入口判断基于缓存 JSON**，不是设备仓库；只注入 `h5.b.c()` 不会让入口跳转，
   必须额外 hook `MainActivity.onCreate` 做同进程重定向。
6. **包名一改，LSPosed 授权就失效**：必须重新在管理器里启用。
   最终包名 `io.github.cnnceart.flymeears`（LSPosed 仓库要求 `io.github.<user>` 前缀）。
7. **ContentProvider authority 要跟随 applicationId**，否则覆盖安装会 `CONFLICTING_PROVIDER`。
8. **adb 没有 root**：LSPosed 管理器只能从通知栏进；`/data/adb` 不可读。
9. **PowerShell 传 Gradle 属性要整体加引号**：`'-PappId=dev.flymeears'`，否则被拆成两个参数。
10. **Android 16 上 `am crash com.android.systemui` 不一定重启**，改模块后最可靠是 `adb reboot`。

## 下一步（按优先级）

1. 扫描页注入：首次连接时在「添加设备」页就能看到第三方设备
   （注入点：`MzBluetoothWrapper.foundDevices` / 扫描结果适配器）
2. 详情页面板真机验证（已写，未截图）
3. 列表行内显示电量与降噪模式
4. 能力页：降噪设置、音频、音效、低延迟
