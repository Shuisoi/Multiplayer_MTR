# 172 = 网页刷新不再吃掉游戏 tick：只读接口走快照发布 + tick 时间片

> 触发（2026-09-16，用户）：**「web 更新地图时，地图更新居然会阻碍客户端动作，这在 64 人多人游戏是致命的」**。
> 状态：**已实施（引擎侧 + 前端取数层）**。用户裁定「第一步第二步一起做」＝止血（tick 预算/缓存）
> ＋结构（把活从 tick 里搬出来）。

---

## 1. 根因：网页的活儿是在**游戏主线程的一次 tick 中间**算的

不是"Java 计算奇怪"，是一条**结构链**，每一环都可查：

| 步 | 位置 | 事实 |
|---|---|---|
| 1 | `servlet/ServletBase.java` `run(...)` | 每个请求都 `simulator.runWeb(...)` —— 不在 Jetty 线程上算 |
| 2 | `simulation/Simulator.java` | 入队成 `WebRun`，**在 `tick()` 里面**逐条执行 |
| 3 | `mod/Init.java` `registerStartServerTick` | `useThreadedSimulation=false` 时 `main.manualTick()` 由**服务端 tick 回调**调用 ⇒ 模拟线程 **就是 MC 服务端主线程** |
| 4 | `run/config/mtr.json` | 实机就是这个配置：`"useThreadedSimulation": false` |
| 5 | `servlet/MessageQueue.process()` | 队列**一 tick 抽干**（不是每 tick 一件）⇒ N 个并行请求＝同一 tick 里 N 份重活 |
| 6 | `ServletBase.buildResponseObject` | JSON 序列化也在那个 runnable 里 ⇒ 也在主线程 |

于是"网页刷一次地图"＝"主线程在若干 tick 里多做几份重活"。50 ms 的 tick 预算，被吃掉 200 ms 就是四个 tick，
**全体玩家同时卡一下**。`mapContext.ts` 里还留着当时的现场数字：浏览器侧单次响应 45–112 ms、
服务端日志 `Can't keep up! Running 18724ms / 32009ms behind`。

## 2. 先量，再改（这一轮的实测数字）

新增 `MmtrWebFeedCostTests`：在**真实 dev 世界**（`[WEB-COST]` 行原样：159 轨 / 96 灯 / 25 站台 /
4 车辆段 / 16 股道）上逐路量"构建一份 JSON 本身"要多少毫秒，冷启动与稳定态分开记：

| 接口 | cold | warmAvg | 正文 | 窗口 |
|---|---:|---:|---:|---:|
| mmtr-trains | 183 | **186** | 22 KB | 400 ms |
| mmtr-schematic | 148 | 125 | 69 KB | 1 s |
| mmtr-sections | 149 | 118 | 205 KB | 1 s |
| mmtr-total-sections | 102 | 74 | 102 KB | 400 ms |
| mmtr-block-sections | 248 | 67 | 132 KB | 1 s |
| mmtr-signals | 54 | 48 | 61 KB | 400 ms |
| mmtr-lamps | 44 | 47 | 37 KB | 1 s |
| mmtr-points | 10 | **5** | 52 KB | 400 ms |
| mmtr-topology | 5 | **0** | 203 KB | 5 s |

**地图页一拍三路**（points + signals + trains）＝约 **240 ms**；区间页一拍（points + signals + total-sections）
≈ **127 ms**。结论有两条：

1. 浏览器侧那 45–112 ms **是真算出来的**，不是"等下一次 tick"的时间；
2. 光靠缓存/限流**不够** —— 一次 `mmtr-trains` 就是 186 ms，一个 tick 装不下。

（顺带一条工具教训：**源码里任何一处语法错，javac 会整个跳过注解处理** —— Lombok 全不生效，
于是满屏"找不到符号 log"，看着像环境坏了。本轮先被这个假象带偏过一次。）

## 3. 这一轮做了什么

### 3.1 只读接口走**快照发布**（新 `servlet/WebFeed.java`）

`SystemMapServlet.FEEDS` 是"哪些接口可以不进 tick"的**唯一真源**（13 路只读接口，各有自己的窗口）：

| 层 | 窗口 | 接口 |
|---|---|---|
| 活数据 | 400 ms | trains / points / signals / total-sections |
| 半静态 | 1 s | track-sections / block-sections / lamps / sections / schematic |
| 静态 | 5 s | topology / lines / junction-legs / platforms |

一次请求的走向：**够新 ⇒ Jetty 线程直接把已发布的那一份写出去（对 tick 零开销）；过旧 ⇒ 只让一个请求
回模拟线程重算，算完发布给后面所有请求共用**；没人问时这一层什么都不做（没有定时器）。

