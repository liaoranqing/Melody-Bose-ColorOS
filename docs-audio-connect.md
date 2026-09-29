# Audio Connect AZ100 控制链路

本文件记录从 Audio Connect 4.4.0 APK 中确认的运行链路，作为设备空间内移植
`DirectAirohaController` 的依据。

## 高层调用

```text
DeviceManager.getInstance()
  -> getDeviceMmi(address)
  -> DeviceMmiAiroha4 (extends DeviceMmiAiroha3 -> DeviceMmiAiroha2)
  -> setOutsideCtrl(DeviceMmiConstants, noiseLevel, ambientLevel)
  -> OutsideCtrl.getModeByte(mode)
  -> AirohaMmiMgr.setOutsideCtrl(byte mode, byte noise, byte ambient)
```

`DeviceMmiAiroha2.setOutsideCtrl` 只接受 `UNSET`、`AMBIENT`、
`NOISE_CANSELLING`，然后将三个字节交给 `AirohaMmiMgr`。默认的
`OutsideCtrl` 参数是 `noiseCancelLevel=0`、`ambientLevel=10`。

## 连接

`DeviceMmiAiroha2.setConnection` 创建：

- `AirohaLinker(context, 6)`
- `SppLinkParam(address, UuidTable.AIROHA_SPP_UUID)`
- SPP UUID：`00000000-0000-0000-0099-AABBCCDDEEFF`
- `AirohaLinker.connect(...)` 返回 `GeneralHost`
- Host 初始化后创建 `AirohaMmiMgr(address, host, linkParam)`

`GeneralHost` 使用 `SppController` 的 RFCOMM socket，并将传输层切换到
`H4Transport`。模块在设备空间进程中通过 Audio Connect 的 APK ClassLoader
反射执行同样的连接和 MMI 初始化，避免对 Audio Connect 进程做 Hook。

## RACE 命令

`AirohaMmiMgr.setOutsideCtrl` 入队 `StageSetOutsideCtrl`：

- RACE type：`0x5A`
- RACE ID：`11`（小端 `0B 00`）
- payload：`mode, noiseCancelLevel, ambientLevel`
- MMI 原始帧：`05 5A 03 00 0B 00 <mode> <noise> <ambient>`
- response type：`0x5B`

`StageSetOutsideCtrl` 在响应状态为 `0` 时将包标记为成功。自适应开关由
`AirohaMmiMgr.setAdaptiveAnc(byte)` 排队，AZ100 使用 `0`/`1`。

## 设备空间模式映射

```text
AirPods UI 5  -> RACE mode 1, adaptive 0  (ANC)
AirPods UI 10 -> RACE mode 1, adaptive 1  (adaptive ANC)
AirPods UI 1  -> RACE mode 0, adaptive 0  (off)
AirPods UI 2  -> RACE mode 2, adaptive 0  (transparency)
```

这些值来自 `AbstractC6418a.m22246o`、`OutsideCtrl.getModeByte`、
`StageSetOutsideCtrl` 和 `StageSetAdaptiveAnc` 的实际字节路径，而不是仅依据
类名或注释推测。
