# 参与开发

[English](CONTRIBUTING.md) · **简体中文**

本文面向要构建或修改 Pen Battery 的人。面向使用者的说明在 [README.zh-CN.md](README.zh-CN.md)。

## 目录

- [环境](#环境)
- [构建](#构建)
- [release 签名](#release-签名)
- [源码地图](#源码地图)
- [翻译](#翻译)
- [界面文案](#界面文案)
- [其它约定](#其它约定)

## 环境

| | |
|---|---|
| Android | 设备需 Android 13（API 33）及以上；`minSdk` 与 `targetSdk` 均为 33。 |
| JDK | 17 及以上（`compileOptions` 面向 17，AGP 9.4.1 要求如此）。 |
| Android SDK | 任意较新的 platform 即可；工程设定 `compileSdk 37`。 |
| Gradle | 不需要安装，`gradle-app/` 下的 wrapper 会拉取 9.6.0。 |

Gradle 工程根目录是 **`gradle-app/`**，不是仓库根目录。Android Studio 直接打开该文件夹即可。

## 构建

```bash
cd gradle-app
./gradlew :app:assembleDebug          # Windows 使用 gradlew.bat :app:assembleDebug
./gradlew :app:assembleRelease        # 没有 keystore 时产物不签名，见下节
```

工程里有两处镜像设定，都可以安全改掉：

- `gradle/wrapper/gradle-wrapper.properties` 从公开的腾讯 CDN 镜像下载 Gradle，
  官方等价地址是 `https://services.gradle.org/distributions/gradle-9.6.0-bin.zip`。
- `settings.gradle` 在 `google()` / `mavenCentral()` 之外还列了 `dl.google.com/dl/android/maven2`
  与两个阿里云镜像。只要你的网络能访问官方端点，删掉它们不影响依赖解析；它们存在只是因为作者的网络访问不到。

`gradle.properties` 里有 `android.overridePathCheck=true`。不加这一行，AGP 会拒绝构建路径中含非 ASCII
字符的工程 —— 作者的检出目录就是这种情况；留着对别人也无害。

Windows 上**不要**开启 `org.gradle.vfs.watch`：该工程的实测经验是文件监听不可靠，会出现资源已改但 `R`
不重新生成。

## release 签名

签名材料一律不进仓库。因此没有配置时 release 任务依然成功，但产出
`app/build/outputs/apk/release/app-release-unsigned.apk`，Android 拒绝安装 —— 这是预期结果，不是构建坏了。
要签名：

```bash
cp keystore.properties.example keystore.properties   # 然后填写
```

`store.file` 指向**工程目录之外**的 keystore；相对路径按工程根解析，绝对路径原样使用；文件不存在时构建
会明确报错，而不是静默产出一个"未签名的 release"。配置正确时产物是 `app-release.apk`。

把私钥留在工程之外是有原因的：工程目录会被打包、同步、分享，签名权不应该跟着一起走。

## 源码地图

`gradle-app/app/src/main/java/com/playbuddy/penbattery/` —— 12 个文件，约 2 200 行。

| 文件 | 职责 |
|---|---|
| `MainActivity` | 权限 / 蓝牙 / 目标设备的闸门判定，底部导航，语言对话框 |
| `PenTarget` | 运行时解析目标设备（记住的 → 关键字匹配 → 询问用户），并判断它是否属于已验证型号 |
| `PenGatt` | GATT 客户端：`0x180F` → `0x2A19`，订阅、60 秒兜底读取、断线后退避重连 |
| `State` | 每轮刷新一份不可变快照；`shouldMask()` 是判断"什么算不适配"的唯一位置 |
| `History`、`Samples` | 按天持久化，以及据此推导出的趋势点与本轮周期端点 |
| `BatteryFragment`、`HistoryFragment`、`AboutFragment` | 三个页面 |
| `Disabled` | "不适配"遮罩面板（半透明填充 + 斜纹 + 短标签） |
| `Links` | 词级可点击 span，命中判定跟随被标记词语所在行的实际边界 |
| `ExportHelper` | 经系统文件选择器导出 CSV |

## 翻译

`app/src/main/res/values/strings.xml` 是**英文默认**（AOSP 惯例：无限定符的资源是兜底），
`values-zh/strings.xml` 是中文镜像。

- 两边键集必须完全一致。少一个键，那一行会在中文界面里静默掉回英文。
- 只进 logcat 的文字故意保留中文：那是排障输出，不该跟着界面语言变。
- 任何可见文字都必须是资源，不允许在 Java 里写死。状态文字按资源 id 比较（`State.statusRes`），
  不比较字符串内容 —— 一旦按译文匹配，换语言当场失效。
- 星期名与时长单位使用按语言的 `string-array`，而不是设备的 `java.time` 格式化：
  部分厂商 ROM 的语言数据不可靠。

## 界面文案

先说现状，再给下一步。避免 "error"、"failed"、"invalid"：不是已验证型号的设备处于正常且可自救的状态，
界面就该读成那样。遮罩标签保持短（中文约六个字，英文两三个词），因为它压在面板上。

凡是可点的，必须看起来可点：带下划线的主色**词**，而不是一整块恰好挂了监听器的面板。

## 其它约定

- 本工程的注释以中文为主，写的是**这行为什么存在** —— 平台怪癖、绕过某个坑、会让后来者意外的不变量。
  不要为了整洁删除它们；只在理由无法从代码本身读出时才加。
- 新增权限必须有具体理由，"方便"不算。这个应用最好无聊一点。
- 优先使用 AndroidX / Material / 平台 API。新增第三方依赖要先查许可证：当前依赖图清一色 Apache-2.0，
  引入 GPL/LGPL 代码会改变本项目对自己许可证的可选项。
- 只在前台采样。若某个改动需要后台采集，先讨论 —— 那个能力是被**主动删除**的，不是没做完。