- `ServletBase` 新增钩子 `tryServeFromSnapshot(...)`，命中就在本线程答完，**不进模拟线程**；
- 序列化与 UTF-8 编码也跟着搬到 Jetty 线程（同一代只做一次）；
- 写接口一律留在 switch 里 —— 那是唯一改状态的地方（用例钉住：`mmtr-point-op` 必须让 tick 计数 +1）。

### 3.2 tick 里那点活加**时间片**（`Simulator.mmtrProcessWebRuns`）

网页任务单独排队（`queuedWebRuns`），与游戏侧那队（保活/会话清理）分开，三道闸按"最该让开"的顺序：

1. **冷却**：刚跑过一个 ≥ 20 ms 的重活，接下来 400 ms 不接网页任务 —— 把"连着四五个 tick 全卡"
   摊成"一个重活 + 一口气"；
2. **本 tick 已经 ≥ 40 ms**：整队让开；
3. 否则按 **2 ms 时间片**跑，**至少跑一条**（否则服务器一忙，网页队列永远不动）。

队列深度天然有界：同路的重复请求在 `WebFeed` 里就合并了，"开十个标签"不会变成十条任务。

### 3.3 内容没变 ⇒ 304（`ETag`）

发布的代次**只在内容真的变了时才前进**（发布时与上一份逐字段比一次 `equals`，不算字符串）。
于是带着 `If-None-Match` 回来的客户端拿到 304，**连序列化都不做**；前端 `api/client.ts` 记住
`ETag + 数据`，304 就把上次的数据还回去（不重解析、不重建图层）。路径写错会静默变慢，所以单列了一组用例
（`scripts/api-client.test.ts`，5 条）。

### 3.4 前端取数层（`views/map/liveFeeds.ts`）

- **页面切到后台就停表**（`visibilitychange`）：控制台是常年挂着的页面，切走了还在每拍打三个接口是白送的 tick；
  回到前台立刻补一拍；
- **一拍只跑一次**（`tickSafely`）：慢响应不再被自己堆成一队；
- 卸载时把定时器与监听都收干净（顺带修掉"await 期间卸载 → 监听/定时器泄漏"那条老缝）。

### 3.5 顺带修掉的算法病：`mmtr-trains` 的 O(车 × 轨)

`getMmtrTrains` 原来**每辆车**都在 `simulator.rails` 与 `positionsToRail` 上线性找一遍"我脚下这根轨 /
它的折线首端"，每次都要对全世界每一条轨算一遍 `canonicalHex`（字符串）。改成一次建索引
（`MmtrRailIndex`）逐车查表，口径逐位不变。**159 轨的小世界上没看出差别**（186 ms 基本没动，说明
真正的开销在别处）—— 但它是 O(车 × 轨) 的，64 人级世界（数千轨 × 数十车）会把这一项放大成主项。

## 4. 可观测性（下一次现场怎么证明它有效）

`[MMTR-HLTH]` 心跳行新增六个字段：

```
webHits=<快照答出次数> webBuilds=<重建次数> webMaxMs=<本窗口最慢一条> webSlow=<接口名>
webQueue=<当前深度> webSkipped=<tick 太慢让开次数> webShed=<冷却让开次数>
```

读法：**webHits 远大于 webBuilds** ⇒ 快照层真的在挡请求；`webSlow` 就是"这一路还太贵"的作案人；
`webShed` 大 ⇒ 冷却频繁介入（说明重活多，该按第 5 节继续拆）。单条 ≥ 25 ms 另外打一行 warn（10 s 限频）。

## 5. A 步：把重活拆薄（已做，实测 6–30×）

B 之前先把"重活为什么贵"量到底。**结论与最初的猜测完全相反**：不是 O(车 × 轨)（那份存档一辆车
都没有，`mmtr-trains` 照样 143 ms），而是两处**看起来无害、实则每个内层调用都在做全世界扫描**的东西：

