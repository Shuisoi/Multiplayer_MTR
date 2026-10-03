# 340 · 渲染基线探针 `MmtrRenderProbe`（分段光照第 1 步）

日期：2026-09-28 · 承接：`notes/338`（渲染管线）→ `notes/339`（别人怎么做，§7.4 落地清单第 1 步）

**本轮只加读数，不改任何行为。** 目的：在动"沿车长分段取光"之前，先把**现状**量出来 ——
每帧多少次 draw、车辆占多少、优化批次本身占多少毫秒。段数与阈值要靠这些数字定，不许拍脑袋。

---

## 1. 改了什么（探针能整段删掉）

| 文件 | 改动 | 是否本轮新增 |
|---|---|---|
| `render/MmtrRenderProbe.java` | **新文件**（探针本体） | ★ 新 |
| `render/MainRenderer.java` | `beginFrame()`（null 守卫后）、`beginFlush()/endFlush()/endFrame()`（包住 `OPTIMIZED_RENDERER_WRAPPER.render(...)`） | ★ 4 行 |
| `resource/OptimizedRendererWrapper.java` | `queue(...)` 拆出 `queueInternal(..., vehicle)`；新增 `queueVehicle(...)`；`queueInternal` 里调 `MmtrRenderProbe.recordQueue(...)` | ★ |
| `resource/VehicleResource.java` | 车体主批次入队：`queue(...)` → `queueVehicle(...)` | ★ 1 行 |
| `resource/ModelPropertiesPart.java` | 门 / 机制件入队（两处）：`queue(...)` → `queueVehicle(...)` | ★ 2 行 |

> ⚠️ `git diff` 会把本轮的改动和**工作树里原有的未提交改动**混在一起
> （`ModelPropertiesPart` 的 W4 雨刷机制件、`MainRenderer` 的 `MmtrDataResync.tick()`、
> `panel/*`、`MmtrSectionBands`、`MmtrTaskHud` 等都不是本轮的）。本轮在本文件里只动了那两处 `queueVehicle`。

**"车辆"的定义**（只影响归属不影响总数）：车体主批次（`VehicleResource`）+ 门 + 机制件（`ModelPropertiesPart`）。
轨道与装饰物走 `StoredModelResourceBase.render(...)` → `queue(...)`，只进"全部"那一栏。

---

## 2. 它量什么

| 量 | 定义 | 为什么是它 |
|---|---|---|
| **帧间隔** | 这一帧 `beginFrame` 到下一帧 `beginFrame`（同相位） | 就是 1/FPS；"改完不许超过 X%"的判据本身 |
| **世界渲染段** | 一次 `MainRenderer.render` 调用的墙钟 | MTR 自己占的那一段（与帧的其余部分分开看） |
| **优化批次** | `OPTIMIZED_RENDERER_WRAPPER.render(...)` 的墙钟 | 顶点构建期上传一次、每帧不动 ⇒ 这段就是"每 draw 的 uniform + drawElements"，**分段会成比例增长的那一段** |
| **draws/帧** | Σ `OptimizedModel.uploadedParts.size()` | 反汇编确认：`OptimizedRenderer.queue(model,…)` 把该 List 交给 `BatchManager.queue(List,state)`，后者**每个 VertexArray 建一条 RenderCall** ⇒ 和 = draw call 数 |
| **车辆 draws/帧** | 只统计 `queueVehicle` 那三处 | 分段要乘的基数 |
| **入队/帧** | 调用次数（全部 / 车辆） | 与 draws 一起看，能分辨"次数多"还是"每次的 VertexArray 多" |

输出：每 5 秒一条 `[MMTR-DRAW]`（与既有 `[MMTR-PERF]` 同节奏，互不干扰）。示例形状：

```
[MMTR-DRAW] 5.0s 窗口：帧=298（59.6 FPS）帧间隔 p50=16.5ms p95=19.1ms 最慢=31ms
  ｜ 世界渲染段 avg=3.412ms p95=4.780ms 最慢=9.120ms
  ｜ 优化批次 avg=0.213ms p95=0.440ms 最慢=1.302ms（占帧 12.9‰）
  ｜ draws/帧 avg=12.3 峰值=41（车辆 avg=9.1 峰值=32，车辆为 0 的帧=17）
  ｜ 入队/帧 avg=4.20（车辆 3）｜ 客户端镜像车=3
```

