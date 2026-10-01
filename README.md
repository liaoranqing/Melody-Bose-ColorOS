# Bose QC Earbuds Ultra 2 ColorOS 音量面板适配

这是一个 LSPosed/Xposed 模块，只注入 `com.oplus.melody`，不注入 SystemUI 或
`com.heytap.mydevices`。它复用 ColorOS Melody 的 Provider 合约，把 Bose QC Earbuds
Ultra 2 映射为系统音量面板中的耳机设备。

## 当前目标设备

- 型号：Bose QuietComfort Ultra Earbuds (2nd Gen)
- BMAP codename：`edith`
- Product ID：`0x4062`
- RFCOMM：BMAP channel 2 / UUID `00000000-deca-fade-deca-deafdecacaff`
- 当前配置地址：`68:F2:1F:3D:41:D7`

## 面板模式

面板只支持三项：

1. 关闭：Bose Quiet 模式配合 ANC 关闭（通过 [31.10] AudioSettings）
2. 降噪：Bose Quiet（模式索引 0，最大降噪）
3. 通透：Bose Aware（模式索引 1）

ColorOS 内部模式顺序仅暴露 `[1, 5, 2]`，分别对应关闭、降噪、通透；不暴露自适应、沉浸式音频或 Cinema。

## 已实现的控制层

- BMAP 4 字节帧编解码、半包/粘包解析、响应地址匹配
- RFCOMM 短连接，避免 Bose Music 与 Melody 长时间争用链路
- 当前模式 GET 与 Quiet/Aware 模式 START 切换
- 左耳、右耳、充电盒、双耳合计电量解析
- CNC/AudioSettings 读取保持并写回的接口，用于调节降噪挡位
- Melody Provider active/noise/battery 查询、点击和刷新通知
- ACL 连接状态监听、短连接失败日志和模式回读

## 构建与安装

项目原仓库没有 Gradle Wrapper，需在有 Android SDK、Gradle 和 Android Gradle Plugin
的环境中执行：

```bash
gradle :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

安装模块后，在 LSPosed 中只勾选：

```text
com.oplus.melody
```

然后强制停止 Melody：

```bash
adb shell am force-stop com.oplus.melody
```

调试日志：

```bash
adb logcat -s MelodyEarphone:V
```

## 重要限制

1. 实机 BMAP 连接是否成功必须由日志确认；APK 由 GitHub Actions
   （`.github/workflows/build.yml`）自动构建，本地无 Gradle 环境。
2. Bose BMAP 已公开的 QC Ultra 2 Earbuds 配置没有可靠的实时“左耳/右耳佩戴状态”
   数据接口。模块不会伪造实时佩戴状态；自动暂停开关可控制，但不等于实时传感器读数。
3. 部分 Bose 设置的 SET 操作要求云端 ECDH 认证，本模块只使用已分析的 GET、
   SETGET 和 AudioModes START；固件拒绝的写入会记录错误，不影响其他控制。
4. MAC 地址来自用户截图并写入源码。如果更换耳机或系统对蓝牙地址做随机化，需要
   修改 `BoseDeviceConfig.java` 后重新构建。
