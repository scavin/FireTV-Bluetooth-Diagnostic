# Fire TV Bluetooth Pairing Diagnostic

一个使用**公开 Android API** 的经典蓝牙扫描与配对诊断 App，针对 Amazon Fire TV Stick 4K Max 第 2 代（`AFTKRT`，Fire OS 8 / Android 11 / API 30）设计。

**用途**：排查 Fire TV 无法与 iPhone 蓝牙配对、导致 DiPlay 的「选择 iPhone」列表为空的问题。用户可使用电视遥控器操作。未执行过真机测试；配对是否成功取决于 Fire OS 与 iPhone。

## 功能

- 调用 `BluetoothAdapter.startDiscovery()` 启动标准 Android 经典蓝牙设备搜索。显示 `ACTION_DISCOVERY_STARTED`、`ACTION_FOUND`、`ACTION_DISCOVERY_FINISHED`。
- 不按设备类别过滤，显示名称、地址、`DEVICE_TYPE_CLASSIC/DUAL/LE`、RSSI、配对状态。
- 将 `getBondedDevices()` 中的设备与扫描结果合并显示。
- 选中设备后先停止扫描，再通过公开 `BluetoothDevice.createBond()` 请求系统配对。**返回 `true` 仅代表配对请求被接受**。
- 监听 `ACTION_PAIRING_REQUEST` 与 `ACTION_BOND_STATE_CHANGED`，显示 `BOND_NONE`、`BOND_BONDING`、`BOND_BONDED`，如系统提供原因码则保存原始码。
- 已知 MAC 地址时，允许手动加入候选列表；这**不会绕过**系统限制，也不能用 iPhone 的随机 BLE MAC 冒充经典蓝牙地址。
- 将所有本应用事件写入 `logcat`（tag：`FireTVBtDiag`）和 app 私有文件。无网络权限、不上传日志。
- 无需 Root，不使用隐藏蓝牙 API，不修改系统蓝牙配对记录，也不会自动确认 PIN。

本工具**只实现经典蓝牙 inquiry**。Android 部分设备的发现流程也可能返回 BLE 设备，但工具不单独发起 BLE 扫描。DiPlay 无线 CarPlay 后续使用的是经典蓝牙 RFCOMM/iAP2，BLE 扫描成功不能证明它能连通。

## 从源码构建

### 方法 A：Android Studio

1. 安装最新版 Android Studio，导入本工程根目录。
2. 使用 **JDK 17**、**Android SDK Platform 35**，等待 Gradle 同步；工程使用 Android Gradle Plugin **8.6.1**，兼容 Gradle **8.7**。
3. 执行 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
4. APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

该工程不依赖 AndroidX、Compose 或任何第三方运行库，只有 Android 平台 API。

### 方法 B：GitHub Actions（无需本机 Android SDK）

1. 创建一个 GitHub 空仓库，把本工程推送到其 `main` 分支（注意 `.github/workflows/build-apk.yml` 必须一并上传）。
2. 打开仓库 **Actions > Build Android APK > Run workflow**，或等待推送触发。
3. 构建成功后，从该任务底部的 **Artifacts** 下载 `firetv-bluetooth-diagnostic-apk`，解压得到 `app-debug.apk`。

工作流使用 Temurin JDK 17、Android SDK 35、Gradle 8.7，自动执行 `:app:assembleDebug`。上传至 GitHub 前请检查代码；无需添加任何密钥。

### 方法 C：macOS 终端（已有 Android SDK 与 Gradle 8.7）

```bash
./build-macos.sh
# or:
gradle --no-daemon :app:assembleDebug
```

> 注意：此 ZIP **没有预编译 APK**，也没有 Gradle wrapper JAR。若本机没有兼容 Gradle，请使用 Android Studio 或上面的 GitHub Actions。

## 安装到你的 Fire TV

```bash
adb connect 172.16.0.218:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n net.appinn.firetvbtdiag/.MainActivity
```

如果通过 GitHub Actions 下载了 APK，把路径换成实际下载文件路径。App 包名：`net.appinn.firetvbtdiag`。

## 测试流程

