# 38 - HUD-2 科技玻璃驾驶台 v1（LZB/AWS 显示）

> 用户方向（2026-09-08）：重构 MTR 原版驾驶仪表为"科技玻璃驾驶台"（深色半透明玻璃 + 霓虹
> 高对比 + 信号色状态点亮），范围=驾驶台全套；LZB 显示同做。实机截图迭代验收。

## 数据面（notes 37 已完成）

- vehicle schema 镜像：mmtrLzbSupervising/CeilingKmh/TargetKmh/TargetDistanceM + *FromSync；
  MmtrLzbSupervisionTests 增补"镜像=监督执行值"一致性断言（全绿）。

## v1 渲染（game/fabric）

- 新 `MmtrCabHudRenderer`（纯矢量自绘，无贴图；深色玻璃面板+霓虹描边），替换
  DrivingGuiRenderer 挂载（InitClient registerGuiRendering / VehicleExtension.setVehicle）：
  - 右侧速度模块：240° 玻璃表盘（刻度/数字）+ 限速/顶棚弧（琥珀=轨限速、青=LZB 顶棚）+
    红针；表内大数字 km/h；下方 P/B/E 挡位；
  - 左侧：MANUAL/ATO、AWS/LZB 制式 chip（≥101=LZB）+ 当前限速 chip；
    LZB 盒（列控）：目标速度大字（0=红"停车目标"）+ 顶棚 + 距目标数字 + 距离条；
    AWS 灯（未确认琥珀闪烁/已确认绿）、占用等待灯、保护 SPAD 红灯、DO/DC 门态；
  - 保留平台停车条与 MMTR 状态回显（SRV/RND/DIFF/CTRL/DRV）作数据核验用。
- fabric `:fabric:compileJava` 通过。

## 待办

- 实机验证：用户 dev client 进入驾驶座（持钥匙）截图，按截图迭代布局/配色；
- 旧 DrivingGuiRenderer 暂留作回退参考，验收后删除；
- 动画/缓动（表针、灯闪烁已有 tick 相位）后续按需加。
