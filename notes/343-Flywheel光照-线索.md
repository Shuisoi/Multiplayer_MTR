# 343 · Flywheel 的光照：**线索**（机制，不是照搬）

日期：2026-09-28 · 承接：notes/338（MTR 渲染管线）→ 339（别人怎么做，结论"每个 draw 一个光值"）→ 342（回退）

**问题**：338/339 量出来的硬上限是"MTR 这套架构，N 个光值 = N 个 draw"。
**问**：Create 的渲染后端 Flywheel 专门给"移动中的、任意几何的"东西做渲染，它怎么处理光？

**一句话答案**：Flywheel 把光当成**世界的属性**而不是模型的属性 —— 把世界的光场整体搬到 GPU，
在**片元着色器里按世界坐标 + 法线取光**。于是"几个光值"和"几个 draw"彻底解耦：
一节 20 格长的车厢跨十几个光照等级，仍然只是**一个实例、一次 draw**。

---

## 0. 证据来源（可复现）

| 来源 | 版本 | 为什么是这个版本 |
|---|---|---|
| `sandbox/flywheel/Flywheel-1.20.1-dev/` | Engine-Room/Flywheel `1.20.1/dev`（= 1.0.6-beta-266） | **Create 6.0.8（`mc1.20.1/dev`）`gradle.properties: flywheel_version = 1.0.6-beta-266` 用的就是它** |
| `sandbox/flywheel/Flywheel-1.20.1-0.6/` | `1.20.1/0.6`（旧引擎） | 对照：旧引擎的 `light/LightVolume.java`、`GPULightVolume.java` **全库无人引用 = 死代码**（0.5 遗留的"把光拷进 ByteBuffer 再逐 draw 上传"的思路，0.6 重写时被废） |
| `sandbox/flywheel/Create-mc1.20.1-dev/` | Creators-of-Create/Create `mc1.20.1/dev` | 看**消费方**：`ContraptionVisual` / `CarriageContraptionVisual`（火车！）怎么用这套 API |

（下载物在 `sandbox/flywheel/`：Flywheel 1.0 ≈ 2.5 MB，Flywheel 0.6 ≈ 6.5 MB，Create ≈ 31 MB，另有 3 个 zip ≈ 17.8 MB。可随时删。）

---

## 1. 机制（逐条带证据）

### 1.1 光场：把世界的光拷成 GPU 竞技场，按"节"管理

`backend/engine/LightStorage.java`

- 一节 = **18³**（原版 16³ 的 section **外扩 1 格边界**，这样 shader 一次取一节就够，不用跨节拼），每节 **5832 B**（`:45-53`）：
  - 前半：**实心位图**（`BitSet`，`SOLID_SIZE_BYTES`）——给 AO 用；
  - 后半：每方块 **block 光 4 bit + sky 光 4 bit**。
- 采集：`LightDataCollector.java`。快路径用 mixin 拿到原版光照引擎的 `DataLayer` 直接整节拷（`createFastSkyDataGetter` / `createFastBlockDataGetter`，`:49-92`），慢路径退回逐方块 `getLightValue(BlockPos)`（`:296-320`）；采一节时不只采中心，还把**六个面 / 十二条棱 / 八个角**都采进来（`:164-181`）——那是外扩那 1 格边界的数据。

### 1.2 触发：**只采有人要的节**，只上传变化过的节

- 视觉自己声明"我要哪些节"：`api/visual/ShaderLightVisual.java`（marker）+ `SectionTrackedVisual.SectionCollector.sections(LongSet)`。
  后端 `ShaderLightVisualStorage.java` 把所有视觉的请求**取并集**，按帧喂给 `Engine.lightSections(...)`（`EngineImpl.java:79-85`）。
- 世界光照变了：mixin 进光照引擎（`backend/mixin/light/SkyLightSectionStorageMixin`、`LightEngineAccessor`），
  调 `Engine.onLightUpdate(sectionPos, layer)` → `LightStorage.onLightUpdate`（`EngineImpl.java:84-85`）。
  → 收集该节**及其 26 个邻居**里我们跟踪的那些（`LightStorage.java:140-152`）。
