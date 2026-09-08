# 30 - 信号 S1：占用与防追尾底座（已交付 282/0/2 全绿）

> 前置：docs/01-设计/信号系统-可行性分析.md（v3 定案：轨限速 >101 LZB / ≤100 AWS、双层限速模型）。
> S1 = 两制式公共安全网：**线上多车不撞、前方占用必停**。AWS/LZB 显示层（S3/S4）在其上叠加。
> 交付：commit（见 git log）；测试 5 新用例全绿；全量 273→282/0/2 零新增失败。

## 1. 代码事实（核对 14fef6c）

- motion 车**写**共享占用树：`Vehicle.writeMmtrMotionVehiclePositions`（L1562）每 tick 末把
  mmtrMotionLegs shadow（PathData 段）按 `getBlockedBounds` 段坐标写进 `vehiclePositions.get(1)`；
  **motion 分支不读**——`simulateMmtrMotion`（L792）全程未查 vehiclePositions（legacy 分支才有
  `railBlockedDistance` 前视 L1361-1366）。
- 占用树生命周期：`Simulator.tick` 每 tick rotate（removeFirst + add 空树，L874-878）→
  查询读 get(0)（上一轮完整）+ get(1)（本轮已 simulate 的车）。测试若绕开 Simulator.tick 需自 rotate。
- `VehiclePosition`：per-rail 两级树 key = (orderedP1, orderedP2)（Position.compareTo 字典序），
  `BlockedSegment(start,end,id)` 为段坐标（ordered 空间），`getClosestOverlap(qs,qe,rev,ignoreId)`
  返回重叠最近距离或 -1。addSegment 的 left=车尾、right=车头（沿行进向的近端/远端），段坐标同 ordered 空间。
- walker：`advance` 到节点 elect（operator>grant>target>单续>halt），offset=len 时仍停在原轨
  （haltedAtAuthority、offsetM=len、未跨轨）——即"车头停在节点上未进入下一轨"的既有 halt 形态；
  单续向直通不经 elect；peek（预问）需要无副作用复刻该决策。
- 车辆顺序：`Simulator.tick` → 各 siding.simulateVehicles → 每车 simulate（motion tick）→ tick 末写 footprint。

## 2. S1 语义（定稿，v0）

以"**每根轨 = 一个闭塞分区**"起步（虚拟边界/短块属 S4 LZB 细化，接口按块建模预留）：

1. **同轨前视（精确区间）**：当前轨上、车头前方最近的外部占用面（同向车=其尾、
   对向车=其头，统一取"与车头同侧最近端点"，无需方向判定）→ 停车点 = 占用面前 − GAP；
2. **下一分区禁入（预问）**：walker 预问下一轨（elect 链确定且会成功）→ 若下一轨有任何外部占用
   → 车头停在当前轨末端（节点上，offset=len，不跨轨——复用 halt 形态，Vehicle 层以
   toNode−ε 截断实现），占用消失（前车整体退出该轨）自动放行；
3. **wait-not-terminal**：占用停车不是终态：auto 车放行后自动续行到原停点目标；手动车放行后
   司机控制恢复（等待期间油门被压制）；
4. 车在轨上写占用含全列区间 [tail..head]，发车/跨轨自然扩展——两车同轨只有在"前车整列退出后
   后车才进入"的分区语义下成立，跟跑表现为"逐轨跳"（AWS 3-aspect 式，保守正确；LZB 区由
   S4 虚拟块加密缩小间隔）；
5. 自己胎生同轨（测试场景）：同轨另一辆车视为外部占用，按 1 停。

执行接线（simulateMmtrMotion 内）：
- 每 tick（服务器分支）算 `mmtrBlockStopM`（walker 距离空间）：
  a. 当前轨段窗口 [railProgress, 段尾] 外部重叠（复用 getBlockedBounds + getClosestOverlap，
     忽略自身 id）→ stopM = railProgress + max(0, overlap − GAP)；
  b. `walker.peekNextRail()` 非空且下一轨树条目存在外部 segment → stopM = min(stopM, 到节点距离)；
