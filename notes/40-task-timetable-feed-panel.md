# 40 - 任务单时间表：mmtr-schedule feed + OP 只读面板 v1

> 任务系统第 2 步（设计见 docs/01-设计/任务系统-任务派生与任务单时间表-设计.md §4）：
> 把车底作业单抽象成时间表给 OP 看（玩家时间面板待 HUD-2 片）。

## 引擎侧

- SystemMapServlet 新 `mmtr-schedule`：逐作业输出 `rows[]`（每步骤一行）：
  stepIndex/stepId/type/taskKind（MOVE_TO 平台→DRIVE_TO_PLATFORM、MOVE_TO 股道→
  DRIVE_TO_SIDING、SERVE→STATION_SERVICE、COUPLE/UNCOUPLE 原名）/targetKind+targetId
  （PLATFORM/SIDING，按平台判定）/plannedMs（due）/note；作业级 state/currentStep/
  failure/loop；行状态由 job 状态推导（已完成 DONE、当前 RUNNING、FAILED 死单标 FAILED）。
- 与 `MmtrTaskFactory` 同一映射口径（servlet 侧 taskKindOf）。

## WEB（OP 只读面板）

- 新 `mmtr-schedule.service.ts`（3s 轮询）+ ops-panel "任务单时间表" section：
  每作业一张表（#/任务中文短标签/地点=站名或车场·道名/计划 T+mm:ss 或 h:mm:ss/状态徽章），
  当前运行行高亮，作业失败原因展示。

## 状态

- engine 编译通过、website 构建通过；dev server 重启验证：6 作业逐行时间表正常返回。
- 玩家侧时间面板（HUD-2 内）后片接入；OP 编辑/手动派车入口后续（现只读展示+作业单编辑器已有）。