- 上传：只有**变过**的节进 staging buffer 拷进 VBO（`LightStorage.java:238-243`）。
  从没变过的帧 = **零上传**。

### 1.3 索引：LUT，一张 buffer texture

`backend/engine/LightLut.java` —— 层级 Y → X → Z（`:11-13` 注释点明），值 **1-based**（0 表示"这节没有"），
`flatten()` 成 `IntArrayList` 上传为 `usamplerBuffer _flw_lightLut`；shader 侧 `_flw_nextLut` / `_flw_chunkCoordToSectionIndex`（`light_lut.glsl:28-67`）。

### 1.4 采样：在**片元**着色器里，按世界坐标取光

`light_lut.glsl:310-421` 的 `bool flw_light(vec3 worldPos, vec3 normal, out FlwLightAo light)`：

```glsl
ivec3 blockPos = ivec3(floor(worldPos)) + flw_renderOrigin;   // :314
```

调用点：`internal/common.frag:103` 的 `flw_shaderLight();` —— 它在 **fragment 的 `_flw_main()`** 里
（`internal/instancing/main.frag:6-11` → `_flw_main()` → `flw_shaderLight()`），
用的是**顶点阶段插值过来的** `flw_vertexPos` / `flw_vertexNormal`（`api_impl.frag:5,10`）
⇒ **逐片元**取光，不是逐顶点，所以再长的面也不会出现"顶点间光被线性糊掉"。

三档质量，编译期宏 `_FLW_LIGHT_SMOOTHNESS`（`compile/LightSmoothness.java:10-15`，**默认 SMOOTH**）：

| 档 | 做什么 | 取光次数 |
|---|---|---|
| `FLAT(0)` | 取所在方块的值 | 1 |
| `TRI_LINEAR(1)` | 三线性插值 | 8 |
| `SMOOTH(2)`（默认） | **等价原版区块烘焙的平滑光照 + AO**：取 3×3×3（27 次）+ 实心位图，按法线方向做角平均、`_FLW_INNER_FACE_CORRECTION` 修内部面 | 27 |

⇒ 这是最狠的一条：**Flywheel 为"任意几何"重现了原版区块烘焙级的平滑光照与 AO，而且不需要重新烘焙模型。**

### 1.5 per-instance 光**仍然在**，只做叠加

`instance/transformed.vert:7`：`flw_vertexLight = max(vec2(i.light) / 256.0, flw_vertexLight);`
→ 顶点属性光（`vertex_input.vert:15`，`_flw_aLight`）和实例光取 `max`，GPU 光场再往上叠。
材质可**逐材质**选 light shader：`SMOOTH` / `SMOOTH_WHEN_EMBEDDED`（**默认**）/ `FLAT`（`lib/material/LightShaders.java`、
`SimpleMaterial.Builder:170`）。取不到时优雅退化（`flw_light` 返回 false，就用原来的顶点/实例光）。

### 1.6 消费方（Create）怎么用

- `content/contraptions/render/ContraptionVisual.java:260-288`：把 **装置包围盒 + 1 格 padding** 的整节列表丢给 `sectionCollector.sections(...)`；
  火车再 +1 格（`CarriageContraptionVisual.java:39`，因为转向架总在包围盒外一点点）。
- 同文件 `:100-108`：把材质的 `CardinalLightingMode.ENTITY` 改写成 `CHUNK` —— 让机器看起来**像方块**而不是像实体。
- `CardinalLightingMode`（`api/material/CardinalLightingMode.java`）**只管方向性明暗**（CHUNK=按区块、ENTITY=按 `RenderSystem` 光方向），与"光值从哪来"正交。

### 1.7 调试手段（我们可以照抄）

`/flywheel backend lightSmoothness <flat|tri_linear|smooth|smooth_inner_face_corrected>` 现场切档；
`BackendDebugFlags.LIGHT_STORAGE_VIEW` 把每个被跟踪的节画成盒子（`LightStorage.DebugVisual:280-381`）。

---