- 有效目标 `brakeTarget = min(stopTargetM(若 armed), blockStopM)`：auto 恒减速停点判据、精确落点
  clamp 全部改用 brakeTarget；落点归因：到的是 stopTarget → 现 arrive 流程（doors/stoppedAtTarget）；
  到的是 blockStop → 只停，置 blockedWaiting（不 arrive、不开门）；
- blockedWaiting && blockStop 未消失 → 速度钳 0（auto 与手动油门均无效）；
  blockStop 消失 → 清 blockedWaiting（auto 续行；手动恢复司机控制）。
- 手动车接近占用面：blockStop 进入制动包络 → 强制 service-brake（与 auto 同分支），
  语义 = 信号强制（闯红灯防护），与"AWS 区限速不强制"不冲突（限速是 S2+，占用是安全）。

常量：GAP（占用面停车安全距）初定 2.0 m（v0 常数，后续按车长/制式调）。

## 3. 改动面

1. `MmtrMotionWalker`：加 `currentRail()`、`peekNextRail()`（无副作用预问：复刻 advance 的
   节点决策序列；漂移风险用 S1 测试"peek 预测随后 advance 的 elect"锁死）。
2. `Vehicle`：motion 字段 mmtrBlockStopM/blockedWaiting + 前视 helper + simulateMmtrMotion 接线。
3. 测试 `MmtrMotionBlockingTests`（合成 Y 汇合网：Siding1→ext1、Siding2→ext2 汇 merge→PL→EXT，
   两股道各自单续向，merge 对每条来向无岔）。

## 4. 测试用例（合成，确定性，全量门禁零新增）

1. `followingTrainStopsBeforeOccupiedRailAndContinuesAfterRelease`（验收主场景）：
   车1 auto 停 PL 25m 处；车2 auto 同停点 → 停于 ext2/merge 前（不进入 PL、railHex 仍 ext2、
   offset≈末端、speed 0）；车1 续行出 PL → 车2 自动进入 PL 精确停 25±0.05、开门。
2. `manualDriverIsHeldAtOccupiedRailAndControlResumesAfterRelease`：车2 手动（override+throttle）
   顶着占用 → 引擎压制不进入 PL；放行后司机油门恢复前进。
3. `headOnVehiclesEachStopBeforeTheSharedNode`（对向）：两车相向各占汇合前一段 →
   各自停在节点前不跨入（R4 死锁人工/任务解，本测试只验互不侵入）。
4. `peekNextRailPredictsFollowingAdvanceElect`：T 岔 flips 前后 peek==advance 实际跨轨
   （锁 walker 预问与执行一致性）。
5. `occupiedCurrentRailStopsFollowingTrainWithGap`（同轨精确面）：胎生/模拟前车停同轨前方
   （构造两 walker 同轨先后）→ 后车停于前车尾 − GAP 前（不贴尾）。

## 5. 坑预警（实现时验证）

- 占用树必须 rotate 才反映"车已离开"（测试 helper 自 rotate，勿漏）；query 读两棵树（0/1）。
- 停"节点上"用 halt 形态：blockStop=到节点距离时截 advance 到 toNode−ε（ε=1e-3 m），
  车头 offset=len−ε；放行后整距跨轨。不得让 advance 跨入被占轨再停（那就侵入了）。
- mmtrMotionLegs 与树的 key/段空间约定完全一致（都是 PathData ordered 端点）——当前轨查询
  直接用 indexInMmtrMotionLegs(railProgress) 对应 PathData，勿自造 rail 端点换算。
- 忽略自身 id；同 siding 双车并存需要两 Siding（spawnMmtrMotionVehicle 要求 yard idle）。
- motion 车 tick 里 data.getCurrentMillis 只用于日志/镜像，测试手动 tick 不需推进 sim 时钟。

## 6. 不在 S1

- 虚拟块/LZB 区段加密、AWS 警示状态机与确认、LZB 连续曲线、限速接线（S2-S4）；
- legacy 车互操作混合测试（R3，S2/S3 补）；
- 运营台图层（S2/S6）。

## 7. 实现与验证证据（已交付）

