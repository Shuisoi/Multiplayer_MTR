# 06 连挂简化设计：单 Vehicle = 车厢序列编组（降低服务器开销）

## 背景与代码事实
- MTR 一个 Siding 只有一套 vehicleCars 模板（Siding#setVehicleCars / simulateVehicles ~L362），
  每趟车都用该模板 new Vehicle(...)。即“一条股道 = 一个固定编组模板”。
- 一个 Vehicle 是整列车刚体：单一 railProgress / speed / 加减速；vehicleCars 只是车厢清单
  （渲染/乘客/长度），每节车 1~2 个转向架（hasOneBogie），引擎内不存在车厢级纵向动力学、
  不存在多“列车节点”协调。
- 因此不要把连挂做成“两个 Vehicle 节点合并运动”（开销高、与 MTR 模型冲突）。

## 抽象：MmtrComposition.Unit ≡ MTR VehicleCar
- 编组 = 有序 Unit 序列；每 Unit 有 ConsistType + massRatio + powered + 独立管压/缸压（0..1）。
- 已实现：MmtrComposition.aggregate（质量加权牵引/制动/阻力）、stepAir（车头手柄 → 相邻管压
  均衡传播 → 每车缸压），含连挂空管充风语义（Unit.setAirState(0,0)）。
- “本编组 4 节板车 + 机车” = loco(powered)+flatcar x4(空管)；资源包仍是一节节车，只是模板按序拼接。
- 运动始终整车单 railProgress：无第二个体、无耦合器弹簧；服务器开销 ≈ 现在跑一列 8 节长车。

## 连挂/解挂 = 编组重排（单一 Vehicle）
1. 连挂：两列停稳对齐（同车场/股道）→ B 的 Units 追加到 A 尾部 → A.vehicleCars=拼接序列 →
   重建 VehicleExtraData（长度/每节乘客集合按车厢重排）→ B 的 Vehicle 从其 Siding 注销；
   B 各 Unit 置 pipe=0（空管，待充风）。全程只有一个 Vehicle 在跑。
2. 解挂：按 Unit 边界切分 → 尾部车厢生成第二辆 Vehicle（同 railProgress 靠后位置/解锁 depot 轨道），
   各侧气压状态保留（既有测试 emptyPipe/uncoupleKeepsIndependentState 覆盖模型层）。

## 落地切分（建议顺序）
- S1 模板混合：Siding/Depot 车辆模板支持 (carClass,count) 有序列表 → vehicleCars 拼接
  （UI 后置；先引擎+配置）。
- S2 接线：Vehicle.mmtr 分支由“单 ConsistType”改为“由本车 vehicleCars 构造 MmtrComposition”
  （默认全车同类型同 powered → 与现行为一致，零回归；AIR_BRAKE 车用 stepAir）。
- S3 连挂 op：engine 层 mmtr_couple/mmtr_uncouple（停稳判定、编组合并/切分、Siding 注销/解锁、
  空管初始化）；占用/司机权随合并传播。
- S4 UI/权限：驾驶/调车界面连挂解挂操作。

## 备注
- 需要轻量“车类→ConsistType/powered/unitId”映射（服务端配置或 VehicleCar 标记），避免重 schema。
- 钩缓 slack/缓冲动力学仍不做（MTR 本无此模型；保留在编组状态上可后续加，不进本次目标）。
- 参考：engine/src/main/java/org/mtr/core/data/{Siding,Depot,Vehicle}.java,
  engine/src/main/java/org/mtr/core/mmtr/{MmtrComposition,AirBrakeController}.java,
  tests: MmtrCompositionTests / MmtrMirrorTests / MmtrMultiplayerFoundationTests。
