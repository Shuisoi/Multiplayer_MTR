# 37 - LZB 驾驶台数据面（HUD-2 前序）：制式/顶棚/目标速度/距离镜像

> 用户方向（2026-09-08 续）："现在开始做 LZB 信号相关"；驾驶台（科技玻璃风格）LZB
> 显示一起做。本片 = 引擎→客户端镜像数据补齐（HUD-2 渲染另片，game/fabric）。

## 现状

- S4 已在引擎侧实现 LZB 连续监督（手动驾驶，≥101 km/h 轨段：顶棚=min(当前轨限速, 编组
  最高速) 强制；前方慢轨/占用停车目标按服务制动包络预告，超速自动衰减；
  `getMmtrLzbCeilingKmh/TargetKmh/TargetDistanceM`）。
- HUD-1（notes 33）已镜像 AWS 警示/确认、占用等待、当前限速等字段；**LZB 三个显示量
  未镜像** → 客户端驾驶台拿不到。

## 改动（引擎侧，数据面）

- `schema/data/vehicle.json` 增 4 字段（generateSchemaClasses 链自动再生成）：
  mmtrLzbSupervising(bool)/mmtrLzbCeilingKmh(int)/mmtrLzbTargetKmh(int)/
  mmtrLzbTargetDistanceM(double, -1=无界目标)。
- `Vehicle.updateMmtrSyncFields()` 尾映射（与监督执行同源，驾驶台显示=列车实际遵守值）：
  supervising = ceiling>0；其余直读同名单值 getter。
- 公开 *FromSync 读（客户端 HUD 消费）。

## 状态

- engine 全量测试绿（290/0/2 保持）。
- HUD-2（game/fabric，科技玻璃驾驶台：速度表/限速+制式 chip/LZB 目标速度+距离条/AWS
  灯/占用态/车门/任务行，替换 DrivingGuiRenderer）下一片做，实机截图迭代。