| # | 真凶 | 事实 |
|---|---|---|
| 1 | **占用逐步账无条件拼字符串**（`MmtrSectionService.isOccupied`） | 每个 span 拼一段（`shortHex` ＋ 两次 double 转字符串 ＋ 五六段拼接，还要 `get` 回来再拼），而 `isOccupied` 是引擎最热的查询之一：一次链走行问它好几遍、每根轨每端一次、每盏灯一次、每台车每 tick 若干次。**读者只有一个**（一条用例的失败消息）。改成默认关（`setOccupancyTraceEnabled`），打开时逐字不变。 |
| 2 | **`refresh()` 一次链走行算 7–8 遍世界签名** | 签名要遍历全世界的轨／道岔／灯／进路，实测 **25 µs** 一次；而 `chainDepth` 会连锁调用四五个公开查询（`sectionProtecting` / `boundaryNodeKeys` / `isOccupied` / `followings`），**每个都先 `refresh()`**。`aspectsForAllRails()` 一次请求 318 条链 ⇒ 约 **2500 次签名**。加"一次外层查询只算一遍"的帧（`withinRefreshFrame`，守卫语义与 166 R4 不冲突：外层一定刷新，只是帧内不重算）。 |
| 3 | **`signature()` 里的 `mmtrAllTurnouts()`** | 每次分配数组 ＋ 拷贝 ＋ 排序 ＋ 排序比较里每次再拼两个 `key()` 字符串。换成 `Simulator.mmtrTurnoutStateSignature()`（纯整数混合，不分配不排序）。 |
| 4 | **`computeRailAspectMap` 每请求 `new MmtrSignalAspect(...)`** | 把 `Simulator` 每 tick 维护的缓存视图整个丢掉（车辆自己问信号用的就是它）。改读 `mmtrSignalAspectView()`。 |

### 实测（同一份 dev 世界，warm 取最小）

| 接口 | 拆薄前 | 拆薄后 | 倍率 |
|---|---:|---:|---:|
| **mmtr-trains** | 143–190 ms | **10 ms** | ~15× |
| **mmtr-sections** | 106–145 ms | **3 ms** | ~40× |
| mmtr-block-sections | 53–69 ms | **2 ms** | ~30× |
| mmtr-lamps | 35–47 ms | **4 ms** | ~10× |
| mmtr-total-sections | 58–74 ms | **8 ms** | ~8× |
| mmtr-signals | 44–56 ms | **18 ms** | ~3× |
| mmtr-points | 4–5 ms | 6 ms | — |
| mmtr-schematic（控制台没用它） | 116–142 ms | 83 ms | ~1.5× |
| **一次刷新全部 13 路** | **577–708 ms** | **134 ms** | ~5× |

**地图页一拍三路**（points + signals + trains）＝ **约 34 ms**（原来 190–240 ms）；
**区间页一拍**（points + signals + total-sections）≈ **32 ms**（原来 127 ms）。
单路最大尖峰从 190 ms 降到 23 ms —— **半个 tick 以内**。

## 6. 还没做：B（把构建搬出 tick 线程）

A 之后，dev 世界这一档已经达标（一拍 34 ms，尖峰 ≤23 ms，且经快照层摊平/共用）。B 的价值在**余量**：
成本随世界规模线性长（3 倍的轨/灯 ≈ 3 倍的 ms ⇒ 又把尖峰推回百毫秒级），要"64 人级世界也保证不卡"，
就得让构建**不在 tick 线程上**跑：主线程按拍发布一份**不可变输入快照**（结构按引用 ＋ 占用/进路的紧凑拷贝），
构建线程只在快照上算。代价是读侧要有一份"按快照读"的实现（`MmtrSectionService` / `MmtrSignalAspect`
的读路径），是本轮里唯一还没动的大件。

### 顺手记下两条"以后会咬人"的（都不建议现在动）

- **`MmtrSignalAspect.restrictedNodeKeys()` 用 `trees.hashCode()` 当缓存签名**：那是**深哈希**，
  对整棵占用树逐条算 —— 而它每个边界节点问一次（318 条链 × 每级每段两个节点）。
  dev 存档一辆车都没有 ⇒ 实测 0 ms（所以这一轮修不到它）；**真机上一多车就会变成主项**。
  正确修法是用 Simulator 维护一个 O(1) 的"占用版本"，但那会把缓存精度从"足迹一变就失效"
  降到"一 tick 一失效"—— 而这条链**同时喂停车判据**，所以必须单独一轮并配停车用例，不能顺手改。
- **`mmtr-schematic` 83 ms**：当前控制台不请求它（只按需打开），所以不着急。

## 7. 一句话结论（写给下一个会话）

**机制 ＋ 拆薄都做完了（A），dev 世界这一档达标**：网页刷新从"每拍吃掉 190–240 ms 的 tick"
变成"每拍约 34 ms、且经快照层共用与摊平、单路尖峰 ≤23 ms"，全量用例 712/0/4。
**要"64 人级大世界也保证不卡"，还差 B**（把构建搬出 tick 线程）；在那之前，`[MMTR-HLTH]`
的 `webMaxMs` / `webSlow` 就是判断"这个世界规模下还够不够"的那个数。