1. 在 iPhone 打开「设置 > 蓝牙」，保持页面前台、手机解锁。
2. 启动 Fire TV 上的 **BT Pair Diagnostic**。应用会列出已配对设备，并展示蓝牙状态。
3. 点击 **开始扫描**。Fire OS 可能弹出位置权限请求，选择允许。标准经典蓝牙扫描通常需要约 12 秒。
4. 如果发现 iPhone，使用遥控器选中，点击 **配对所选**。在电视及 iPhone 上留意蓝牙配对弹窗。
5. 成功后应看到 `BOND_NONE -> BOND_BONDING -> BOND_BONDED`（实际可能省略部分事件）。点击「刷新配对」，返回 DiPlay 检查「选择 iPhone」。
6. 如果 `startDiscovery() = true` 但收不到 `ACTION_FOUND`，说明系统接纳扫描请求但未向应用提供可见设备；**不能单凭此断言 Fire OS 过滤了 iPhone**，还可能是定位权限、系统定位开关、射频环境或设备可发现状态。
7. 如果 `createBond() = false`，表示系统立即拒绝请求。如果从 `BOND_BONDING` 回到 `BOND_NONE`，表示配对流程未成功完成；查看设备原生日志寻找更具体的原因。

### Android 11 位置权限 / 定位开关

Android 11 的经典蓝牙扫描通常需要**精确位置权限**且系统定位开启。Fire OS 可能不提供普通 Android 定位设置界面。

若没有弹出授权对话框，可从 Mac 执行：

```bash
adb shell pm grant net.appinn.firetvbtdiag android.permission.ACCESS_FINE_LOCATION
adb shell dumpsys package net.appinn.firetvbtdiag | grep -A 10 'runtime permissions:'
adb shell settings get secure location_mode
```

`pm grant` 仅授予应用权限，**不会打开系统定位开关**，也无法绕过 Fire OS 对蓝牙扫描/配对的底层限制。

## 导出诊断日志

查看实时日志：

```bash
adb logcat -s FireTVBtDiag:I '*:S'
```

提取完整应用日志：

```bash
adb shell run-as net.appinn.firetvbtdiag cat files/bluetooth-diagnostic.txt > firetv-bt-diagnostic.txt
```

`run-as` 适用于此工程生成的 **debug APK**；如果将来使用不可调试的 release APK，需更换日志导出方法。

提取系统配对状态：

```bash
adb shell dumpsys bluetooth_manager | grep -A 20 'Bonded devices:'
adb shell dumpsys bluetooth_manager | grep -A 20 'Bond Events:'
```

辅助记录系统日志（需在 Mac 终端上运行，然后重试配对，按 Ctrl+C）：

```bash
adb logcat -b all -v threadtime > firetv-system-bluetooth.log
```

**隐私**：日志包含附近设备名称、蓝牙 MAC 地址、时间戳和系统信息；公开之前请删掉敏感内容。应用不会联网。

## 已知局限与故障判断

| 结果 | 能说明什么 | 不能说明什么 |
| --- | --- | --- |
| 只见 `startDiscovery()=true` | 系统接受启动请求 | 不能证明 Fire TV 实际能发现 iPhone |
| 接收到 `ACTION_FOUND` | Android 向 app 提供设备记录 | 不能证明经典蓝牙配对会成功 |
| `createBond()=false` | 配对请求被系统立即拒绝 | 不能单凭此定位原因 |
| 发生 `BOND_BONDING -> BOND_NONE` | 配对过程结束但未成功 | 不能仅凭状态知道是否为 Fire OS Profile 限制 |
| 收到 `BOND_BONDED` | 系统蓝牙配对成功 | 不能证明 DiPlay 的 iAP2 RFCOMM 能连接 |

Fire TV 的 Amazon 蓝牙管理服务、系统权限、iPhone 可发现方式、蓝牙 Profile 兼容性都可能影响测试；该工具不修改底层策略。

## 参考

- [Android BluetoothAdapter.startDiscovery](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#startDiscovery())
- [Android BluetoothDevice.createBond](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createBond())
- [DiPlay 源码](https://github.com/shihabal3amri/DiPlay)
- [Amazon Fire TV 应用兼容性](https://developer.amazon.com/docs/app-submission/device-filtering-and-compatibility.html)

代码：最小的本地诊断实现；不包含从 DiPlay 复制的程序代码或凭据。
