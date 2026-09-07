# 22 - L3 Slice-8：运行中任务目标重定向（task-target fork 裁决，本会话）

> 承接 notes/14-21。补上目标措辞中"任务目标经 MmtrNodeRouter 选下一段"在**运行中 Vehicle** 上的
> 引擎级证明与能力：walker live 重定向 setTargetRailHex —— 任务/操作层可在车跑动途中改目标，
> 下一个岔口决策即按任务目标走：**任务目标覆盖陈旧 operator**，且在未设岔口上**任务目标本身就是
> 权威**（绝不 auto、但任务指了路就不卡）。登轨目标轨后停车（offset≈0）；清除目标即恢复自由跑。

## 1. 改动
- MmtrMotionWalker：targetRailHex 去 final + setTargetRailHex(@Nullable)：
  - 与 ctor/advance 同一语义（electAtFork 每次现读 targetRailHex；登轨置 atTarget 停车）；
  - 重定向到别的轨/清空时复位 atTarget（=恢复运行），当前轨==目标保持不变；
- Vehicle：任务目标登轨（consumed<integrated 且 walker.atTarget）日志改为
  "[MMTR-DRV] motion arrived at task target rail …"（不再误报 authority halt）。

## 2. 测试（MmtrMotionTaskTargetTests，2 例全绿）
1. liveTaskRetargetOverridesStaleOperatorAtTheFork：operator 预置 0（直向）；手动车跑到车场中段时
   live setTarget(分叉轨) → 岔口按任务目标走（覆盖陈旧 operator 0）登分叉轨停车（offset<2m）；
   清除目标 → 同一趟恢复运行（progress 继续增长）。
2. taskTargetIsTheAuthorityOnAnUnsetFork：无任何 operator；出发即武装任务目标 → 未设岔口不阻塞，
   按任务目标登分叉轨停车；断言 haltedAtAuthority=false（任务已裁决，从未等 operator）。

## 3. 回归证据
- 定向：data.* + mmtr.* + operation.* 全绿；全量：slice8-full.log（基线 243/0/2 → 预计 +2）。

## 4. 边界与下一步
- 边界：任务目标=下一岔口的期望轨（hop 语义）；多节点长程目标仍需 planner 预置/逐步更新
  （MmtrRunPlanner 已覆盖进路；二者可组合：planner 预置 + 运行中 task 覆盖 = 人工/任务应急改径）；
  登轨即停车是 walker 语义（任务到达站），如需"越过目标轨继续"请清除或改用停点目标。
- 下一步（均另立项）：作业调度宏接 motion；M2 行经段信号/限速；平台停点对齐；客户端镜像；实机清单。