**怎么读**：分段只影响 `优化批次` 与 `draws/帧`。放大倍数 ≈ (S-1)/现状车辆入队数（S = 段数）。
所以只要 `优化批次 avg` 相对 `帧间隔 p50` 还很小，分段就有充足余量。

---

## 3. 怎么跑

```powershell
# ① 先停掉 dev 会话（notes/195：运行中编译会把新类喂给活着的进程）
# ② 删掉可能残的输出目录（notes/281：Gradle 会报 UP-TO-DATE 但树是残的）
Remove-Item -Recurse -Force "mmtr\game\fabric\build\classes\java\main"
# ③ 真编译
cd mmtr\game; .\gradlew.bat :fabric:compileJava --console=plain --no-daemon
# ④ 起客户端，进世界，让带车的画面停留 ≥ 15 秒（至少 3 个窗口）
# ⑤ 看日志
Select-String -Path mmtr\game\fabric\run\logs\latest.log -Pattern 'MMTR-DRAW' | Select-Object -Last 6
```

- **关掉探针**：JVM 参数 `-Dmmtr.drawprobe=0`（默认开）。
- **量基线的要求**：固定机位、固定编组、固定视距；近处一段（车占屏幕大）与远处一段各留 3 个窗口，
  因为 draw call 数与"有几节车在视锥/遮挡剔除内"直接相关。

---

## 4. 验收阈值（改完之后拿它判）

本轮先把基线量出来；第 2 步（分段 + 细节距离）完成后，按同一机位/同一编组对照：

- **硬阈值**：优化批次 `avg` 与 `p95` 的增量都 **< 0.3 ms**；帧间隔 `p95` 增量 **< 3%**。
- **软阈值**：`draws/帧` 的增量应与"多出的段数 × 车辆入队数"对得上（对不上说明分段没生效 ——
  典型原因是又落回了 `PartCondition` 合并键，见 notes/338 §6）。

---

## 5. 诚实边界（读数字前先看这段）

1. **反射读 `uploadedParts`**：它是 mapping jar 的包私有字段、无访问器。读不到就退化成"每次按 1 次 draw 记账"
   并只告警一次（`[MMTR-DRAW] 读不到 …`）。**看到那条告警就说明 draws 数字不可用。**
2. **`hideTranslucentParts` 打开时略高估**：半透明批次不画，但仍被计入。
3. **开 GUI / 暂停不计帧**：`MainRenderer.render` 不被调用，那一小段不进帧数 ⇒ 该窗口 FPS 偏低、窗口被拉长。
4. **阴影 pass 不记账**：`OptimizedRenderer.renderingShadows()` 为真时整帧跳过；否则一个视觉帧会记两次。
   （光影开着时，`optimizedRenderer.render` 真的会被调用两遍，所以这个跳过是必须的。）
5. **没量 batch / 材质切换数**：需要 mixin 进 mapping 类的 `BatchManager`（NTE 的 `DrawContext.recordBatches` 就是这个），
   本轮**故意不做** —— 先用最便宜的手段拿到关键量；只有当 draws 解释不了耗时时再加。
6. **半透明批次的排序开销**在 `render(translucent=true)` 里，已包含在"优化批次"里。

---

## 6. 怎么撤

1. 删 `render/MmtrRenderProbe.java`；
2. `MainRenderer.java` 去掉 4 处调用（`beginFrame` / `beginFlush` / `endFlush` / `endFrame`）；
3. `OptimizedRendererWrapper.java` 恢复成单个 `queue(...)`（去掉 `queueVehicle` / `queueInternal` / `recordQueue`）；
4. `VehicleResource.java`、`ModelPropertiesPart.java`（2 处）把 `queueVehicle` 改回 `queue`。

全程无状态迁移、无存档影响、无资源包影响。

---

## 7. 本轮验证

| 检查 | 结果 |
|---|---|
| `pwsh mmtr\scripts\check-java-compile.ps1 -Quiet` | **OK — 453 源 / 668 类**，退出码 0 |
| `pwsh mmtr\scripts\check-paths.ps1` | **OK**（无机器相关绝对路径） |

