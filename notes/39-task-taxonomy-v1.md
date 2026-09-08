# 39 - 任务体系 v1：MmtrTask 基类 + 首批派生（任务=某时×某地×某操作）

> 用户路线：记英铁 AWS 现实机制 → 任务系统 → 信号×道岔×任务结合（先做低速区整体行车
> 控制）。本片=任务体系领域层 v1；设计见 docs/01-设计/任务系统-任务派生与任务单时间表-设计.md，
> AWS 现实基准见 docs/01-设计/参考-英铁AWS与TPWS机制.md。

## 本片内容（engine/src/main/java/org/mtr/core/mmtr/task/）

- `MmtrTask`（抽象基类）：taskId / targetRef+targetKind（PLATFORM/SIDING/CONSIST/FREIGHT
  常量）/ earliestMs..dueMs（计划窗）/ note；派生类实现 kind() / describe()（时间表行文案）
  / validate()（目标类别与参数检查，防手改/反序列化注入）。
- `MmtrTaskKind` 枚举：DRIVE_TO_SIDING / DRIVE_TO_PLATFORM / STATION_SERVICE /
  DRIVE_TURNBACK / DRIVE_TO_CONSIST（连挂走行，接口预留）/ FREIGHT_WORK（占位）。
- 派生：DriveToSidingTask / DriveToPlatformTask / StationServiceTask(dwellMs) /
  DriveTurnbackTask(viaRailHex 可选) / DriveToConsistTask(approachSpeedKmh) /
  FreightWorkTask(workMs)。
- 测试 MmtrTaskTests 7 例全绿（kind/目标类别拒绝/参数钳制/describe 可读性）。

## 边界（按用户拍板）

- 连挂：Vehicle 派生（动力/非动力）后续片；DriveToConsist 目标先以对象 id（TARGET_CONSIST）
  占位；"专门地点生成非动力车 + 玩家开车连挂"场景待 Vehicle 派生片激活。
- MmtrMission 保留为运行时容器（state/executor/failure），下一步挂 task 引用；
- 手动派车 = 编辑选中车任务单（统一入口）；OP 时间表先只读。