- 代码：
  - `MmtrMotionWalker`：`currentRail()`（暴露当前轨）+ `peekNextRail()`（无副作用预问：复刻 advance
    节点决策 operator>grant>target>单续，endOfLine/halt 返回 null；javadoc 注明与 advance 同步义务）。
  - `Vehicle`：常量 MMTR_BLOCK_TAIL_GAP_M=2.0 / MMTR_BLOCK_NODE_EPS_M=0.001；字段 mmtrBlockStopM
    （每 tick 前视结果，walker 距离空间）/ mmtrBlockedWaiting（占用等待，非终态）；engage 重置。
    `simulateMmtrMotion`：tick 顶部 `computeMmtrBlockStopM` → 有效停车目标
    brakeTarget=min(停点目标, 块停点)；auto/手动统一按 brakeTarget 走服务制动包络与精确落点；
    落点归因三态：停点目标 arrive（门/终态）｜块停点 waiting（无门、非终态、牵引压制）｜advance 停。
    放行：brakeTarget 越过车头 1e-3 即清 waiting——auto 自动续行到原目标、手动司机恢复牵引。
  - `computeMmtrBlockStopM` 双规则：① 当前轨 [head, 轨尾] 窗口内最近外部占用面（getBlockedBounds+
    getClosestOverlap 忽略自身 id，同向尾/对向头同式）→ 距占用面 GAP 停；② `peekNextRail()` 非空且
    下一轨有任何外部占用（整轨=闭塞分区）→ 停在当前轨末端节点 ε 前（不跨入被占轨）。
    查询读共享 vehiclePositions 两棵轮换树（get(0) 上轮 + get(1) 本轮），key=ordered 轨端点，与
    footprint 写入同约定。
- 测试 `MmtrMotionBlockingTests`（5 用例；合成单线链 Y2-X2-Y1-MA-PL-EXT-Y3，三车场、全单续向）：
  1. followingAutoTrainStopsBeforeOccupiedRailAndContinuesAfterRelease —— auto 后车停在被占 PL 入口
     （46.0-ε 精确），前车整列出轨后同一 auto 续行到停点 70.0±0.05 开门；
  2. manualDriverIsHeldAtOccupiedRailAndControlResumesAfterRelease —— 手动司机+持续 fresh 命令被压制
     不侵入；放行后同命令恢复前进（block 停≠target 停：无需 fresh control）；
  3. headOnTrainsStopAtTheOppositeEndsOfTheSharedRail —— 对向两车各停共享轨两端节点（52.0-ε/72.0-ε）
     互不侵入（R4 死锁由人工/任务解，文档已记录）；
  4. externalOccupancyOnTheCurrentRailStopsWithGapAndSuppressesTraction —— 注入同轨外部占用：
     精确停占用面前 2.0m GAP（38.5），持续注入时 fresh 牵引不闭合 GAP，停止注入即恢复；
  5. peekNextRailPredictsTheFollowingAdvanceElect —— 未设岔 peek null + advance halt；operator
     0/1 flip 后 peek==advance 实际跨轨；task target 于未设岔（新 store）一致（锁 walker 预问/执行
     不漂移）。
- 全量门禁：cleanTest test = **282 tests / 0 fail / 2 skip**（基线 273/0/2 → +9 零新增失败）。
- 坑记录（实现期）：① arm 目标在车头身后会经"brakeTargetM-dist<=1e-6"误判 arrive（既有语义，
  非本次引入，planner 不产生身后目标）；② 注入测试必须每 tick 重注入（树逐 tick rotate）；
  ③ 车辆初始 headOffset=7m（(轨长12+列长2)/2）非直觉值，目标需在车头前方。④ 单轨=单闭塞分区下
  同轨精确面（规则①）只能靠注入/胎生场景触达——规则②（下一轨占用禁入）在正常跟跑中先发。
- 语义确认：占用等待期间信号压制司机牵引（司机 emergency 制动分支仍保留走 brake），与
  "AWS 区超限速不强制"（S2+）不冲突——占用/信号是安全层，限速是运营层。
- 下一步（S2）：motion 分支行经段轨限速钳制接线（legacy L1394 语义重接）+ 制式服务（轨限速
  ≤100 AWS / ≥101 LZB）+ mmtr-topology 带 speedLimit 实机摸底。