---

## 8. 基线实测（2026-09-28，dev 客户端 + dev 服务端）

**工况**：客户端已连 `127.0.0.1:25565`；引擎 API（`/mtr/api/map/mmtr-trains`）报
**1 列车 / 10 节编组 / 55 km/h / onRoute=true**；客户端 `镜像车=1`；视距默认。

**两条独立来源的帧率一致**（这条用来证明帧记账没错）：
`[MMTR-PERF]`（notes/177 的既有探针）118 FPS ↔ `[MMTR-DRAW]` 118 FPS。

| 量 | 车在视野内 | 车不在视野 | 备注 |
|---|---|---|---|
| 帧率 | **117–118 FPS**（帧 ≈ 8.5 ms） | 116–118 FPS | 两条探针一致 |
| 世界渲染段 avg | **1.38 – 1.70 ms** | 0.99 – 3.95 ms | 占帧 ~16–20% |
| 优化批次 avg | **0.305 – 0.398 ms** | 0.31 – 0.35 ms | 占帧 **3.8 – 5.0‰×10 = 3.8–5.0%** |
| 优化批次 最慢 | 1.06 – 6.02 ms | 1.13 – 1.98 ms | |
| **draws/帧 avg** | 1342 – 1679（峰值 1598–2225） | 1478（恒定） | |
| **车辆 draws/帧** | **152.0（avg == 峰值，极稳）** | 0 | 10 节编组 ⇒ **约 15 draws/节** |
| 入队/帧 avg | 1301 – 1545（车辆 18–138/帧） | 1478（车辆 0） | 轨道占绝大多数 |
| 反射告警 | 无 | 无 | draws 是真值，非退化值 |

### 8.1 三个结论

1. **车辆只占总 draw 的约 10%**：152 / ~1434。**3D 轨道占了 ~1478 draws/帧 —— 是车辆的十倍。**
   （顺带印证 notes/339 §7.2②：NTE 用 `InstancedRailChunk` 实例化画的正是轨道，MTR 4 这条还没做。
   真要优化渲染性能，大头在轨道那边，不在车辆。）
2. **分段代价上界可算**：10 节 × 8 段，最坏情况每段仍带 2 个材质组 ⇒ 车体 8→16 个 VertexArray/节
   ⇒ **+80 draws/帧 ≈ 总 draws 的 +5.6%**；优化批次按比例 **+约 0.02 ms**。最乐观（每段 1 个材质组）则几乎不变。
   ⇒ **余量极大，8 段完全放得下。**
3. **但"优化批次"只量了 CPU 侧提交**：1630 次 draw 只花 0.35 ms（≈215 ns/draw），说明瓶颈不在 CPU 提交
   （GL 调用是异步的）。**GPU/驱动侧的 draw 固定开销量不到**（要 GPU timer query）。
   ⇒ 所以分段的验收**不能只看优化批次**，必须做**同机位/同编组的帧间隔 A/B**，必要时才上 GPU timer。

---

## 9. 探针自身的缺陷（本轮修掉，需重启客户端才生效）

★ `record()` 原实现写成 `(int) value` —— **忘了除以格宽**，值被当格号用，于是分位数整体缩了
`bucketMillis` 倍。现场症状是**同一行里两个数自相矛盾**：

```
帧间隔 p50=4.0ms ……（同窗口 MMTR-PERF 报 118 FPS ⇒ 真值约 8.5ms）
世界渲染段 avg=1.564ms p95=0.200ms 最慢=6.893ms   ← 5%×6.893 也凑不出 1.564 的均值
```

**判据是"均值 vs p95 vs 最大值三者必须自洽"** —— 这次就是靠这条抓出来的
（`avg ≤ 0.95×p95 + 0.05×max` 必然成立，不成立就是分位算错了）。

修法：`record(histogram, bucketMillis, value)` 里做 `value / bucketMillis`；`percentile` 改回**所在格的上沿**
（偏保守、上界一格宽）。均值与最大值一直是原始量，从未受影响 —— 所以 §8 表里的 avg/峰值/FPS/draws **都可信**，
不可信的只有 p50/p95 两列。