## 2. 这条线索回答了什么 / 没回答什么

**回答了**：
1. **"一个 draw 一个光值"不是物理上限**，而是"把光烘进模型 / 按 draw 传光"的必然结果。
   只要光的**取值方式**从"每 draw 一个标量"换成"按位置查光场"，draw 数就不再和光值数量挂钩。
2. **移动物体不需要每帧重算**：光场留在世界坐标里，**采样点随几何走**。车在动，位置就变，插值出来的光自然连续变化
   —— 每帧 CPU 侧唯一的工作是"车没跨节、世界光没更新 ⇒ 什么都不做"。
3. AO 也能白拿（档 2 的实心位图），也是**世界方块**的 AO。

**没回答**：
- Flywheel 的 AO 是**世界方块的 AO，不是物体自遮挡**：车体自己的窗框不会给车内打阴影，车底盘不会给下方打阴影。
  想要那个量级的效果得另做（SSAO / 阴影贴图 / 预烘），别把它算进这条线索的收益里。
- 旧引擎（0.6 的 `LightVolume`/`GPULightVolume`）那条路是**死代码**，别去参考它。

---

## 3. 对 MMTR 意味着什么（三条路线，成本 / 风险都写清）

| 路线 | 做法 | 收益 | 成本 / 风险 |
|---|---|---|---|
| **A. 复刻光场（GPU）** | 自建"车占用的节"的光 buffer（+LUT），用**我们自己的 shader** 在片元里按世界坐标取光 | 逐片元平滑光 + 世界 AO；**draw 数不变**；车动不用重算 | ① MTR 现在画车用的是 vanilla `RenderType` + vanilla core shader，得引入自定义 `ShaderInstance`（配自写 core shader）并挂到 `QueuedRenderLayer` 的 shader state 上；② 要有"节→arena"的索引与增量上传；③ 光照更新靠 mixin 进光照引擎（或者简化成"车跨节时整片重采"）；④ **Iris/光影会替换 core shader ⇒ 必须能探测并退回现状** |
| **B. CPU 逐顶点光** | 光源仍是世界坐标：把每个顶点的 light 重算进**独立的 light VBO**（vanilla 本来就是"顶点属性带 lightmap"的机制） | 无 shader 风险；逐顶点平滑；不动 draw 数 | ① 每帧 ~顶点数 次光照查询（10 节编组可能上十万次，需要"只在车动/跨方块时重算 + 按方块缓存"来压）；② **待验证**：MTR 优化器的 VBO 里 UV2 是否还在、优化后的 shader 是读顶点 UV2 还是按 draw 传光（notes/339 记的是 light 挂在 `RenderCall` 的 `VertexAttributeState` 上，需要回 mappings 反汇编确认）；若是"按 draw 传光"，得改成读顶点属性 |
| **C. 现状** | 一车一光；想要更细就拆 draw | — | 就是刚被否决的 1 格切片方向（notes/341/342） |

**附：能不能直接用 Flywheel 本体？** 把 MTR 车辆改成 Flywheel 实例，等于白拿路线 A 的全部收益。
代价：MMTR 要**强依赖 Flywheel**（本机 dev 实例 `run/mods` 是空的，Create/Flywheel 都不在；
依赖来自 `build.gradle` 的 maven 声明），OBJ 要转成 Flywheel `Model`，MTR 的 part/renderStage 语义要重新映射。
⇒ **不推荐**，除非以后确定"总是和 Create 同装"。

---

## 4. 结论

**线索成立，而且是我们目前唯一一条"不动 draw 数就能加光照分辨率"的路。**
方向从"把车切成 N 段、每段一个光值"（结构上做加法，代价是 draw 数）改成
"光场留在世界、按位置取"（结构上做除法，代价是一次性的 shader/上传管线）。

下一步若要动手，**先做 B 的那条待验证项**（MTR 优化器的顶点 UV2 还在不在、shader 读的是不是它）——
它是 A 和 B 共同的前置：A 要"光从别处来"，B 要"光逐顶点写进缓冲"，两者都要先确认这条通路是否还通。
