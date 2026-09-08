# 33 - 驾驶台 HUD 数据面（HUD-1）：mirror 显示字段（290/0/2 保持全绿）

> 用户方向（2026-09-08 拍板）：重构 MTR 原版驾驶仪表（DrivingGuiRenderer）为
> "科技玻璃驾驶台"（深色半透明玻璃 + 霓虹高对比 + 信号色状态点亮，带动画），范围=驾驶台全套，
> 实机截图迭代验收。本片 = 引擎→客户端镜像的数据面（渲染框架 HUD-2 另片）。

## 现状（勘察结论）

- 仪表 = game/fabric `org.mtr.mod.render.DrivingGuiRenderer`（356 行）：右下半圆速度表
  （300 km/h 刻度/ATS 圈/文字），纯静态代码自绘，无动画/组件；InitClient 注册 GUI rendering 事件，
  数据来自 client 镜像 Vehicle（VehicleExtension，core Vehicle 的 client 形态）。
- 镜像机制：engine 的 VehicleSchema（schema 源 `buildSrc/.../schema/data/vehicle.json` →
  generateSchemaClasses 生成 `src/.../generated/data/VehicleSchema.java`（gitignore 生成物））携带
  mmtr* 字段（mmtrProtection 等），server 写 → JSON → client Vehicle.updateData 读。

## 改动（引擎侧，数据面）

- vehicle.json 新增 4 个 mirror 字段（生成链）：mmtrAwsWarningPending / mmtrAwsWarningAcknowledged
  （S3 警示态）、mmtrBlockHeld（S1 占用等待）、mmtrSpeedLimitKmh（S2 当前行向轨限速，long km/h）。
- Vehicle.updateMmtrSyncFields() 尾映射内部状态 → 镜像字段（每 motion tick）。
- core Vehicle 公开读：isMmtrAwsWarningPendingFromSync() / isMmtrAwsWarningAcknowledgedFromSync() /
  isMmtrBlockHeldFromSync() / getMmtrSpeedLimitKmhFromSync()（客户端 HUD 消费）。
- 测试：MmtrAwsWarningTests 两用例补 mirror 一致性断言（pending/acked/blockHeld/limit=40）。
- 教训：`src/main/java/org/mtr/core/generated/**` 是生成物（gitignore）——schema 变更必须改
  vehicle.json 再跑 generateSchemaClasses，手改生成文件无效且会被覆盖。

## 状态与下一步

- 全量 290/0/2 保持（零新增失败）。
- HUD-2（渲染框架，game/fabric）：tween/缓动 + 矢量组件（弧形表盘/圆角玻璃/辉光）+ 面板组件
  （速度/限速+制式 chip/占用态/AWS 警示灯/SPAD/车门/任务行），替换 DrivingGuiRenderer 挂载
  （InitClient 事件 + 原 setVehicle 输入门），实机截图迭代（需 dev client 窗口验证）。
- 驾驶输入侧：VehicleRidingMovement 加 AWS ack 键（ControlState.acknowledge 已贯通）。
