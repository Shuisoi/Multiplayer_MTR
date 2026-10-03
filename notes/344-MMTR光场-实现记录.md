# 344 · MMTR 光场：复刻 Flywheel 的光照（实现记录 + 三个坑）

日期：2026-09-28 · 承接：notes/338（渲染管线）→ 339（别人怎么做）→ 340（基线探针）→ 342（切片方案回退）→ 343（Flywheel 线索）

**目标**：让车厢各部分的光照随它**真实所在的世界位置**变化（现在整辆车共用"车中心"采到的那一个光值），
**且不增加 draw call**。做法 = 复刻 Flywheel：把世界光场搬到 GPU，在**片元**阶段按世界坐标取光。

---

## 1. 为什么必须走着色器（先否掉更便宜的路）

`OptimizedModel.DEFAULT_MAPPING`：`POSITION / UV_TEXTURE / NORMAL` 来自 VBO，**而 `UV_LIGHTMAP` 是 `GLOBAL`** ——
优化器的顶点缓冲里**根本没有光照属性**（`RawMesh.upload` 只写 VERTEX_BUFFER 来源的字段，stride 24），
光值由 `VertexAttributeState.lightmapUV` 通过 `glVertexAttribI2i` **每个 draw 一个常量**喂进去。

⇒ "把光逐顶点写进 VBO"这条路不存在（除非改库），而"按 part 采光"也没用：
`VehicleResource.queue` 是按 `PartCondition`（一个条件一个模型）入队的，
每辆车只有 ~15 次 queue ⇒ 逐 part 采光 ≈ 还是逐车一个亮度。
**唯一能加分辨率的地方就是着色器**。

## 2. 实现（新增文件）

| 文件 | 作用 |
|---|---|
| `org/mtr/mod/render/light/MmtrLightField.java` | 光场本体：section 采集 → R8UI 图集（256 槽 × 64×64）+ R32UI LUT（相机相对 section → 槽位），每帧上传变过的图块并 `setShaderTexture(3/4)` |
| `assets/mtr/shaders/core/mmtr_vehicle_light.{json,vsh,fsh}` | 我们自己的 CUTOUT 程序：顶点算出**相机相对世界坐标**，片元三线性取光 + 世界 AO（AO 待做） |
| `org/mtr/mod/render/light/MmtrOptimizerStats.java` | 数 draw / 批次，验证"不加 draw" |
| `org/mtr/mixin/PatchingResourceProviderMixin.java` | 把 MTR 的 CUTOUT 程序**整体换成我们那份**（注入点在 `getResource` 的 RETURN） |
| `org/mtr/mixin/ShaderManagerMixin.java` | 观察 `reloadShaders()`（补丁什么时候有机会打上）+ 数批次 |
| `org/mtr/mixin/BatchManagerRenderCallMixin.java` | 数真正的 draw（`BatchManager$RenderCall#draw`） |
| `org/mtr/mixin/ClientPlayNetworkHandlerLightMixin.java` | 光照更新包到达 → 标脏对应区块列里我们跟踪的 section（Flywheel 的 `NetworkLightUpdateMixin` 同款） |
| `org/mtr/mixin/DummyClassMixin.java` | **把 MTR 吞掉的异常 cause 链打出来**（见 §3） |
| `MainRenderer.java` / `RenderVehicles.java` | 各加 1~2 行：draw 之前 `beginFrame(offset)`；渲染循环里 `requestCar(...)` 登记 section |

**坐标口径（全链路一致才有意义）**：着色器里的顶点坐标是
`ModelViewMat * ModelMat * Position`，其中 `ModelMat` 是 MTR 每个 draw 的矩阵
（`StoredMatrixTransformations.transform` 减掉了 `MainRenderer.render(graphicsHolder, offset)` 的 `offset`），
所以这是**相机相对世界坐标**。因此 LUT 也按"相机相对 section"建，
而且因为相机位置带小数，**一个世界 section 的方块会落进 1~2 个相对 section**，LUT 每个轴最多写 8 格、**每帧重建**
（486 个 int，可忽略）。这样就不需要把相机位置传进着色器。

**降级链**：LUT 槽位 0 = 没数据 → 着色器返回 0 → `color *= max(lightMapColor, fieldLight)` 里的 `max` 保住原来的
per-draw 光值（车内强制满亮 `CUTOUT_BRIGHT` 也不会被弄暗）。光影包在用（`Utilities.canUseCustomShader()==false`）时
**一个字节都不改**；另有 `-Dmmtr.lightfield.disabled=true` 一键彻底关掉。

## 3. 三个坑（都是实测踩出来的，值得记住）

### 3.1 片元着色器有"按文件名"的编译缓存 —— 改写原版 fsh 根本不生效

`ShaderProgram.loadShader` 把已编译阶段缓存在 `ShaderStage.Type.loadedShaders`（**只按文件名 key**）。
原版自己先编译过 `rendertype_entity_cutout.fsh`（实体渲染要用），于是"改原版 fsh"这一招**永远不会被重新读取**：
程序里只有我们改过的顶点阶段，`Sampler3/4` 不存在 → 采样器警告 → `ShaderProgram` 构造抛异常 →
`ShaderManager.isReady()` 永远 false → **车、轨全都不画**。
（这也解释了用户看到的现象："**开光影能显示，不开没有实体**"——有光影时 MTR 走回原生 RenderLayer，压根不用这套程序。）

**修法**：不要改原版，整份换成自己的程序，两个阶段都用**独立文件名**（`mmtr_vehicle_light`），缓存永不命中。
MTR 自己其实也用了同一招（把 vertex 改成 `..._modelmat`），只是它没告诉我们 fsh 也要这样。

### 3.2 `packed` 是 GLSL 保留字

`uint packed = ...` → `error C0000: syntax error, unexpected '='`。改名 `packedLight`。

### 3.3 片元里用 `Sampler2` 必须自己声明

原版只在顶点阶段声明 `Sampler2`（光照贴图），片元里用它换算颜色会得到
`error C1503: undefined variable "Sampler2"`。

### 3.4 附带发现：MTR 把异常 cause 吞了

Iris 的 `FakeChainedJsonException` 包装后，日志只剩 `Invalid shaders/core/xxx.json: `（冒号后面空白），
真正的编译器报错**看不见**。`DummyClassMixin` 补打 cause 链之后，3.2 / 3.3 两个错才一次暴露出来。
**没有这个探针，这两个坑只能靠猜。**

## 4. 现状与证据（2026-09-28 14:00）

- 着色器：`serving mtr:shaders/core/mmtr_vehicle_light.{json,vsh,fsh}` ✓，**无编译错误、无 "could not find sampler Sampler3/4" 警告**
  （后者是"我们的采样器真的进了链接后的程序"的硬证据）。
- 光场在跑：`slots=22~33/256 collecting=… collected=… tiles=…` ✓；光照更新包 → 标脏已接线。
- 性能：`MMTR-PERF 帧=345（69 FPS）帧最慢=39ms` ✓ 无 >150ms 停顿。
- 新增计数（同一静止场景，无车在画）：`optimizer draws/frame=2460.0 batches/frame=1.00`
  —— 与 notes/340 的"轨道占 ~1478 draws/帧"同量级（该场景装进来的轨道更多）。
  **A/B 对照（`-Dmmtr.lightfield.disabled=true` 同场景）待跑**。
- 平滑：已从"按方块取光"升级为**三线性插值**（8 个 tap 各自查自己的 section，跨 section 边界天然正确；
  网格边缘取不到就退回最近邻）。等价 Flywheel 的 `TRI_LINEAR`。

## 5. 还没做（下一轮）

1. **AO**：目标里要求"光与 AO"。需要给图集加一个"遮挡"通道（RG8UI：G = 该方块是否遮挡），
   并在片元里按**世界法线**（顶点侧用 `IViewRotMat` 反旋回去）做体素式角点 AO。
2. **半透明件（车窗）**：`rendertype_entity_translucent_cull.fsh` 根本不用光照贴图（只有顶点色 + 漫反射），
   要不要让玻璃也吃光场，是一个**观感取舍**，要先看一眼再说。
3. **A/B 与实机目测**：需要"同机位、开/关光场"两张截图对比（F2 截图落在 `run/screenshots/`，可以直接读图核对）。
4. **光照更新**：目前 `ClientPlayNetworkHandler.onLightUpdate` → 标脏整区块列。要不要再细到 section，看实测。

---

## 6. 第二轮：三线性 + **坐标系陷阱** + 自驱动诊断回路

### 6.1 陷阱 4（最贵的一个）：`ModelViewMat` 是"视图旋转"，不是"世界"

第一版把 `mmtrRelWorld = ModelViewMat * ModelMat * Position` 当成"相机相对世界坐标"。**错。**
MTR 传的 `ModelViewMat` 是 `RenderSystem.getModelViewMatrix()`，即**视图旋转**（平移由 `ModelMat` 承担），
所以那个式子算出来是**视图空间**坐标 —— 它跟着视角转。

**症状（用户原话）**："暗部随着视角变化，鼠标控制视角上移阴影就上移，并且阴影笼罩整个车身。"
原因就是这么直白：同一个像素的查表坐标随视角变化 ⇒ 每转一下视角就查到别的 section ⇒ 车身忽明忽暗。

**正确写法**（也是 MTR 自己打补丁后算雾用的式子）：

```glsl
vec4 viewSpace = ModelViewMat * ModelMat * vec4(Position, 1.0);   // 视图空间
vec3 cameraRelativeWorld = IViewRotMat * viewSpace.xyz;           // ← 相机相对世界坐标
vertexDistance = fog_distance(mat4(1.0), cameraRelativeWorld, FogShape);  // 与 MTR 的雾逐字一致
```

**教训**：`IViewRotMat`（视图旋转的逆）就是为了这一步存在的；原版雾的写法里已经给了标准答案，
应该一开始就照抄，而不是自己推。

### 6.2 语义修正：`max()` → **有光场就用光场**

第一版为了"绝不比现在更暗"写成 `color *= max(lightMapColor, fieldLight)`。这一步**把要的效果自己抹掉了**：
`max` 只能让像素变亮，而"车头在暗处、车尾在灯下"要的正是**变暗** ⇒ 车看起来和以前一样。

改成 Flywheel 的语义（`flw_light()` 成功就覆盖）：

```glsl
vec4 mmtrFinalLight() {
    // MTR 主动强制点亮的部件保持原样：CUTOUT_BRIGHT=0xF000F0 → UV2(240,240)；
    // MAX_LIGHT_INTERIOR=0xF000B0 → UV2(240,176)
    if (mmtrLightUv.x == 240 && (mmtrLightUv.y == 240 || mmtrLightUv.y == 176)) return lightMapColor;
    vec4 fieldLight = mmtrFieldLight();
    return fieldLight.a > 0.5 ? fieldLight : lightMapColor;   // 取不到光场才退回 per-draw
}
```
（`mmtrLightUv` 是顶点侧 `flat out ivec2 = UV2`，用来区分"这个部件是不是被 MTR 强制点亮的"。）

**教训**：安全网如果方向错了（只能变亮），它会正好挡住要修的那个方向。诊断要问"我要的现象有没有可能出现"，
而不是"会不会更糟"。

### 6.3 新工具：离线 GLSL 检查器（`sandbox/glslcheck/GlslCheck.java`）

GLSL 错误以前只能在游戏日志里看到（还被 Iris 抹掉 message），每次试错都要**重启客户端 ~4 分钟**。
这个 60 行的探针用游戏同一套 LWJGL，在不可见的 GL 3.2 core 上下文里编译 + 链接两个阶段，
自己展开 `#moj_import`（用 `sandbox/mc-shaders/include` 里从客户端 jar 抽出来的原版 include），
并把行号、uniform/attribute 位置全打出来。

第一次运行就抓到 `final vec4 fieldLight = ...`（`final` 是 Java 不是 GLSL）——**这一次就省下一次重启**。
用法：
```
java -cp "<runClient 的 classpath>;sandbox/glslcheck" GlslCheck <vsh> <fsh>
```

### 6.4 自驱动验证回路：自动截图 + 假色诊断

- `MainRenderer` 在优化渲染器画完之后调 `MmtrLightField.maybeCaptureScreenshot()`：
  每 2 秒把**当前帧**写进 `run/screenshots/`，共 N 张 ⇒ 我可以**直接读图**核对渲染结果，不必等人按 F2。
- 诊断开关放在 `run/mmtr-lightfield.properties`（**不是 `-D`**：dev 客户端由 gradle daemon 派生，
  早先启动的 daemon 不继承新的 `JAVA_TOOL_OPTIONS`，参数会被静默吞掉 —— 实测 14:12 那次一张截图都没有）：
  - `debug=true`：换上假色片元着色器（红=方块光 绿=天空光 灰=没数据 蓝=MTR 强制亮），存盘后按 F3+T 重载资源即可。
  - `screenshot=N`：自动截图张数（每 2 秒重读一次，改完立即生效）。
- 光照值的**硬证据**也进了日志：每采完一个 section 就打一行它的范围，例如
  `section(-9,3,112) block 0..0 avg 0.00 / sky 0..15 avg 7.58` ⇒ 天空光真的有梯度（不是一片零）。

### 6.5 证据：坏版本长什么样

`-Dmmtr.lightfield.screenshot=12` 抓到的那组图（坐标系修好之前）里，
**整列车是纯黑的**，而地形/轨道正常 —— 正是"阴影笼罩整个车身"。
对照 notes/340 的基线（同样场景车体是灰白偏亮），这一眼就能判定"视图空间查表"必然错。

### 6.6 这一轮的 draw 计数

同一静止场景（无车在画、轨道为主）：`optimizer draws/frame=2460.0 batches/frame=1.00`。
计数点分别是 `BatchManager$RenderCall#draw`（= 真正的 `glDrawElements`）与 `ShaderManager#setupShaderBatchState`（批次）。
光场只在**数据 + 着色器**侧动手，同一个 RenderCall 仍然只 draw 一次 ⇒ A/B 应当逐位相同（下一轮跑
`-Dmmtr.lightfield.disabled=true` 对照确认）。

---

## 7. 第三轮：两个"只有靠实测才能发现"的真根因

### 7.1 陷阱 5：`ModelViewMat` **就是单位矩阵** —— 不要乘 `IViewRotMat`

用户第二条症状："这个光场好像会随着玩家运动而移动。"

我先在 `beginFrame` 里读矩阵，看到 `ModelViewMat = 单位矩阵`、`IViewRotMat = 真实旋转`，
于是**推错了方向**：以为 `ModelViewMat` 是"视图旋转"，加了一次 `IViewRotMat *`。
改完以后症状变成"暗部随视角转、车全黑"。

把日志挪到**真正 draw 的那一刻**（`ShaderManager.setupShaderBatchState` 的 HEAD，MTR 就是在这里设 uniform）才拿到真相：

```
@draw ModelViewMat =  1 0 0 0 | 0 1 0 0 | 0 0 1 0 | 0 0 0 1      ← 单位矩阵
@draw IViewRotMat  = -0.021 -0.259 0.966 | ...                   ← 真实旋转
@draw IViewRotMat * mat3(ModelViewMat) = 同上（≠ 单位）
```

**结论**：几何的"相机相对世界变换"全部由 `ModelMat` 这个顶点属性承担，
MTR 把 `ModelViewMat` 设成单位矩阵、`ProjMat` 设成投影矩阵，
于是 `ProjMat * (ModelViewMat * ModelMat * Position) = ProjMat * (相机相对世界坐标)` ✓。
⇒ 光场查表必须用 `ModelViewMat * ModelMat * Position`**原样**；再乘一次 `IViewRotMat`
就是把已经是世界坐标的向量又转了一次 —— 查表坐标系跟着视角转，症状就是"光场跟着玩家/视角移动"。

**教训**：矩阵要**在它被设进去的那一刻**读（`setupShaderBatchState`），不要在世界渲染的别的阶段读；
"我读到的值"和"着色器用的值"可能根本不是一回事。

### 7.2 陷阱 6：`lutDirty` 只在"相机移动"分支里被消费 ⇒ 站着不动时 LUT 永远全零

原来的写法：

```java
if (camX != lastCamX || camY != lastCamY || camZ != lastCamZ) { rebuildLut(...); uploadLut(); }
```

但**槽位分配/释放同样会让 LUT 变脏**，而分配发生在 `ensureSlot()` 里 —— 玩家站着不动时相机不变，
于是 LUT 永远停留在创建时的全零状态：查表一律命中槽位 0（=没数据）→ 退回 per-draw 光。

这条不是猜的：加了 GPU 回读诊断之后一眼看到

```
GPU readback: atlas nonZero=4096 sum=983040 (cpu nonZero=4096) | lut nonZero=0/486 [] | slots=52
```

—— 图集**在 GPU 上**（4096 个非零字节，与 CPU 完全一致 ✓ 上传通路没问题），
而 LUT 零个非零项，同时 CPU 侧已经分配了 52 个槽位。
修法：`if (lutDirty || 相机变了) { rebuildLut(...); uploadLut(); }`。

### 7.3 这一轮新增的两个"不靠眼睛"的诊断

| 诊断 | 做法 | 能回答什么 |
|---|---|---|
| draw 时刻矩阵 | `ShaderManagerMixin` 在 `setupShaderBatchState` HEAD 读 `RenderSystem.getModelViewMatrix()` / `getInverseViewRotationMatrix()`，打 4×4 / 3×3 并算出乘积 | 着色器里的坐标到底是什么空间（是不是世界坐标） |
| GPU 纹理回读 | `glGetTexImage` 把图集/LUT 读回来统计非零纹素，与 CPU 侧对照（**必须等采过数据之后再读**，否则看到的只是"初始状态本来就空"） | "CPU 有数据但 GPU 全零"这类上传/对齐问题 |

两个都是**一次调用、零用户参与**，比"重启游戏 + 让人看画面猜"快一个数量级。

### 7.4 本轮结论

- 图集上传通路 ✓（GPU 与 CPU 逐字节一致）。
- 坐标口径 = `ModelViewMat * ModelMat * Position`（`ModelViewMat` 是单位矩阵，**不要**再乘 `IViewRotMat`）。
- LUT 上传时机 ✓（槽位一变就传）。
- 三线性 + 语义修正（有光场就用光场）已就位，等待**一次干净的实机目测**（这三个修正从没同时生效过）。

⚠️ 上面第 7.4 节的"坐标口径"结论**已被 §8 推翻**（当时只凭 `ModelViewMat` 是单位矩阵就下了结论）。
后来拿到 A/B 实测数据才发现旋转藏在 `ModelMat` 里 —— 见 §8.1。保留原文是为了留下"我错在哪一步"的记录。

---

## 8. 坐标口径定案 + 三个真 bug（2026-09-28 下午）

这一轮的转折点是：不再靠"看着不对"猜，而是**先把事实测出来**。

### 8.1 坐标口径：必须乘 `IViewRotMat`（这次有两条独立证据）

**证据 1：draw 时刻的矩阵本身。** 新增 mixin 在 `VertexAttributeState.apply()`（真正上传 per-draw 属性的那一刻）
读矩阵，每 5 秒窗口打一次：

```
本窗口 mat3(ModelViewMat) =   1.000 0.000 0.000 | 0.000 1.000 0.000 | 0.000 0.000 1.000     ← 单位矩阵
本窗口 IViewRotMat =   0.933 -0.056 0.354 | 0.000 0.988 0.156 | -0.359 -0.146 0.922          ← 真实旋转
```

**证据 2：把 `ModelMat` 的平移按两种口径解释成世界坐标，再和地形比对**（同一行日志里并排打出）：

```
draw #23: 平移=(-422.98, 31.39, -195.80)  |T|=471
   A 口径（只加相机位置）      世界=(-863.26, 99.01, 1615.64)   ← y≈99：天上什么都没有
   B 口径（再乘 IViewRotMat）  世界=(-906.24, 67.99, 1778.02)   ← y≈68：正好是轨道/车体高度
```

两条证据合起来只能是同一个解释：**相机旋转在 `ModelMat` 里**（MTR 的
`StoredMatrixTransformations.transform()` 是在**已经压入视图矩阵的 pose** 上做 `translate(世界 − 相机)`，
所以那个矩阵 = 相机旋转 × 平移），而 `ModelViewMat` 这个 uniform 确实是单位矩阵。
于是：

```glsl
vec4 viewSpace = ModelViewMat * ModelMat * vec4(Position, 1.0);   // 视空间
mmtrRelWorld  = IViewRotMat * viewSpace.xyz;                      // 世界相对坐标 ← 必须这一步
gl_Position   = ProjMat * viewSpace;                              // 与原版逐字一致，不动
```

**我在这一个二选一上错了两次**，两次的症状都记录在此，免得再来第三遍：

| 错误版本 | 症状 | 原因 |
|---|---|---|
| 乘 `IViewRotMat` 但 LUT 覆盖不全（§7） | "光场随着玩家/视角移动" | 当时以为是旋转错，其实是 LUT 全零/网格太小 ⇒ 一路退回 per-draw 光，看着像"跟着动" |
| **不乘** `IViewRotMat`（错把单位矩阵当成"不含旋转"） | 光场跟着视角整体旋转 | 旋转在 `ModelMat` 里，不乘就等于拿视空间坐标查世界表 |

教训：**"某个矩阵是单位矩阵"不等于"这个变换不需要做"** —— 得把候选口径都算出来，
拿"真实世界光照/地形高度"当裁判，而不是拿某一项观测当结论。

### 8.2 新增的诊断能力（这一轮效率提升的关键）

| 能力 | 实现 | 价值 |
|---|---|---|
| per-draw 真值 | 新 mixin `VertexAttributeStateMixin` 注入 `apply()`，用 `Utilities.store(matrix4f, buf)` 取 ModelMat 平移 + `lightmapUV`，并**同时**算出 A/B 两种口径的世界坐标、该处真实世界光照、光场采样值 | 坐标口径的判决性证据；顺带暴露"被登记的车都在 470 格外" |
| 去重 + 配额重置 | 同一对象一帧会画十几个材质组（矩阵完全相同）；按平移去重，并且每 5 秒窗口重置配额 | 早先 24 条日志全给了一件几百格外的物体，车一根都没记到 |
| 远程切换假色 shader | `run/mmtr-lightfield.properties` 里 `reload=<新值>` → 自动 `client.reloadResources()` | 不用再让用户按 F3+T；整个诊断循环都不需要人动手 |
| 一次性回读改成"看到非零才收手" | `verifyGpuTexturesOnce` 最多试 6 次，并把 CPU 侧非零数并排打出 | §7 那条 `lut nonZero=0` 其实是**误报**：一次性回读正好落在"相机附近还没有任何 section"的一帧 |

### 8.3 假色截图：光场数据第一次**确实进到了车厢的像素里**

15:05:43 的假色截图（`debug=true`，红=方块光/15、绿=天空光/15、灰=取不到、蓝=MTR 强制亮）：

- 车身大部分是**亮绿色** ⇒ 天空光 15、方块光 0 —— 光场**取到了**，而且是逐像素的；
- 下半部／裙板是**黑色** ⇒ (0,0)，即"槽位已分配、图块还没采到"或采样点落在实心方块里；
- 若干**红色斑块** ⇒ 那些位置的方块光 > 0。

在此之前，"光场按世界坐标逐像素取光"从未被证实过（三线性、坐标、覆盖三者从没同时正确）。
这张图是第一次拿到"逐像素"的硬证据。

### 8.4 由证据抓出来的三个真 bug

**(a) 网格太小 + 登记范围失控。** 日志 `cells=0 tracked=64`：64 个被登记的 section
**一个都不在网格内**。原因不是网格大小，而是"登记了什么"：MTR 的优化渲染器**不做距离剔除**，
会把 470 格外的车厢也交给 `requestCar` ⇒ 256 个图集槽位被远处的车占满，玩家身边那节车反而被挤掉。
修法两条：
- 网格 9×6×9（±4 section = ±64 格）→ **25×8×25（±12 section = ±192 格）**；
- `requestCar` **只登记与网格相交的 section**（`isSectionInsideGrid`），远处车厢不管。
  LUT 只有 25×200 = 5000 个纹素（R32UI，20 KB），放大网格的代价可以忽略。

**(b) 采集太慢 + 顺序不对。** `COLLECT_LAYERS_PER_FRAME` 4 → **16（一帧采完一整个 section）**，
并把 `collectSlice()` 从 FIFO 改成**离相机最近的 section 优先**。
假色图里那些**全黑的钢轨/车体**就是这个：一列车一次登记几十个 section，
FIFO 时"玩家正看着的那节车"排在几十个后面，长期停在"槽位已分配、图块还是零"的状态。

**(c) LUT 漏格。** 片元里方块坐标是 `floor(relWorld - 0.5)`（取方块中心），
那个 `-0.5` 会让"相机几乎正好压在 section 边界上"时算出 `relX - 1`；
原来只写 `relX / relX+1` 两格，于是漏格。改成每个 section 写 **-1..+1 共 27 格**。

顺带修掉一个诊断自身的 bug：`summarizeSection()` 的步长写成了 `i * 2`（只扫一半、还跨到别的 z 层），
所以之前那些 `block 0..7 avg 0.03` 的区间统计是错的。

### 8.5 仍未完成 / 待验证

- **AO**：目标要求里有"光与 AO"，目前只有光。需要额外一张遮蔽通道（R8UI 或 RG8UI 图集）+ 世界空间法线
  （法线要乘 `IViewRotMat` 转回世界，才能按真实朝向取遮蔽）。
- **MTR 强制亮的判别是个启发式**：现在用 `UV2 == (240,240)` 或 `(240,176)` 判定"这是 MTR 强制点亮的部件"，
  但 `(240,240)` 也可能是**真的**方块光 15 + 天空光 15（例如灯下）。要彻底分干净得从
  `MaterialProperties`（CUTOUT vs CUTOUT_BRIGHT）拿渲染层信息，而那两个层用的是同一个 program。
- **半透明件**（车窗）：原版 `rendertype_entity_translucent_cull.fsh` 根本不看光照贴图，光场对它无效。
- **draw call A/B**：计数器（`optimizer draws/frame`、`batches/frame`）已就位，还没做 `-Dmmtr.lightfield.disabled=true` 的对照。
- **采样点的语义**：假色图里车身下半部取到 (0,0)，说明"方块光照值"是按**采样点所在方块**取的；
  车体侧面贴在站台/道床旁边时，采样点会落进实心方块（光照 0）。这是"逐方块取光"的固有粒度问题，
  后续要么在采集时对实心方块做一次外扩（取邻域最大值），要么接受它。

---

## 9. 坐标口径的**逐位铁证** + 真正的"跟随视角"元凶（漫反射法线）

### 9.1 用 MTR 自己报出的车体位置当裁判

§8.1 的 A/B 比对还是"拿地形高度猜"。这一轮把它变成了**确定性判据**：`requestCar` 里的车体世界坐标
是 MTR 自己给的（`absoluteVehicleCarPositionAndRotation.position`），把它和 draw 时刻两种口径算出的
世界坐标直接对减：

```
15:14:13  最近登记车=(-1770.64, 74.00, 1387.50)
  draw #2: 平移=(21.75, -3.07, 3.09)
    A 口径世界=(-1770.18, 73.61, 1389.16)      ← y/z 都不对
    B 口径世界=(-1769.96, 74.00, 1387.50)      ← y=74.00、z=1387.50 与车**逐位相同**
15:14:18  最近登记车=(-1826.45, 74.00, 1387.50)
    B 口径世界=(-1825.49, 74.00, 1387.50)      ← 又一次逐位相同
```

**B（乘 `IViewRotMat`）两次都精确落在车体位置上，A 从不吻合** ⇒ 坐标口径定案。
同时这一轮的数据也证明光场的其它环节都对：

```
LUT: cells=405 tracked=15（网格 25x8x25；GPU 读回非零=224）
最近登记车=(-1826.45, 74.00, 1387.50) 车心光场采样=8/15 真实光照=3/15
```

—— LUT 有 405 格、GPU 上确实有数据、车心采样有值（8/15 vs 真实 3/15，偏亮是三线性取周围 8 格均值的
正常行为，与 Flywheel 一致）。

### 9.2 "光照跟随视角变化"的真凶：MTR 补丁的**视空间法线**

坐标修好之后，"跟着镜头转"依然存在 ⇒ 说明它不是光场造成的。查着色器只剩一处视角相关量：

```glsl
vec3 relativeNormal = normalize(mat3(ModelViewMat * ModelMat) * Normal);   // MTR 补丁：视空间法线
vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, relativeNormal, Color);
```

- `Light0_Direction` / `Light1_Direction` 是原版按**世界方向**设的（MTR 只是用
  `RenderSystem.setupShaderLights` 把它们原样塞进 program；原版实体着色器用的也是**未变换的 `Normal`**，
  所以原版漫反射与镜头方向无关）。
- MTR 的补丁把法线换成视空间 ⇒ **世界方向的光照向量点乘视空间法线**，
  镜头一转整车的漫反射就跟着变 —— 正是"暗部随视角变化""鼠标上移阴影就上移""阴影笼罩整个车身"。

修法（在我们自己的 vsh 里）：

```glsl
vec3 worldNormal = IViewRotMat * relativeNormal;
vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, worldNormal, Color);
```

**注意这条与光场无关**：只要用了 MTR 的优化渲染器，所有几何（含 3D 钢轨）都受它影响，
只是车厢大面积、看得清才被注意到。

> ⚠️ **上面这条 9.2 的结论是错的，已撤回**（保留原文作为"我错在哪"的记录）。
> 我当时的推理是"原版实体着色器用未变换的 `Normal`，所以光照方向是世界空间"——但**原版那个 `Normal`
> 属性本身就是视空间的**。反编译字节码才是确定答案：
>
> ```
> DiffuseLighting.enableForLevel(Matrix4f)              // 世界渲染时调用，传入相机旋转矩阵
>   → RenderSystem.setupLevelDiffuseLighting(dir0, dir1, mat4f)   // 把两个方向按相机旋转转进视空间
>     → RenderSystem.setShaderLights(viewSpaceDir0, viewSpaceDir1)
> ```
>
> ⇒ `Light0_Direction`/`Light1_Direction` 是**视空间**的，MTR 补丁的 `mat3(ModelViewMat * ModelMat) * Normal`
> 也是视空间的，**两者口径一致，原本就是对的**。我改成"世界法线"之后，变成"视空间方向 · 世界法线"，
> **反而制造了"镜头一转整车亮度就变"** —— 用户随后报的"还在"就是我这次改动造成的。
> 教训：光照方向属于哪个空间，必须去读原版是怎么设的（`DiffuseLighting`），不能靠"实体着色器没用
> 变换"这种间接推理。

### 9.3 流程上的一个大改进：着色器改动可以**不重启客户端**上线

着色器源码是 `PatchingResourceProviderMixin` 在资源重载时**从 mod 资源里现读**的，
所以：`gradlew :fabric:processResources`（约 40 秒，只拷贝资源）→ 把 `run/mmtr-lightfield.properties`
里的 `reload` 改成任意新值 → 客户端 2 秒内自动重载 ⇒ **改一行 GLSL 的验证周期从"重启 4 分钟 + 让人重进世界"
降到 15 秒，且用户完全不用动手**。配合 `sandbox/glslcheck` 的离线编译+链接，
GLSL 迭代现在是"改 → 离线编译 → 远程热重载 → 截图"。
（注意：**Java 代码改动仍然必须重启**，只有资源/着色器能这样热更。）

---

## 10. 真正的"玩家一动、光照就滑"元凶：**CPU 与着色器的格点不是同一个格点**

### 10.1 症状与定位

用户原话："**视角是不动了，但玩家移动时还会动**"。视角那部分已经被 §8.1 的 `IViewRotMat` 修掉，
剩下的这个只跟**位置**有关 —— 顺着"位置相关"去找，只有一处：

| | 用的格点 |
|---|---|
| CPU 采集（写图集） | **世界**方块：`tileLocal = worldBlock & 15` |
| 片元查表（读图集） | **相机相对**方块：`floor(mmtrRelWorld - 0.5) & 15` |

两者差着**相机位置的小数部分**，而且差值会随玩家走动在 0..15 之间连续扫过去。算一个具体例子
（相机 x = −314.9，世界方块 x = −320，它在自己 section 内的局部坐标是 0）：

```
CPU  : (-320) & 15                                    = 0
片元 : floor(-320 - (-314.9) - 0.5) & 15 = (-6) & 15   = 10     ← 差 10 格
```

所以车身上采到的**不是它所在方块的光，而是同一 section 里偏了 N 格的另一个方块的光**，
N 随玩家走动滑动 ⇒ "玩家移动时，光照图案在车身上滑动"。

**为什么 §9.1 那条"逐位吻合"的铁证没能发现它**：那条判据验证的是**section 格号**
（`cam + IViewRotMat*T` 是否等于车体位置），而 section 两侧的换算恰好是自洽的；
错的是 section **内部**的局部下标。判据的覆盖范围 = 它验证的东西，这点得记住。

### 10.2 修法：一切都用**绝对**坐标

- CPU：`cameraBlock = floor(相机位置)`；`gridOrigin = (cameraBlock >> 4) - OFFSET`（绝对 section）；
  世界 section → 格号 = `section - gridOrigin`（**一对一**，不再需要 ±1 邻域）；图块局部下标 = `worldBlock & 15`。
- 着色器：它只有相机相对坐标，所以需要"相机所在方块坐标"才能折回绝对坐标 ——
  **把它塞进 LUT 的元数据列**：LUT 纹理宽度 = `GRID_X + 1`，最后一列的 0/1/2 行存
  `相机方块坐标 + 0x800000`（R32UI 是无符号纹理，加偏置避开负数）。
  这样不需要任何新 uniform、也不需要碰 MTR 的 uniform 设置流程（`ShaderManager` 没暴露 program，
  本来还得再写一层 mixin 才能设自定义 uniform）。
- 着色器里：`exact = mmtrRelWorld + camBlock - 0.5`，`base = floor(exact)`，
  格号 = `(base >> 4) - gridOrigin`，局部 = `base & 15` —— 与 CPU 逐字一致。

代价：着色器用的是 `floor(相机位置)`（整数），所以绝对坐标有 **<1 格** 的偏差；
采样方块偶尔会取到相邻方块（两者光照差别本来就 ≤1 格），**没有可见影响**，而且不再随移动滑动。

顺带的好处：LUT 不再依赖相机位置的小数部分 ⇒ 重建频率从"每帧"降到"跨 section / 槽位变化时"，
相机坐标元数据则按"跨方块"刷新（走路大约每秒 3 次）。

### 10.3 还没完：**整数**相机坐标残留 frac，周期正好是 1 格

§10.2 上线后用户反馈："滑，但是滑一定距离后重置到正确位置，然后继续滑继续重置" ——
**"周期性重置"是周期性误差的签名**，而这一版里周期性的来源只剩一个：

```glsl
exact = (世界坐标 − 相机位置) + floor(相机位置) − 0.5 = 世界坐标 − frac(相机位置) − 0.5
```

`frac(相机位置)` 每走一格扫一个周期 ⇒ 方块下标会翻一次、三线性插值的相位也一起漂 ⇒
**图案随走动漂移，走满一格"重置"一次，如此循环**。我原来以为"<1 格偏差没有可见影响"是错的：
光场虽然逐方块取值，但在灯下/明暗交界处，1 格的位置差就能让采样值明显变化。

修法：元数据列传**精确**相机位置 = 整数部分（行 0/1/2）+ 小数部分 × 65536（行 3/4/5，定点）。
这样 `exact = relWorld + camPos − 0.5` **正好**等于"世界坐标 − 0.5"，与 CPU 逐位一致、零残余漂移。
精度 1/65536 格。

**踩到的第二个坑**：本来想用 float 位模式（`floatBitsToUint` / `intBitsToFloat`）传精确值，
**GLSL 1.50 里没有这两个函数**（MC 用 `#version 150`），离线编译器直接报
`'intBitsToFloat' : function is not known`。定点整数编码不依赖任何位转换，最稳。
—— 这个坑是 `sandbox/glslcheck` 在**进游戏之前**拦下来的，否则又是一次"重启 4 分钟才发现 shader 编译失败"。

**流程教训（这一轮反复最多的地方）**：生效链条是
`源码 → processResources（拷资源）→ Java 则还要重编译+重启 → 资源重载（reload nonce）`。
中间少任何一环，就会拿"新 Java + 旧着色器"混搭的版本去判断，结论必然是错的 ——
而且它表现得**很像**一个真 bug。现在每改完都会核对
`build/resources/main/**/*.fsh` 的时间戳与内容标记，再决定是否需要重启。

### 10.4 真正的元凶：**相机基准取错了**（MTR 的渲染偏移比真相机滞后 0~1 格）

用户描述最终形态："**柔和的滑**，我按 A 向左走，光照向左柔和滑，停下后柔和地回到正确的位置"。

"随移动方向柔和滑动 + 停下回位" = **速度相关的偏移**，即采样点被整体平移了一个
`基准差 = 光场用的相机 − 顶点的 ModelMat 用的相机`。读源码找到了：

```java
// MainRenderer：车辆平移用的是"渲染用假实体" EntityRendering 的插值位置
render(graphicsHolder, entityRendering.getCameraPosVec2(tickDelta));

// EntityRendering：这个假实体每 tick 只在"离相机 >1 格"时才被传送到相机位置
if (skipDistanceCheck || position.squaredDistanceTo(getPos2()) > 1 || MinecraftClient.getInstance().isPaused()) {
    setPosition2(position.getXMapped(), position.getYMapped(), position.getZMapped());
}
```

顶点的 `graphicsHolder` 来自 **vanilla 实体渲染**，那一层的姿势是
`translate(实体插值位置 − camera.getPos()) * 相机旋转` —— 用的是**真相机**。于是：

```
ModelMat 平移 = (假实体插值位置 − 真相机) + (车世界坐标 − 假实体插值位置) = 车世界坐标 − 真相机
```

⇒ **ModelMat 的参考系是真相机，而光场的 LUT/元数据用的是那个滞后 0~1 格的假实体位置**。
滞后量随走动锯齿变化（走 ~5 tick 才超过 1 格触发一次传送），正好表现为"柔和地滑、停下回位"。

**这也是 §9.1 那条"逐位吻合"里被我解释掉的那个 0.68 格**：当时 y/z 严丝合缝、只有 x 差 0.68 格，
我把它当成了"转向架偏移"—— 其实那是滞后量，而用户当时正沿 x 方向走路。
**教训：判据出现"只有一个轴对不上"时，不要急着找一个能解释它的物理理由，先想想有没有一个
速度/方向相关的量。** 证据里出现的任何残差都要有解释，用"大概是别的因素"糊过去就是在埋雷。

修法：`beginFrame` 改用真相机 `client.gameRenderer.getCamera().getPos()`
（原版 `MinecraftClient`，不是 MTR 的 holder，所以走原版字段），并把
`|mtrOffset − 真相机|` 每 5 秒打一次（本窗口最大值 + 当前值）作为**可观测证据**：
改对之后这个滞后仍然存在（它是 MTR 的行为），但它**不再影响光场**。

改完后的日志正是这条判据的读数：

```
[MMTR-LIGHT] 相机基准滞后：本窗口最大 |mtrOffset − 真相机| = 0.71 格（当前 0.21）
[MMTR-LIGHT] LUT: cells=26 tracked=26（网格 25x8x25，**绝对** section；GPU 读回非零=32）
```

—— 滞后确实存在（走动时 0.2~0.7 格，停下回到 0.00），而光场再不受它影响。
`GPU 读回非零=32 = 26 个格号 + 6 个元数据纹素`，正好对上（内部一致性自检）。

**用户确认："OK 完美了"** —— 车厢各部分光照随其真实世界位置变化、不随视角、不随走动滑动。

---

## 11. 附带的两个工程问题（这一轮踩到的）

### 11.1 原生崩溃：`GL_UNPACK_ROW_LENGTH` 没摆正

15:51 客户端在世界里崩掉，退出码 `-1073741819`（`0xC0000005` 访问越界），**没有 Java 崩溃报告**
⇒ 是 GL/驱动层的原生崩溃。我加的 LUT 上传（每帧 20 KB）是最可疑的新增 GL 工作：
`glTexSubImage2D` 按"紧凑排布"读缓冲区，但 `GL_UNPACK_ROW_LENGTH` 是**全局状态**，
别的渲染代码（Sodium 传 mipmap 就会用）可能把它留在别的值上 —— 那驱动会按那个行宽往外读，
直接越界。修法：所有上传/回读前显式摆正 `UNPACK/PACK` 的 `ALIGNMENT / ROW_LENGTH / SKIP_ROWS / SKIP_PIXELS`
（`prepareUnpackState` / `preparePackState`）。同时把自动截图先关掉（它是另一个原生崩溃疑点，
需要时再临时打开）。此后未再崩溃。

### 11.2 生效链条（这一轮反复最多的地方，值得单列）

`源码 → processResources（拷资源）→ Java 还要重编译+重启 → 资源重载（reload nonce）`

中间少任何一环，就会拿"新 Java + 旧着色器"混搭的版本去判断，而它表现得**很像一个真 bug**
（§10.3 那次"还在滑"就是这个）。现在每次改完都核对
`build/resources/main/**/*.fsh` 的时间戳与内容标记，再决定要不要重启。

---

## 12. 仍未完成

- **AO**：目标里要求"光与 AO"，目前**只有光**。需要额外的遮蔽通道（图集加一维或另开 R8UI）
  + 世界空间法线（法线要乘 `IViewRotMat` 才能按真实朝向取遮蔽）。
- **MTR 强制亮的判别是启发式**：用 `UV2 == (240,240)`/`(240,176)` 判"强制点亮的部件"，
  但 `(240,240)` 也可能是真的方块光 15 + 天空光 15。要彻底分干净得拿 `MaterialProperties`
  的渲染层（CUTOUT vs CUTOUT_BRIGHT），而两者共用同一个 program。
- **半透明件（车窗）**：原版 `rendertype_entity_translucent_cull.fsh` 根本不看光照贴图，光场对它无效。
- **draw call 对照**：`optimizer draws/frame`、`batches/frame` 计数器已就位，
  但还没做 `-Dmmtr.lightfield.disabled=true` 的 A/B（设计上光场不动顶点格式、不拆批次，
  所以预期 draw call 不变，但"预期"不算证据）。
- **采样点的粒度**：光场是逐方块取光，车体侧面紧贴站台/道床时采样点会落进实心方块（光照 0）。

---

## 13. 钢轨不参与光场（按批次换采样器）

**决定**（用户 2026-09-28）：钢轨**完全不参与**光场，保持 MTR 原来的 per-draw 光。

**为什么**：光场替换的是 **program**（`rendertype_entity_cutout`），而 MTR 的 3D 钢轨和车厢走的是
**同一套优化渲染器**（`RenderRails` → `ModelPropertiesPart` → `OptimizedModel` → `BatchManager`），
所以钢轨本来也被光场接管了（假色图里变绿的就是钢轨）。但光场只覆盖"车辆登记过的 section"，
于是车附近的钢轨走光场、远处走原版 ⇒ 长轨道上会出现一条**网格边界亮度台阶**。
钢轨是固定几何、MTR 本来给的就是"每段一个常量"（`RenderRails` 在段中点采一次光），
与其让它半参与，不如整类排除、行为可预测。

**做法（不需要改一行 GLSL）**：MTR 在 `ShaderManager.setupShaderBatchState(materialProperties)`
**内部**从 `RenderSystem.getShaderTexture(i)` 取 Sampler0..7 并 `addSampler`，所以在该方法
**HEAD** 处换掉 Sampler3 就会被它读到：

```java
RenderSystem.setShaderTexture(3, isRail ? dummyLutTex : lutTex);
```

钢轨批次绑一张**全零 LUT**（尺寸与真 LUT 一致 —— `texelFetch` 越界是未定义值，不能拿 1×1 糊），
查表一律命中槽位 0（=该 section 没数据）⇒ `mmtrFieldLight()` 返回"无效" ⇒ 自动退回 `lightMapColor`
（MTR 原来的 per-draw 光）。**批次划分与 draw call 完全不变。**

**怎么认出钢轨**：内建 3D 钢轨来自 `mtr_custom_resources.json` 的
`"modelResource": "mtr:models/rail/rail.obj"` / `rail_siding.obj`，贴图与模型同目录
（`mtr:models/rail/rail.png`）。判据放宽到"路径含 `/rail/`"或结尾 `/rail.png`、`/rail_siding.png`，
并把**第一次**判成钢轨的贴图打进日志（宁可先看见，不要先猜）。每 5 秒的统计行里带
`批次：钢轨=N（不参与光场）车辆=M`，一眼能看出分类是否合理。
局限：资源包自定义的 3D 钢轨若把贴图放在别处，判据认不出（会继续参与光场）。

**实测确认**（16:01，重启后进世界）：

```
[MMTR-LIGHT] 钢轨批次（不参与光场）：贴图 mtr:models/rail/rail.png      ← 分类正确，贴图名与预期一致
[MMTR-LIGHT] 相机基准滞后：… | 批次：钢轨=577（不参与光场）车辆=3462      ← 每 5 秒（约 120 fps）
```

按帧换算：钢轨 ≈ 1 批/帧、车辆 ≈ 6 批/帧，与 `batches/frame≈7` 对得上，
说明"按批次分派采样器"确实生效、且没有把任何车辆贴图误判成钢轨。

---

## 14. 第四轮：世界 AO（复刻 Flywheel 的 SMOOTH 档）

日期：2026-09-28 晚 · 承接 §12「AO 还没做」

### 14.1 复刻对象：Flywheel 的 `SMOOTH` 到底算什么

`sandbox/flywheel/Flywheel-1.20.1-dev/.../internal/light_lut.glsl`，三档里默认的 `SMOOTH(2)`：

| 步骤 | Flywheel | 我们 |
|---|---|---|
| 遮蔽数据 | 每节 18³ 的**实心位图**（`BitSet`，5832 bit）+ 逐方块 4+4 bit 光 | 光图集照旧；**另加**一张 R8UI 实心位图，**1 bit/方块**，一行一个 section（512×256 = 128 KB） |
| 实心判据 | （采集侧）方块是否遮挡 | `BlockState.isOpaqueFullCube(world,pos)` —— 与原版 AO 计算器**同一个**判据（反编译 `BlockModelRenderer$AmbientOcclusionCalculator` 确认） |
| 取光 | 3×3×3（27 次）+ 实心掩码 | 照旧只取三线性那 8 个 tap（**不动已验证的取光**），实心掩码另取 27 次 |
| AO | 每个"角"取该方向平面上周围 4 个方块，`AO = 1 − 0.2 × (4 − validCount)`；8 角三线性；三轴按 n² 加权；逐片元 | 完全同构（`mmtrCornerAo` 直接写成 `1 − 0.2 × 实心数`，与它的 validCount 公式等价） |
| 短路 | 3×3×3 全实心 → 直接 0.2 | 同（`solidMask == 0x7FFFFFF`） |
| 内面修正 | `_FLW_INNER_FACE_CORRECTION`（非默认档） | 未实现（与默认档一致） |

**与 Flywheel 的一处有意偏差**：它的"方向取光"会把 4 个方块的光**按 validCount 平均**（等于顺带排除实心方块里的 0 光）；我们只加 AO、不动取光 —— 因为取光那一套是用户已经确认"完美"的（§10–11），不在这轮改动里冒险。

### 14.2 落地要点（都在代码注释里写死了）

- **世界法线**：顶点阶段 `mmtrWorldNormal = IViewRotMat * relativeNormal`。它和漫反射用的**视空间**法线不是一回事：漫反射方向（`Light0/1_Direction`）是视空间的，AO 用的是世界方块的属性。§9.2 那次把漫反射改成世界法线导致"镜头一转亮度就变"，两者混用会重演，注释里显式警告。
- **格点**：与光场同一个 `floor(世界坐标 − 0.5)`（方块中心约定）。代价：AO 图案相对真实方块边界有**半格相位**（视觉上是软过渡，可接受，先记一笔）。
- **开关**：AO 强度写进 LUT 元数据列第 6 行（`GRID_X + 6*LUT_W` = 索引 181）。着色器读到 0 就**连那 27 次取位都不做** ⇒ 它天然就是"AO 的性能代价"的 A/B 开关，不需要重启、不需要两份着色器。
- **共享 include**：新增 `assets/minecraft/shaders/include/mmtr_lightfield.glsl`，生产 fsh 与假色 fsh 都 `#moj_import <mmtr_lightfield.glsl>`，一份实现两个消费者，不会再各自跑偏。
  - 命名空间坑：反编译 `ShaderProgram$1.loadImport` 确认原版就是 `new Identifier("shaders/include/" + name)`（单参构造 ⇒ 命名空间恒为 `minecraft`），所以文件必须放 `assets/**minecraft**/shaders/include/`，而不是 `mtr`。
  - 离线探针 `sandbox/glslcheck/GlslCheck.java` 改成多目录（`-Dmmtr.includes="a;b"`），并加了 `check.ps1` 一键跑两对；`Sampler5` 有效位置 10。

### 14.3 证据（2026-09-28 16:25–16:40，实机）

1. **数据到 GPU**（一次性回读，glError=0）：
   `light field online: atlasTex=134 lutTex=135 solidTex=136 samplers=3/4/5`
   `GPU readback: atlas nonZero=2393 … | 实心位图 非零字节=238 实心方块=1693 | lut nonZero=40/5200 …`
   LUT 读回里能看到元数据：`… 1000@181`（181 = 25 + 6×26 = AO 强度行，值 1000 = 强度 1.0）。
2. **逐像素生效**（假色着色器，蓝通道 = AO；车身上取垂直/网格采样，反解回 方块光/天空光/AO）：
   上层开阔处 `11–12/15, AO=1.00`；往平台/地面方向**平滑**降到 `AO=0.66–0.78`（方块光同时 11→8、天空光 15→11）。
   ⇒ 既证明 AO 进了像素，也证明"开阔处不误伤"。
3. **性能 A/B**（同一机位；`draws/frame` 恒为 3046.0、`batches/frame` 恒为 7.00）：

| AO | 各 5 秒窗口平均帧时（ms） |
|---|---|
| 开 | 8.87 / 8.82 / 8.94 / 8.95 / 8.85 / 8.86 / 8.81 / 8.76 / 8.84 |
| 关 | 8.81 / 8.77 / 8.92 / 8.81 / 8.85 / 8.88 |

   差异 **< 0.1 ms（噪声内）**。⚠️ 注意这个机位是 **draw call 主导**（3046 draws/frame，帧时几乎被 CPU 提交吃满），
   所以这个数字说明的是"AO 不额外花 CPU、且在这台机器的 GPU 余量里"；GPU 受限机位（车占满屏）的代价还没量到。
4. **draw 数**：AO 开/关两态 `draws/frame`、`batches/frame` **逐位相同**（都在上表里）。
5. **"不增加 draw call"的正式对照**（2026-09-28 16:45，同一**静止**机位，`enabled` 运行时开关）：

| 状态 | draws/frame（4 个 5 秒窗口） | batches/frame | 平均帧时（ms） |
|---|---|---|---|
| `enabled=false`（光场不介入，MTR 原 per-draw 光） | 2478.0 / 2478.0 / 2478.0 / 2478.0 | 7.00 ×4 | 9.23 / 9.10 / 9.15 / 9.22 |
| `enabled=true`（光场 + AO 生效） | 2478.0 / 2478.0 / 2478.0 / 2478.0 | 7.00 ×4 | 8.92 / 8.99 / 9.07 / 9.21 |

   ⇒ **draw call 与批次划分逐位不变**，帧时差异在同一噪声带内（光场这一侧甚至略低）。
   这一条是要求里点名的："按世界坐标取光**且不增加 draw call**"。
   （机制上也说得通：光场只改 `RenderSystem.setShaderTexture(3/4/5)` 与两次纹理上传，
   完全不碰 `BatchManager` / `OptimizedModel` / VBO / 顶点格式 / `RenderCall` 的划分。）

### 14.4 这轮踩的两个坑（都不是算法问题）

1. **光影包开关会让 MTR 的 program 变陈旧** —— 用户报"现在开不开光影又都是老式的了"的**真正原因**。
   Iris 在跑时 `Utilities.canUseCustomShader() = noShaderPackInUse()` 为 false，我们按设计不换着色器；
   但用户一关光影包，MTR 手里那份 program 还是"无光场"的（它只在**资源重载**时重新请求）。
   日志铁证：16:30:29 `(Iris) Using shaderpack: ComplementaryReimagined_r5.9.3.zip` → 每 5 秒的 `[MMTR-LIGHT]` 统计
   正好在那一秒停；16:35 关包后统计继续、但画面仍是老式的，直到 **16:35:18 手动触发一次资源重载**（serving 又出现）才恢复。
   ⇒ 现在加了看门狗 `reloadIfShaderInputsChanged()`：`canUseCustomShader()` 由 false 变 true、或 `enabled`/`reload` 变化，
   自动重载一次资源。**光影包开着时，光场按设计就是不介入的**（MTR 走回原生 RenderLayer），这两者互斥。
2. **截图计数器只增不减**：properties 里把 `screenshot` 改小就再也截不到图（`screenshotsTaken >= count` 永久成立）。
   现在"改了 N 就从 0 重新记数"。
   附带发现：客户端窗口失焦/最小化时 MC 停止渲染，每 5 秒的统计行会出现 30–60 秒的空洞 —— 我一度当 bug 查。

### 14.5 仍未完成 / 局限

- AO 是**世界方块的**遮蔽，**不是物体自遮挡**：车体自己的窗框不会给车内打阴影（与 Flywheel 一致，见 notes/343 §2）。
- 采样格点的半格相位（见 14.2）。
- MTR"强制点亮"的启发式（`UV2 == (240,240)/(240,176)`）那些部件不做 AO。
- 半透明件（车窗）走 `rendertype_entity_translucent_cull`，原版 fsh 根本不看光照贴图 ⇒ 光场/AO 对它们无效。
- **"不增加 draw call"的正式对照**（`enabled=false` 同机位 A/B）—— 已跑，见 14.3 第 5 条。
- GPU 受限机位下的 AO 代价未量（本机这个场景 2400~3000 draws/frame，是 draw call 主导）。

---

## 15. 光影包开着时要怎么办（架构调查 + 两条路）

日期：2026-09-28 晚 · 用户要求："现在主要是做光影下的实现"

### 15.1 事实：光影下 MTR 的车厢不是我们的 program 画的

反汇编 `ShaderManager.setupShaderBatchState(MaterialProperties)`（MTR 二进制库）：

```
0: Utilities.canUseCustomShader()                       // = ModShaderHandler…noShaderPackInUse() && !isGl4ES
3: ifeq 33
   // true（无光影包）：用 MTR 自己那份 program（我们提供的 mmtr_vehicle_light）
   program = shaders.get(getShaderName(shaderType)); materialProperties.setupCompositeState();
33: // false（有光影包）：
   materialProperties.getBlazeRenderType().startDrawing();   // 原版 RenderLayer → Iris 换成 gbuffers_entities
   program = RenderSystem.getShader();
44: …对 program 设 ModelViewMat/ProjMat/IViewRotMat/雾/采样器 0..7，最后 program.bind()
```

而光照值是 **每 draw 一个常量顶点属性**：`VertexAttributeState.apply()` 里
`glVertexAttribI2i(LIGHTMAP_UV.location, lightmapUV, lightmapUV >> 16)`（同一分支里 `canUseCustomShader()` 为 false 时
**不**上传 `MATRIX_MODEL`）。⇒ 光影包拿到的是"这一段一个光值"，所以用户看到的"老式的"就是这个，
**不是**光场坏了（这也解释了为什么开着光影时 `[MMTR-LIGHT]` 统计里 `canUseCustomShader=false`、光场整段不介入）。

### 15.2 两条可行路线

| 路线 | 做法 | 得到什么 | 代价 / 风险 |
|---|---|---|---|
| **A. 逼 MTR 用自己的 program** | 对 MTR 谎报"没有光影包"（`Utilities.canUseCustomShader()` 返回 true）—— 已实现为 `underShaderpack=true`（`UtilitiesShaderPackMixin`） | 逐像素光场 + 世界 AO，与无光影时**完全同一套**着色器 | 车厢不再由包的 `gbuffers_entities` 画 ⇒ 不写包的 gbuffer 布局（只有 `gl_FragData[0]`），拿不到包那套材质/反射/AO 后期；包的延迟合成阶段可能对车的像素处理异常 |
| **B. 往包的着色器里注入取光** | 在 Iris 编译前改包源码：把 `gbuffers_entities` 片段里的 `vec2 lmCoordM = lmCoord;` 换成我们逐像素采到的 (方块光, 天空光)，之后包的 `DoLighting(...)` 用它自己的光照模型算 | 包的全部效果都保留，车的光照变成逐像素、跟随世界位置 | 要动 Iris 的着色器加载路径（找注入点 + 文本改写），并且要和包的采样器**抢 GL 纹理单元**（Iris `ProgramSamplers$Builder` 用 `reservedTextureUnits` 从低到高分配，而 MTR 只会绑 `Sampler0..7` ⇒ 我们的 Sampler3/4/5 很可能和包的 colortex/shadowtex 撞车）；按包适配、脆弱 |

**共同的好消息**：两件事都已经在正确的位置——
- MTR 对**任何** program 都会在 `setupShaderBatchState` 里 `addSampler("Sampler" + i, RenderSystem.getShaderTexture(i))`（i = 0..7，反汇编确认 `bipush 8` 的循环），所以包的程序也能拿到我们绑的 Sampler3/4/5；
- 包的实体片段里 `playerPos`（相机相对世界坐标）与 `worldGeoNormal`（世界法线）都是现成的局部变量，注入点的上下文齐了。

### 15.3 已验证的架构事实（可复现）

- ❌ 不是"光影下 MTR 走原生 RenderLayer 就不批次了"：`setupShaderBatchState` **照样**被调用（实证：光影开着时日志里仍有 `钢轨批次（不参与光场）`，
  那是 `ShaderManagerMixin` 在方法 HEAD 处打的），变的只是 program 从哪来。
- ❌ 我们不可能"只换 program 不换光照"：光照走顶点属性（`VertexAttributeState.apply()`），program 一换、属性口径也跟着换
  （`MATRIX_MODEL` 的同样受 `canUseCustomShader()` 控制）。

**状态**：路线 A 已实现并待实测（`underShaderpack=true` + 光影包开）。

### 15.4 路线 A 已证伪（实测，2026-09-28 17:24）

`underShaderpack=true`（对 MTR 谎报"没有光影包"，逼它用自己的 program）⇒ **整屏黑**。
日志时间线：17:24:23 谎报成功（`canUseCustomShader 初始=true`）→ 17:24:24 光场数据上线 → 17:24:26 我们的着色器被 serving
→ 17:24:38 用户手动关掉光影包自救（`Shaders are disabled because enableShaders is set to false`）→ 17:25:59 恢复正常。

**机制**（不是我们的着色器写错）：MTR 绑自己的 program 就绕过了 Iris 的 `RenderLayer.startDrawing()`，而它随后那圈
`addSampler("Sampler0..7", …)` 会把 GL 纹理单元 0–7 绑成我们的贴图 —— 那正是 Iris 分给 colortex/shadowtex 的单元；
Iris 又用缓存认为"已经绑好了、不必重绑"，于是它后面每一趟 pass 都在采样我们的 LUT ⇒ 黑屏。
**结论：与 Iris 的状态机抢所有权这条路不通。** 相关代码已删除（`UtilitiesShaderPackMixin` + `underShaderpack` 开关），
避免以后再踩。

---

## 16. 第五轮：光影包下的实现（与包无关的顶点阶段注入）

日期：2026-09-28 晚 · 用户约束："不能特配某个光影包"、"只在 mod 自身改"

### 16.1 为什么是"顶点阶段 + 标准通道"（有先例）

- **与包无关的通道只有一个**：Iris 对所有包都保证的标准顶点接口 —— `gl_Vertex / gl_Normal / gl_NormalMatrix /
  gl_ModelViewMatrix / gbufferModelViewInverse` 与光照贴图顶点属性 `vaUV2`（旧名 `gl_MultiTexCoord1`）。
  在顶点阶段按世界坐标采一次光场、写回光照通道本身，包后面怎么算光都不管 ⇒ 不认任何包的变量名。
- **先例**：Flywheel 自己就是这条路 —— `Backends.INSTANCING/INDIRECT` 的 `supported()` 里写着
  `&& !ShadersModHelper.isShaderPackInUse()`（[Backends.java:22](../../sandbox/flywheel/Flywheel-1.20.1-dev/common/src/backend/java/dev/engine_room/flywheel/backend/Backends.java#L22)），
  有光影包就回退到 `flywheel:off`（不渲染），由 Create 用
  `SuperByteBuffer.useLevelLight(level, worldMatrix)` 逐顶点按世界坐标取光
  （[ContraptionEntityRenderer.java:128](../../sandbox/flywheel/Create-mc1.20.1-dev/src/main/java/com/simibubi/create/content/contraptions/render/ContraptionEntityRenderer.java#L128)）。
  ⇒ **"光影下逐顶点"是生态里的标准答案**，我们照做；无光影时仍是我们的逐片元光场（比 Flywheel 更进一步）。

### 16.2 已建成的东西（都在 mod 自身）

| 文件 | 作用 | 状态 |
|---|---|---|
| `assets/minecraft/shaders/include/mmtr_lightfield.glsl` | 共享核心（改成"位置当参数 + 采样器当宏"），无光影/光影两条路共用同一套取光数学 | ✅ 离线编译通过 |
| `.../mmtr_lightfield_mtr.glsl` | 我们自己的 program 那一侧（Sampler3/4/5 + `mmtrFinalLight`） | ✅ 离线编译通过 |
| `.../mmtr_lightfield_pack.glsl` | **注入进包顶点阶段的文本**：算世界坐标/法线 → `mmtrSampleField` → 写回 `vaUV2`；`mmtrOriginalUv2()` 在影子宏之前捕获原值，取不到数据时保持原样；`mmtrPackEncode()` 复刻 MTR 的 `pack()`+`exchangeLightmapUVBits()` 编码（逐位一致） | ✅ 离线编译通过（合成顶点着色器） |
| `MmtrShaderPackLightField.java` | 拼接注入文本（骨架 + 核心）、定位顶点 `void main()`、两条 `#define`、插入调用；找不到锚点/包自己声明了 `vaUV2` 就**不注入**并记日志 | ✅ 已构建 |
| `org/mtr/mixin/iris/IncludeProcessorMixin.java` + `mtr.iris.mixins.json`（`required:false`） | 挂 Iris 的 `IncludeProcessor.getIncludedFile`（include 展开后的源码出口） | ⚠️ **钩子没落上**（见 16.3） |
| `MmtrLightField` | 有光影包时：数据照旧上传；**不许**碰 `Sampler3/4/5`（那是 Iris 的单元，实测会黑屏）；改由 `bindPackSamplers()` 在 program bind 之后把 `mmtrLut/mmtrAtlas/mmtrSolid` 指到**运行时枚举出来的空闲单元** | ✅ 已构建、在跑 |

**离线检查器升级**（`sandbox/glslcheck/`）：`expand()` 改成递归（我们的 include 里还有 include）；新增
`check.ps1 -Extra` 可以对**注入进包的合成顶点着色器**做语法检查 —— 这一轮它当场抓到两个真错误：
① 注入段里又用了 GLSL 保留字 `packed`；② `#define gl_Color (...)` 会和 Iris 已定义的 `gl_Color` 撞成
"Macro gl_Color re-defined"（所以 AO 那一步（乘顶点颜色）单独留到下一步，先只做光）。

### 16.3 当前卡点（要接着做的第一件事）

光影包开着时的实测：`光影包在跑 … 注入程序数=0/钩子调用=0`（18:05:46）—— 即
**`IncludeProcessorMixin` 一次都没被调用**，所以注入没有发生（采样器日志也印证：program 里没有我们的 uniform）。
游戏本身一切正常（包照旧渲染、无黑屏、无报错），只是"光影下逐顶点光"这最后一公里还没接上。

两个候选方向（按优先级）：

1. **确认 mixin 到底有没有应用**：`mtr.iris.mixins.json` 是 `required:false` + `defaultRequire:0`，
   失败是静默的。开 `-Dmixin.debug=true`（或 `mixin.debug.verbose`）看启动日志里有没有这条 mixin 的 applying/失败信息；
   另外确认 `build/resources/main/mtr.iris.mixins.json` 与 `fabric.mod.json` 里那一条都到位了（构建产物里已确认存在）。
2. **换挂点**：Iris 1.7 里 `IncludeProcessor` 可能只服务老式 `shaders/` 路径，主程序装配走
   `ProgramSet`/`ProgramSource`。更稳的候选是 `ShaderCreator$IrisProgramResourceFactory`（Iris 把**装配好的字符串源码**
   经一个 `ResourceFactory` 交给原版 `ShaderProgram` 构造）——甚至可以挂**原版** `net.minecraft.client.gl.ShaderProgram`
   的构造、包一层 ResourceFactory 来改写源码（这样连 Iris 的类都不用碰，只依赖原版签名，对 Iris 版本更稳）。

### 16.4 诚实的状态

- 无光影包：逐片元光场 + AO，**已验收**（§14）。
- 有光影包：数据链路已通，注入文本已离线验证，**但注入钩子还没落上** ⇒ 车厢仍是每 draw 一个光值（现状不变）。
- 失败是安全的：所有注入路径都做了"锚点找不到就不注入"，不会把包改坏、不会黑屏。

## 17. 为什么 `钩子调用=0`：三个缺陷（全部有源码/字节码证据）

查询目标是"光影下逐顶点取光"这最后一公里为什么没接上。结论：**不是一个 bug，是三个叠在一起**，
而且第 2、3 个会在修好第 1 个之后立刻暴露（第 2 个就是之前"车辆不显示 / 全黑"的形态）。

### 17.1 缺陷 1：mixin 配置根本没注册（`钩子调用=0` 的直接原因）

- `mmtr/game/fabric/src/main/resources/fabric.mod.json`（以及 `build/resources/main/` 那份产物）
  的 `mixins` 数组只有 `mtr.mixins.json` 与 `mtr.library.mixins.json`，**没有** `mtr.iris.mixins.json`。
- Fabric 只从 `fabric.mod.json` 的 `mixins` 数组加载 mixin 配置，**不会**扫描 `*.mixins.json`。
  所以 `IncludeProcessorMixin` 从未被应用 ⇒ `getIncludedFile` 没被改写 ⇒ 钩子调用数恒为 0。
- §16.3 里写的"已加入 `fabric.mod.json`"是错的，实际没写进去。**这条错误本身值得记下来：
  凡是"配置类改动"，必须回读产物文件，而不是回读我自己的记忆。**

### 17.2 挂点本身没问题（排除"挂错地方"）

`javap -c net.irisshaders.iris.shaderpack.ShaderPack`：

```
1357: new  IncludeProcessor          // ShaderPack.<init> 里建图
1363: invokespecial <init>(IncludeGraph)
1378: invokedynamic #16 apply:(List;IncludeProcessor;Iterable)Function   // sourceProvider
...
lambda$new$8:
  51: invokevirtual IncludeProcessor.getIncludedFile(AbsolutePackPath)ImmutableList
```

即 `getIncludedFile` 确实在"包装载 → 每个文件取源码"的路径上。挂点是对的，错的只是"没挂上"。

### 17.3 缺陷 2：锚点会落到**片元** `void main()`（修好 1 之后立刻黑屏）

- 真实包 `ComplementaryReimagined_r5.9.3/shaders/world0/gbuffers_entities.vsh` 只有 7 行：
  `#version 130` / `#define VERTEX_SHADER` / `#define OVERWORLD` / `#define GBUFFERS_ENTITIES`
  / `#include "/program/gbuffers_entities.glsl"`。**`.vsh` 里没有 `#ifdef VERTEX_SHADER`** —— 它在被 include 的
  程序文件里。
- Iris 的 `IncludeProcessor` 把 include **原地展开**，所以拿到的行里 `#ifdef VERTEX_SHADER` 出现多次。
- `shaders/lib/util/commonFunctions.glsl:14` 就有一个 `#ifdef VERTEX_SHADER`，而它经
  `shaders/lib/common.glsl:730` 被 `shaders/program/gbuffers_entities.glsl:6` include ——
  位置在片元段 `#ifdef FRAGMENT_SHADER`（该文件第 9 行）**之前**。
- 上一版逻辑是"第一个 `#ifdef VERTEX_SHADER` 之后的第一个 `void main`" ⇒ 命中
  `gbuffers_entities.glsl:130` 的**片元 main**（`grep "void main" shaders/lib` 无命中，所以中间没有别的候选）。
- 后果：把只在顶点阶段合法的代码（`gl_Vertex`/`gl_NormalMatrix`/`gl_ModelViewMatrix`）插进片元 main
  ⇒ 编译失败 ⇒ 正是 §15/§16 里"车辆直接不显示 / 游戏全黑"的形态。

### 17.4 缺陷 3：`#define vaUV2 …` 这套机制**根本不存在**（即使编译过也是空转）

Iris 1.7.2 的兼容层是 **AST/词法改写，不是宏**：

- 扫整包 `.class` 常量池：**没有任何 `#define gl_`**；也**没有任何 `PreProcessor`/`setPreprocessor`**
  ⇒ Iris 的 AST 阶段不做宏展开，宏只在最终 GLSL 编译时按**文本位置**生效。
- `VanillaCoreTransformer`：`gl_MultiTexCoord1` → `vec4(iris_UV2, 0.0, 1.0)`；`vaUV2` → 改名 `iris_UV2`。
- `VanillaTransformer`（字节码，偏移见下）：
  ```
  119: ShaderAttributeInputs.hasLight()
  122: ifeq 147
  127: ldc "gl_MultiTexCoord1"   129: ldc "vec4(iris_UV2, 0.0, 1.0)"
  131: Root.replaceReferenceExpressions(parser, "gl_MultiTexCoord1", "vec4(iris_UV2, 0.0, 1.0)")
  136: getstatic ASTInjectionPoint.BEFORE_DECLARATIONS
  139: ldc "in ivec2 iris_UV2;"
  141: TranslationUnit.parseAndInjectNode(parser, BEFORE_DECLARATIONS, "in ivec2 iris_UV2;")
  147: (else) "gl_MultiTexCoord1" → "vec4(240.0, 240.0, 0.0, 1.0)"   // 没有光照属性 ⇒ 强制亮
  ```
- 真实包**只用** `gl_MultiTexCoord1`（全包唯一命中 `lib/util/commonFunctions.glsl:16`），
  **一次都没用 `vaUV2`** ⇒ 上一版 `#define vaUV2 …` 影子的名字在最终源码里根本不出现 = 完全空转。
- 就算名字对，**位置也错**：宏只影响它**之后**的文本，而 `GetLightMapCoordinates()` 的函数体在文件前部
  （`commonFunctions.glsl:15-18`），预处理器早已读过。上一版把 `#define` 放在 `void main()` 之前。

**唯一能活到编译期的名字是 `iris_UV2`**（Iris 自己插入/改写的那个）。

### 17.5 新方案：只影子 `iris_UV2`，用"自引用宏"把原值当参数拿回来

改动三处（都在 mod 自身，不碰包）：

1. **注册**：`fabric.mod.json` 的 `mixins` 加上 `"mtr.iris.mixins.json"`。
2. **锚点**：`MmtrShaderPackLightField.findVertexMain()` 改成**跟踪条件编译栈** ——
   只接受「有 `#ifdef VERTEX_SHADER` 包着、且没有被 `#ifdef FRAGMENT_SHADER` 包着」的那个 `void main`。
   对上面的真实文件，这会精确落在 `gbuffers_entities.glsl:326` 的**顶点 main**。
3. **载体**：分两段注入
   - piece 1（`#version` 之后、**所有包代码之前**）：
     ```glsl
     ivec2 mmtrLightUv(ivec2 mmtrOriginalUv2);
     #define iris_UV2 mmtrLightUv(iris_UV2)
     ```
   - piece 2（顶点 `void main()` 之前）：uniform + 共享核心 + `mmtrLightUv()` 函数体。

   包里的 `vec4(iris_UV2, 0.0, 1.0)` 于是变成 `vec4(mmtrLightUv(iris_UV2), 0.0, 1.0)`；
   宏体里的 `iris_UV2` 是**自引用**，按 C/GLSL 预处理器的"蓝漆"规则不会再展开，
   所以它**原样作为参数传进来 = 这个顶点真正的属性值**（MTR 这个 draw 喂的常量）。
   于是"光场没覆盖到就保持原样"是精确的，不需要额外 uniform、不需要 CPU 往返。

   为什么这样对**所有**包都成立：`hasLight()` 为真时 Iris 一定插入 `in ivec2 iris_UV2;`
   并把包的取光写法收敛到这个名字上；包写 `gl_MultiTexCoord1`（Complementary）被替换成
   `vec4(iris_UV2,…)`，包写 `vaUV2`（现代写法）被改名成 `iris_UV2` —— 两条路都落到同一个名字。

   为什么不会把 Iris 插入的声明改坏：那条声明插在 `ASTInjectionPoint.BEFORE_DECLARATIONS`
   （**所有声明之前**），在最终文本里一定排在我们的 `#define` **之前**（§17.4 的偏移 136 是直接证据）。

   **失败仍然安全**：包里自己声明了光照属性（会被改名到我们的宏之后而撞车）⇒ 不注入；
   找不到顶点 main ⇒ 不注入；资源读不到 ⇒ 不注入。一律只记日志。

### 17.6 离线验证（先证后改，省下重启）

- 新增 `sandbox/glslcheck/make_synthetic.ps1`：用**生产同一份资源**生成"注入后的包顶点着色器"，
  避免手抄漂移。
- 新增 `sandbox/glslcheck/pack_probe.vsh|fsh`：专门证自引用宏的语义（会不会无限递归）。
- 结果：生产 fsh、debug fsh、`pack_synthetic`、`pack_probe` **四组全部 vertex OK / fragment OK / link OK**。
  自引用宏能编译通过本身就证明"蓝漆"规则成立。

### 17.7 顺带确认的两件事（留给下一步）

- **AO 通道**：`program/gbuffers_entities.glsl:292 out vec4 glColor;` / `:335 glColor = gl_Color;`
  / `:135 color *= glColor;` ⇒ 这个包**确实**把顶点颜色乘进最终颜色，AO 走顶点颜色成立。
  但注意 `gl_Color` 也是被 AST 改写的（→ `(iris_Color * iris_ColorModulator)`），
  上一版"`#define gl_Color`"的手法同样无效；要改只能改 `glColor` 这个 varying，
  那就依赖包的命名 ⇒ 需要另设计（或和光照一起编码进 `iris_UV2`）。
- **轴序**（⚠️ 这一条当时的结论是**错的**，已在 §17.14 纠正）：当时只看了 `VertexAttributeState`
  构造函数里的 `exchangeLightmapUVBits`，就断定"喂给顶点属性的是 `(sky*16, block*16)`"。
  实际 `apply()` 里是 `glVertexAttribI2i(loc, lightmapUV >>> 16, (short) lightmapUV)` ——
  **又反序取出**，两次交换抵消，MTR 最终喂进去的正是 vanilla 的 `(block*16, sky*16)`。
  教训：**只读半个方法的字节码就下结论，等于没读。**

### 17.8 修好上面三条之后暴露的第 4 个坑：光影包装载得比资源管理器还早

注册修好、钩子当场就落上了（`18:23:11` 起能看到 `Iris 请求 /lib/uniforms.glsl（第 1 次，208 行）`…），
但紧接着：

```
[18:23:11] [Render thread/ERROR] [MMTR-LIGHT] 光影注入：准备文本失败，本次不注入
 java.lang.NullPointerException: Cannot invoke "ResourceManager.getResource(Identifier)"
   because the return value of "MinecraftClient.getResourceManager()" is null
  at MmtrShaderPackLightField.readResource(...)
  at MmtrShaderPackLightField.patch(...)
  at IncludeProcessor.getIncludedFile(...)
  at ShaderPack.lambda$new$8(ShaderPack.java:266)
  at ProgramSet.readProgramSource(ProgramSet.java:91)
  at Iris.loadExternalShaderpack(Iris.java:303)
  at Iris.onRenderSystemInit(Iris.java:117)
  at RenderSystem.initRenderer(RenderSystem.java:855)
  at MinecraftClient.<init>(MinecraftClient.java:534)     ← 客户端**构造期间**
```

即：**光影包是在 `RenderSystem.initRenderer()` 里装载的**，那一步在 `MinecraftClient` 构造期间，
`getResourceManager()` 还是 `null`。所以挂在这个钩子上的代码**不能**用原版资源管理器读文件。

修法：这两个 GLSL 是我们自己的实现细节（也不该被资源包覆盖），直接读自己的类路径
（`MmtrShaderPackLightField.class.getResourceAsStream("/assets/minecraft/shaders/include/…")`），
资源管理器只留作兜底。这条同时说明：任何"挂在包装载路径上"的代码，**初始化时机必须假设
早于一切客户端子系统**。

### 17.9 终审证据：**用宏影子属性名是结构性不可能的**（Iris 自己 dump 的编译源码）

把 `config/iris.properties` 的 `enableDebugOptions` 打开后，Iris 会把**真正交给 GLSL 编译器的那份源码**
写到 `run/patched_shaders/NNN_<program>.vsh|fsh`。这是"它到底编译了什么"的终审证据，不用再猜。

`028_entities_cutout.vsh`（我们的注入目标之一）实测：

```
1| #version 410 core
2| // Generated by glsl-transformer
3| uniform mat4 iris_ProjMat;
...
14| in ivec2 iris_UV2;                                  ← Iris 插的属性声明（BEFORE_DECLARATIONS）
...
36| ivec2 mmtrLightUv(ivec2 mmtrOriginalUv2);           ← 我们的**原型**活着
    （我们的 #define iris_UV2 mmtrLightUv(iris_UV2)  —— **不见了**）
...
156| vec2 GetLightMapCoordinates() {
157|     vec2 lmCoord = (iris_LightmapTextureMatrix * vec4(iris_UV2, 0.0f, 1.0f)).xy;   ← 宏没展开
...
677| uniform usampler2D mmtrLut;                        ← 我们的 uniform 声明在
...
838| ivec2 mmtrLightUv(ivec2 mmtrOriginalUv2) { ... }   ← 我们的函数体在
```

而且整份 dump 里 `#define` / `#if` 的行数是 **0**。

**结论（这是本轮最重要的发现）**：Iris 的流水线是
「**先做完整预处理**（把所有 `#define`/`#ifdef`/include 全部求值、拍平成普通代码）→ 再上
`glsl-transformer` 做 AST 改写（`gl_MultiTexCoord1` 是在这一步才被换成 `vec4(iris_UV2, …)`）→
打印成最终源码」。所以：

- 我们注入的 `#define` 在**预处理阶段**就已经被消费/丢弃；
- 而 `iris_UV2` 这个 token 是**预处理之后**才被 AST 插进去的 —— 那时已经没有预处理器了。

⇒ **任何"注入 `#define` 来影子属性名"的方案都不可能生效**：它同时解释了为什么
`#define vaUV2`（§17.4）和 `#define iris_UV2`（§17.5）都是空转。
⇒ 连带后果：`mmtrLightUv` 变成**死代码**，GLSL 编译器把整个函数连同它用到的
`mmtrLut/mmtrAtlas/mmtrSolid` uniform 一起删掉 ⇒ 运行时 `glGetUniformLocation` 拿到
`-1/-1/-1`（正是日志里看到的）。**"注入了"和"生效了"是两件事，这一步必须用 dump 或探针证明，不能靠推断。**

### 17.10 正确的路子：改写**取光 token 本身**，不要用宏

既然最终源码里 `iris_UV2` 是 Iris 自己插进来的普通标识符，唯一可行的注入点是
**在包源码里把"读光照属性"的那个 token 换成我们对它的调用**：

- 旧式写法（Complementary）：把 `gl_MultiTexCoord1` 换成 `mmtrLightFromPack(gl_MultiTexCoord1)`。
  Iris 随后仍会把**内层**那个 `gl_MultiTexCoord1` 换成 `vec4(iris_UV2, 0.0, 1.0)`
  （`replaceReferenceExpressions` 只换引用表达式，我们插进去的那层包装它会照换），
  并且 `in ivec2 iris_UV2;` 的插入在 `hasLight()` 分支里是**无条件**的
  （字节码 136-141：`getstatic BEFORE_DECLARATIONS` + `parseAndInjectNode`，没有"是否还有引用"的判断），
  所以 `iris_UV2` 一定被声明、也一定被喂上数据。
- 现代写法：把 `vaUV2` 换成 `mmtrLightFromIvec2(vaUV2)`；Iris 的 `Root.rename("vaUV2","iris_UV2")`
  会把内层一起改名。

配套要点：
- 两个包装函数都**保持原类型**（`vec4`→`vec4`、`ivec2`→`ivec2`），所以周围表达式（如
  `iris_LightmapTextureMatrix * …`）不需要改，也不依赖包的变量命名。
- 原型必须插在文件最前（包自己的取光函数在很前面就调用了它）。
- "原始值"就是这个 token 传进来的实参 —— `vec4(iris_UV2,0,1)` 的分量是原始整数（0..240，
  浮点可精确表示），`ivec2(v.xy)` 能逐位还原；不需要额外 uniform、不需要 CPU 往返。
- **失败仍然安全**：包里自己声明了该属性 ⇒ 不注入；找不到顶点 main ⇒ 不注入。
  另外要注意：如果 `hasLight()` 为假，Iris 根本不会声明 `iris_UV2`，那时我们注入的代码会引用未声明标识符
  ⇒ 因此包装函数必须只在**确认目标程序会走 hasLight 分支**时才插（这一条要在实现时落实）。

### 17.11 本轮的净结果

| 缺陷 | 状态 |
|---|---|
| 1. `mtr.iris.mixins.json` 没注册 | ✅ 已修，实测 `钩子调用=673`、`注入程序数=4` |
| 2. 锚点落到片元 `void main` | ✅ 已修（条件编译栈），实测落在 `…entities.vsh:11585/11657`，无编译失败 |
| 3. 包装载早于资源管理器 ⇒ NPE | ✅ 已修（读自己的 classpath），`就绪=true` |
| 4. 宏影子属性名结构性无效 | ❌ 已定性（§17.9），方案改为 token 改写（§17.10），**尚未实现** |

现状安全性：注入存在但是死代码 ⇒ 光影下车厢渲染**与本轮之前完全一致**（不会更坏、不会黑屏）。
`config/iris.properties` 的 `enableDebugOptions=true` 是本轮为取证打开的，会在每次包装载 dump
`run/patched_shaders/`（约 100 个文件），下一轮验证 token 改写时正好用得上；不需要时改回 `false` 即可。

### 17.12 成品：token 改写方案已实现并实测落地（2026-09-28 18:35）

按 §17.10 实现完毕。**代码改动（全部在 mod 自身）**：

| 文件 | 改动 |
|---|---|
| `src/main/resources/fabric.mod.json` | `mixins` 注册 `mtr.iris.mixins.json`（§17.1） |
| `src/main/java/org/mtr/mixin/iris/IncludeProcessorMixin.java` | 挂 `IncludeProcessor.getIncludedFile` 出口（未变） |
| `src/main/java/org/mtr/mod/render/light/MmtrShaderPackLightField.java` | 重写：**token 改写** + 两段注入 + 条件编译栈锚点 + 类路径读资源 + 探针 |
| `src/main/resources/assets/minecraft/shaders/include/mmtr_lightfield_pack.glsl` | 重写为 `mmtrPickUv2` / `mmtrLightFromPack` / `mmtrLightFromIvec2` 三个函数 |
| `src/main/java/org/mtr/mod/render/light/MmtrLightField.java` | 新增 `describeProgram` 探针（列 active uniform + 附着着色器源码标记） |

**离线验证（`sandbox/glslcheck/`）**：新增两条与生产同源的验证通道，`check.ps1` 共 4 组全绿：

- `make_synthetic.ps1` → `pack_synthetic.vsh`：手搓等价最小源码（秒级，含旧式/现代两条调用路径）。
- `make_realcheck.ps1` → `pack_real.vsh`：**直接拿 Iris 真实 dump 当输入**。它会先把 dump 里可能残留的
  旧注入剥掉，并用三条断言自证剥干净了（不含 `mmtr` 标识符、恰好 1 个 `void main`、
  恰好 1 处 `vec4(iris_UV2, …)`），再补上"生产注入会造成的结果"。
  因为 dump 是 Iris 改写**之后**的，而 piece2 在生产里插在改写**之前**，生成器要手工补上 Iris 那批替换
  （`gl_ModelViewMatrix`→`(iris_ModelViewMat * _iris_internal_translate(iris_ChunkOffset))` 等）——
  这批替换的正确性由上一版 dump 自己的第 839 行证明。
- `GlslCheck` 的 GL 上下文改成从 4.5 往下降级（Iris 交给编译器的是 `#version 410 core`，
  3.2 上下文编不了）。实测拿到 4.5。

**实机验证（18:35 那次启动）**：

```
光影注入 /world0/gbuffers_entities.vsh：已注入（顶点 main 在第 11584 行，piece1 在第 2 行，
        函数体 11906 行，改写取光 token 1 处 → 共 11980 行）
（beaconbeam / entities_translucent / entities_glowing 同样各 1 处）
```

Iris 自己 dump 的**最终编译源码** `028_entities_cutout.vsh` 里：

```
36| vec4 mmtrLightFromPack(vec4 mmtrPackUv2);
37| ivec2 mmtrLightFromIvec2(ivec2 mmtrPackUv2);
157|    vec2 lmCoord = (iris_LightmapTextureMatrix * mmtrLightFromPack(vec4(iris_UV2, 0.0f, 1.0f))).xy;
    ...
	ivec2 mmtrPickUv2(ivec2 mmtrOriginalUv2) { ... }
	vec4 mmtrLightFromPack(vec4 mmtrPackUv2) { ... }
	ivec2 mmtrLightFromIvec2(ivec2 mmtrPackUv2) { ... }
860|    lmCoord = GetLightMapCoordinates();
```

即包的光照读取**确实被包进了我们的函数**（上一版这里是 `vec4(iris_UV2, 0.0f, 1.0f)` 裸读）。
调用链 `main:860 → GetLightMapCoordinates():157 → mmtrLightFromPack → mmtrPickUv2 → mmtrLut/Atlas/Solid`
全部打通 ⇒ 三个 uniform 是活代码，不会再被编译器删掉（这正是上一版 -1/-1/-1 的原因）。
日志里 `ailed to compile|compilation failed|ERROR: 0:` **零命中**。

**仍未验证的一环**：光影包模式下的**运行时采样器绑定**（`光影采样器：program N 已用单元=… → 光场用 x/y/z`）
需要世界里真的有一列车被画出来才会触发；以及最终**肉眼**确认车厢各部分光照随位置变化（亮/暗对照）。
这两项要在进入世界、车旁站定后收尾。

### 17.13 端到端实测通过（2026-09-28 18:38，世界里 2 列车在视野内）

```
[18:38:25] light field online: atlasTex=165 lutTex=166 solidTex=167 samplers=3/4/5
[18:38:25] canUseCustomShader 初始 = false（有光影包在跑）
[18:38:26] 光影采样器：program 257 已用单元={0, 1, 5} → 光场用 8/9/10（LUT/图集/实心位图）
[18:38:29] 光影采样器：program 260 已用单元={0, 1, 5} → 光场用 8/9/10（LUT/图集/实心位图）
[18:38:35] MMTR-PERF 5 秒窗口：帧=302（60 FPS）帧最慢=43ms
[18:38:40] MMTR-PERF 5 秒窗口：帧=331（65 FPS）帧最慢=92ms
```

对比上一版同一行是 `program 257 里没有 mmtrLut/mmtrAtlas/mmtrSolid（-1/-1/-1），跳过`。
现在三个 uniform **被找到并绑到了 Iris 用不到的空闲单元 8/9/10**（Iris 自己用 {0,1,5}，我们绝不碰）
⇒ 这是"注入的代码真的活着"的运行时铁证。零编译错误、无黑屏、60–65 FPS。

**整体链路（光影包开着）**：光场数据照常上传（atlas/lut/solid 三个纹理）
→ 注入进包 entity 程序的顶点阶段按世界坐标采样 → 写回光照属性
→ 包的片元阶段照它自己的曲线算光与阴影。**draw call 不变**（没有新增任何绘制）。

**已知边界 / 下一步**：
- 逐顶点 vs 逐片元：光影下是逐顶点（Flywheel/Create 同路线，见 §16.1）。车厢横跨明暗边界时看得出逐顶点差异；
  整车处在均匀光照里时看不出 —— 这不是 bug，是这条路线的固有粒度。
- AO 尚未接入（§17.7：这个包确实把顶点颜色乘进最终颜色，但 `gl_Color` 同样被 Iris 改写，
  要改只能动它自己的 varying `glColor`，会依赖包命名 ⇒ 需另设计，或与光照一起编码进光照属性）。
- 现代写法（包自己声明 `vaUV2`）那条路只做过离线编译，实机遇到的包都是旧式 `gl_MultiTexCoord1`。
- `run/config/iris.properties` 的 `enableDebugOptions=true` 还开着（每次包装载 dump 约 100 个文件）；
  不需要时改回 `false`。

### 17.14 用户实测反馈"车亮度完全统一、没有暗的地方" —— 是我的轴序错了

§17.13 交付后用户立刻反馈：车整个亮度一致、没有任何暗处。这正是"blocklight 通道被天空光灌满"的形态。

**纠正 §17.7 的错误结论。** 完整读 `VertexAttributeState` 两个方法后：

```
// 构造函数
14: invokestatic Utilities.exchangeLightmapUVBits:(I)I     // arg = LightmapTextureManager.pack(block, sky)
20: putfield     lightmapUV
   ⇒ lightmapUV = (sky*16) | (block*16 << 16)              // low16 = sky, high16 = block

// apply()
345: getfield     lightmapUV
352: bipush 16
354: iushr                                                  // 第一个分量 = lightmapUV >>> 16 = block*16
360: getfield     lightmapUV
363: i2s                                                    // 第二个分量 = (short) lightmapUV = sky*16
364: invokestatic GL33.glVertexAttribI2i:(III)V
   ⇒ 属性 = (block*16, sky*16)   ← **就是 vanilla 的口径**
```

**交换了两次、方向相反，净结果等于没交换。** 我上一轮只看了构造函数，把 `mmtrPackEncode` 写成
"照抄 MTR 的交换"，于是包拿到的 `lmCoord` 是 `(sky/15, block/15)`：`lmCoord.x`（blocklight）
= 天空光 = 白天 15 ⇒ 1.0 ⇒ 被 Complementary 自己那句 `lmCoord.x = min(lmCoord.x, 0.9);`
（注释原文：*"reducing the max blocklight on a normal entity"*）夹到 0.9，
而 `lmCoord.y`（skylight）= block = 0 ⇒ 车被"饱和的火把光"整体点亮、与天空阴影无关
⇒ **全车亮度统一、没有暗处**。与反馈完全吻合。

**修法**（只改一处，`mmtr_lightfield_pack.glsl`）：

```glsl
ivec2 mmtrPackEncode(float block, float sky) {
    return ivec2(int(block + 0.5) << 4, int(sky + 0.5) << 4);   // vanilla: (block*16, sky*16)
}
```

旁证（两边都对上了）：
- 光场里 `field.light` 的顺序是 `(block, sky)`：写入端
  `atlasCpu.put(..., (block & 15) | ((sky & 15) << 4))`，读取端
  `return vec3(float(packedLight & 15u), float((packedLight >> 4u) & 15u), 1.0);`。
- 无光影那条路一直是对的 —— `mmtrFieldColor(light) = texture(Sampler2, light / 16.0)` 把
  `light.x` 当光照贴图的 x（= blocklight）用，所以用户才说"不开光影列车光照正常"。
  这也解释了为什么这个 bug 只在光影下暴露。
- vanilla 自己的 `rendertype_entity_cutout.vsh` 也是 `lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);`
  —— 与 MTR 逐字相同，前提就是属性 = `(block*16, sky*16)`。

**教训**：`exchangeLightmapUVBits` / `glVertexAttribI2i` 这一对必须**成对读完**才能判断口径；
只读一半就推断"编码一致"，属于"预期当证据"。

### 17.15 用户实测"世界的发光方块显示不对" —— MTR 强制点亮的部件被世界光压暗

修好轴序后用户报"我手持光源完全正常，但对世界的发光方块的显示不对"。查 MTR 上游源码后确认：

```
ModelTrainBase.java:55-56
  final int lightOnInteriorLevel = lightsOn ? MAX_LIGHT_INTERIOR : light;
  final int lightOnGlowingLevel   = lightsOn ? GraphicsHolder.getDefaultLight() : light;
RenderStage.java
  ALWAYS_ON_LIGHT(OptimizedModel.ShaderType.TRANSLUCENT_GLOWING)   ← 用车头灯/目的地显示屏等，每 draw 用 getDefaultLight()
  INTERIOR(OptimizedModel.ShaderType.CUTOUT_BRIGHT)                ← 车灯开着时的车内照明，用 MAX_LIGHT_INTERIOR
GraphicsHolder.getDefaultLight() = 15728880 = 0xF000F0 = pack(15,15) → 属性 (240, 240)
IGui.MAX_LIGHT_INTERIOR          = 0xF000B0 = pack(11,15) → 属性 (176, 240)
```

**光影那条路我们之前完全没做"强制点亮透传"**，于是这些"约定常量"被世界光覆盖 ⇒ 车灯/显示屏/车内照明
该亮不亮。而且**无光影那条路的判据本身也是错的**：`mmtr_vehicle_light.fsh` 里写的是
`mmtrLightUv.x == 240 && (mmtrLightUv.y == 240 || mmtrLightUv.y == 176)` —— `(240,176)` 是**交换过**的旧口径，
永不成立，所以车内照明的强制点亮一直没生效。

修法：判据提到共享核心 `mmtr_lightfield.glsl` 的 `mmtrForcedBrightUv2(ivec2)`（`(240,240)` / `(176,240)`），
两条路都调它；光影路径在 `mmtrPickUv2` 开头先判它、命中就原样返回。

### 17.16 用户实测"车身明暗无逻辑" —— 我们漏了 MTR 的 ModelMat（**最关键的一处**）

用户接着报"整体亮度或颜色不对 + 车厢明暗过渡不自然 + 车身明暗无逻辑"。对照**验收过的无光影路径**
（`mmtr_vehicle_light.vsh`）就一眼看出问题：

```glsl
in mat4 ModelMat;                                              // MTR 每 draw 的模型矩阵
vec4 mmtrViewSpace = ModelViewMat * ModelMat * vec4(Position, 1.0);
mmtrRelWorld = IViewRotMat * mmtrViewSpace.xyz;
```

而我们注入进包的那段写的是 `gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex` ——
**ModelMat 整个漏了**。MTR 的车体顶点在 VBO 里是**模型局部坐标**，摆放全靠 ModelMat
（MTR 自己的顶点格式里占 location 6..9）；包的程序里 `gl_ModelViewMatrix` 被 Iris 改写成
`iris_ModelViewMat * _iris_internal_translate(iris_ChunkOffset)`，**不含** MTR 的模型变换。
少了它，算出来的"世界坐标"≈ 模型局部坐标 ⇒ 采到的光与车体真实位置无关 ⇒ **明暗无逻辑**。

**为什么不能直接声明属性**：`gbuffers_entities` 是 MTR 和**原版实体共用**的 program。原版实体没有 ModelMat，
而那四个 location 会残留 MTR 上一个 draw 的值（MTR 用 `glVertexAttrib4f` 逐个上传）⇒ 原版实体会误用。

**采用的方案：当 uniform 逐 draw 传 + 全零哨兵**
- `VertexAttributeStateMixin`（已在 `VertexAttributeState.apply()` HEAD）→ `MmtrLightField.onDrawState`
  → `uploadPackModelMatrix(modelMatrix)`：`glUniformMatrix4fv` 写进当前 program 的 `mmtrModelMat`。
- 顺序有字节码保证：`BatchManager$RenderCall.draw()` = `VertexArray.bind()` → 本次 draw 的
  `VertexAttributeState.apply()`（我们在这里拿到 ModelMat）→ 材质级 `apply()`（两参构造，`matrix4f == null`，
  不会覆盖）→ `VertexArray.draw()`。
- `BatchManagerRenderCallMixin` 新增 RETURN 注入 → `clearPackModelMatrix()` 把 `mmtrModelMat` 置**全零**。
  着色器里 `if (mmtrModelMat[3][3] == 0.0) return mmtrOriginalUv2;` —— 于是**非 MTR 绘制一律原样返回**，
  原版实体完全不受影响。链不上时（uniform 被优化掉等）也是"退回 per-draw 光"，不会出垃圾。
- 属性 location 6..10 是空的：Iris `ProgramCreator` 只绑 `iris_Entity`/`mc_Entity`→11、
  `mc_midTexCoord`→12、`at_tangent`→13、`at_midBlock`→14，以及 0..5 的 vanilla 那批 —— 所以其实两条路都可行，
  选 uniform 是因为它不依赖 `glVertexAttribDivisor` 语义，且能天然地"只对 MTR 的 draw"生效。

**顺带纠正一条认知**：`gl_ModelViewMatrix` / `gl_Vertex` / `gl_NormalMatrix` 这些兼容内建在 Iris 的
AST 改写之后会变成 `iris_*`，**语义与 MTR 自己 shader 里的同名 uniform 不一定相同**（下面 §17.17 就是例证）。

### 17.17 用户实测"会随视角角度、玩家位移改变明暗" —— 相机旋转被乘了两遍

补上 ModelMat 后用户报"现在是有变化，但会随着视角角度、玩家位移改变明暗"。
这正是 MTR 在自己 vsh 里**两次踩坑记录**（60-71 行）的同型错误：

> 实测 2：A（只加相机位置）→ y≈99，天上什么都没有；B（再乘 IViewRotMat）→ y≈68，正好是轨道高度
> ⇒ 相机旋转在 **ModelMat** 里，ModelViewMat 只是单位矩阵

也就是说 `MTR 的 ModelViewMat ≡ 单位矩阵`（相机旋转在 ModelMat 里），所以它的式子是
`IViewRotMat * (ModelMat * Position)`。而包的程序里 `gl_ModelViewMatrix` 被 Iris 改写成
**`iris_ModelViewMat`（真正的相机视图矩阵）** —— 再乘一次等于把相机旋转乘了两遍，
结果随镜头转动/玩家移动而变。

修法（只改一行）：

```glsl
// ❌ vec4 mmtrViewSpace = gl_ModelViewMatrix * mmtrModelMat * gl_Vertex;
vec4 mmtrViewSpace = mmtrModelMat * gl_Vertex;
vec3 relativeWorld = mat3(gbufferModelViewInverse) * mmtrViewSpace.xyz;
vec3 worldNormal  = normalize(mat3(gbufferModelViewInverse) * mat3(mmtrModelMat) * gl_Normal);
```

**验收（2026-09-28 19:03，光影包开着，世界里 5 列车）**：
用户实测「**现在稳定了：镜头怎么转、人怎么走，车身明暗都不变**」✅

Iris 自己 dump 的最终编译源码 `028_entities_cutout.vsh` 里的定稿形态：

```
835| bool mmtrForcedBrightUv2(ivec2 uv2) { ... }
842|     if (mmtrForcedBrightUv2(mmtrOriginalUv2)) { return mmtrOriginalUv2; }
845|     if (mmtrModelMat[3][3] == 0.0f) { return mmtrOriginalUv2; }
848|     vec4 mmtrViewSpace = mmtrModelMat * vec4(iris_Position, 1.0f);
849|     vec3 relativeWorld = mat3(gbufferModelViewInverse) * mmtrViewSpace.xyz;
850|     vec3 worldNormal = normalize(mat3(gbufferModelViewInverse) * mat3(mmtrModelMat) * iris_Normal);
157|    vec2 lmCoord = (iris_LightmapTextureMatrix * mmtrLightFromPack(vec4(iris_UV2, 0.0f, 1.0f))).xy;
```

运行时不变量：`光影采样器：program 257/260 已用单元={0,1,5} → 光场用 8/9/10`、
零编译错误、平均帧时 15.1–18.3 ms（2600–3000 draws/frame，5 列车）。

**本轮第四个可复用的教训**：`gl_ModelViewMatrix` 这类"兼容内建"经 Iris 改写后，
**和 MTR 自己 shader 里的同名 uniform 不是一回事** —— 跨过 Iris 改写层做坐标换算时，
每一步都要拿"验收过的参考实现"逐字对齐，不能凭名字想当然。

### 17.18 一个被我一开始说错的运维细节：改注入文本必须重启客户端

`client.reloadResources()` 只重载**原版资源**，Iris **不会**因此重新解析光影包
（它在启动 / 世界加载 / 切换光影包时才 `loadShaderpack`）。而我们的注入发生在**包装载**
（`IncludeProcessor.getIncludedFile`）那一刻，所以：

- 改 `mmtr_lightfield_pack.glsl` / `mmtr_lightfield.glsl` ⇒ **必须重启客户端**（或在游戏里切一次光影包，
  那会走 `Iris.reload()`）。
- 我在 §17.12 之后一度说"热重载生效"，是错的：当时看到的 dump 其实是上一次重启留下的。
  `invalidate()`（资源重载时作废注入文本缓存）仍然保留 —— 它在"Iris 真的重新解析包"时是必需的，
  只是它自己**不能**触发重新解析。

### 17.19 用户报"车辆建模的不同面对光的反射不同" —— 法线空间是可疑点，但改用**运行时开关**来判

**硬事实（GL 回读，2026-09-28 19:16）**：

```
包程序矩阵探针（program 257）：
  iris_ModelViewMat        = 单位矩阵
  iris_NormalMat           = 单位矩阵        ← 包的 `normal = normalize(iris_NormalMat * iris_Normal)` 等于直接用它
  gbufferModelViewInverse  = 纯旋转（末列 0,0,0,1，无平移）
  mmtrModelMat             = 全零（探针在批设时刻读，那是上一 draw 留下的哨兵）
mmtrModelMat 回读：平移=(-127.69, 24.28, -106.06) [3][3]=1.00
              （写入的平移=(-127.69, 24.28, -106.06)）
```

⇒ ① 我们的逐 draw 上传**确实生效**（回读与写入一致、`[3][3]=1.0` 不会误触哨兵）
⇒ **逐顶点光场路径在车体上确实是活的**，不是退回 per-draw 常量（这一点之前只能靠"看着稳定"推断，现在有硬证据）。
⇒ ② 包按 **vanilla 口径**用 `iris_Normal` 当视空间法线；而 MTR 的 `Normal` 顶点属性是**模型局部空间**
   （它自己 shader 要 `mat3(ModelViewMat * ModelMat) * Normal` 才得到视空间 —— 同一个道理，
   位置也要 `ModelMat`，而我们已经验证过位置那一半是对的）。

**但**：按这个推断加上的"法线包装"（`gl_Normal` → `mmtrNormalFromPack(gl_Normal)`）首次硬开关实测，
用户反馈"**没变化 / 反而变差了**"，无法据此定论。所以改成**运行时开关**，让眼睛在大世界里判：

- 注入侧**恒常**包一层：`mmtrNormalFromPack(...)`；
- 行为由 uniform `mmtrNormalFix` 决定（0 = 原样交出去，1 = 先 `mat3(mmtrModelMat) * n` 转成视空间）；
- Java 侧从 `run/mmtr-lightfield.properties` 的 `normalFix=` 每 draw 上传（`refreshDiagnostics` 每 2 秒重读），
  **改一行、2 秒内生效、不用重启**（重启一次约 3 分钟，这种"哪个更好"的问题不值得）。

**一条待办**：还要问清楚"不同面对光的反射不同"到底是不是**包自己的方向光照**（vanilla 生物/物品在同一个包里
也会这样）—— 若原版实体也一样，那车体就是正常表现，不是我们的 bug。

**本轮第五个可复用教训**：凡是"哪个更好"这类只能眼睛判的改动，**先做成运行时开关再交给用户**，
不要用"改一版 + 重启 3 分钟"去猜。

### 17.21 "不同面对光的反射不同" —— 二分探针把范围锁到**光照**侧

做法：把"注入的光照"和"注入的法线"各做成可钉死的运行时 uniform（`probe=0/1/2/3`），
并把**上传值 + glGetUniformiv 回读值**一起打日志（先排除"开关没生效"这种假阴性）。

```
注入开关生效：program 257 normalFix=0（回读 0）probe=3（回读 3）   ← 开关确实到着色器了
注入开关生效：program 257 normalFix=0（回读 0）probe=1（回读 1）
```

用户实测（每一步都只改 properties，2 秒生效、不用重启）：

| 设置 | 含义 | 用户看到的 |
|---|---|---|
| `probe=3` | 光照钉满亮 + 法线钉 (0,1,0) | **车厢完全均匀**（面差异消失） |
| `probe=1` | 只把光照钉满亮 | **也完全均匀** |

⇒ **面差异来自我们写进去的光照**（不是法线）；`normalFix` 那条线可以放掉
（它不是没生效 —— 回读值证明生效了，只是不是主因）。

**诊断行里同时暴露了更可疑的东西**：

```
LUT: cells=50 tracked=50（网格 25x8x25）| 最近登记车=(-4149.85, 70.00, 1393.50) 车心光场采样=无数据 真实光照=0/15
slots=50/256 collecting=0 collected=0 tiles=0 | cam=(-3822.3, 72.6, 1399.1)    ← 相机离"最近登记车"约 327 格
draw #1: … light=6291696 || A 口径世界=(…) 光=0/0 || B 口径世界=(…) 光=0/0 (光场采样 无数据 / 0/0)
```

- 网格覆盖只有 **25×8×25 sections、以相机为中心 ±192/±64 格**（`MMTR_GRID_DIM=(25,8,25)`、
  `OFFSET=(12,4,12)`）。用户在看的车经常在 300+ 格外 ⇒ **整个车都在覆盖之外** ⇒ 顶点全部退回
  MTR 的 per-draw 常量。
- 而 MTR 同一列车的不同部件本来就用**不同的 per-draw 光照常量**（`QueuedRenderLayer.INTERIOR`
  用 `MAX_LIGHT_INTERIOR`、`EXTERIOR` 用世界光、`ALWAYS_ON_LIGHT` 用 `getDefaultLight()`）——
  一旦退回，这些部件就会呈现"按面/按部件不同"的硬差异。
- 近处（|T|≈7 格）的 draw 里，光场采样值出现 `3/15`（正常户外）与 `0/0`（**全黑**）交替 ——
  说明**同一辆车表面两种来源混用**（有的顶点取到光场、有的退回 per-draw）。

**下一步的两个候选（都需要你拍板，因为都是设计取舍）**：

1. **一致性优先**：把"取不取光场"的决定从**逐顶点**改成**逐 draw**（用 `mmtrModelMat` 的平移判断
   这个 draw 在不在覆盖内），这样同一部件绝不会一半光场一半 per-draw；代价是覆盖边界上的部件会整块退回。
2. **采样点外移**：车体顶点常在**方块内部/结构阴影里**（站台边、雨棚下），照顶点原样采样会取到"实心方块那格"的
   光照（≈0）⇒ 相邻面忽然变黑。按世界法线把采样点向**空气侧外移约半格**再采，更接近"表面的光照"，
   也避开这一整类假黑。这条更接近根因、代价很小。

**另附一条运维发现（省下大量时间）**：这台机器上 Gradle 的联网查询失败并**不是网断了** ——
`curl` 直连两个端点都是 200，本机 `127.0.0.1:7890` 有代理在监听。
`BuildTools.getJson` 失败的真实原因是 IPv6/黑洞 IP 组合。可用

```powershell
$env:JAVA_TOOL_OPTIONS = "-Djava.net.preferIPv4Stack=true -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890"
```

让客户端与 Gradle daemon **两个 JVM 同时**吃到这两个开关（实测 `IPv4 only` 失败、`proxy only` 失败、
**两者一起成功**），无需改 `build.gradle`/`gradle.properties`。

### 17.22 A（采样点外移）+ B（逐 draw 一致性）实测无效 ⇒ 根因是"逐顶点的粒度本身"

按用户选择 "A+B 一起做" 实现并实测：

- **A**：采样点沿世界法线外移 0.5 格（`MMTR_SAMPLE_NUDGE`），想避开"顶点落在实心方块那格取到全黑"。
- **B**：用 `mmtrModelMat[3].xyz` 折回 draw 原点，先 `mmtrSectionTracked` 判一次覆盖，不在覆盖内就整块退回，
  避免"同一部件一半光场一半 per-draw"。B 放在**着色器里**做（比 CPU 侧判更简单也更准，不用新增 uniform）。

**用户实测：两条都没帮助（"没变化 / 变差了"）。** 这本身是强证据 —— 排除了"采样点落点"和"来源混用"两个假设。

同时诊断里有一条**反向**证据：我们采到的光与**真实世界光照一致**
（`B 口径世界=(…) 光=0/0` 与 `光场采样 0/0` 对得上，见 §17.21 的日志）⇒ **光值本身是对的**。

**所以"按面跳"是逐顶点这个粒度本身的固有表现**：世界光在几格内本来就有台阶（雨棚边、灯边、隧道口），
而 MTR 的车体是**大面片低模**（一个面跨好几格）—— 四个角各采一次、中间线性过渡，面上就是明显梯度，
相邻面看起来"对光的反应不同"。原版实体看不出这个问题，只因为它们小。

**采用的处置：把它交给眼睛 —— 新增运行时旋钮 `mix=0..1`**

```glsl
// mmtr_lightfield_pack.glsl（piece 2）
float mmtrBlock = mix(float(mmtrOriginalUv2.x >> 4), field.light.x, mmtrVertexLightMix);
float mmtrSky   = mix(float(mmtrOriginalUv2.y >> 4), field.light.y, mmtrVertexLightMix);
return mmtrPackEncode(mmtrBlock, mmtrSky);
```

`0` = MTR 原样 per-draw 光（车厢整体一个值，即"单点"）；`1` = 纯逐顶点。
Java 侧从 `run/mmtr-lightfield.properties` 的 `mix=` 每 draw 上传（`refreshDiagnostics` 每 2 秒重读），
**改一行、2 秒生效、不用重启**，并把"上传值 + `glGetUniformfv` 回读值"打日志（证明真的到着色器）。
MTR 的 per-draw 属性是 vanilla 口径 `(block*16, sky*16)`，所以 `>>4` 就是等级（0..15）。

A 的常量同时改回 `0`（保留代码路径，便于以后再试）；B 的调用已移除（`mmtrSectionTracked` 留在核心里备用）。

**当前默认值**：`enabled=true`、`probe=0`、`normalFix=0`、`mix=0.3`（往返于"有点世界变化"与"不硬跳"之间，用户可自行调）。

**本轮第六个可复用教训**：当两个"合理的机制性假设"都被实测否掉、而数据又证明"值是对的"时，
剩下的往往不是 bug 而是**方法本身的粒度**。这时正确的动作不是继续猜机制，而是
**把粒度做成一个用户可调的连续旋钮**（并给出两端含义），让审美判断在游戏里当场完成。

### 17.20 改注入文本的一键流程（省掉大部分来回）

```powershell
# 1) 改 src 里的 include / Java
cd <workspace>; . .\env\workspace.env.ps1; cd mmtr\game
.\gradlew.bat :fabric:classes            # Java 改了要编译再重启
# 2) 重启客户端（Iris 只在启动/切包时重新解析光影包）
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ? { $_.CommandLine -match 'KnotClient' } | Stop-Process -Force
pwsh -NoProfile -File ..\scripts\dev-client.ps1
# 3) 离线先验（**改任何注入文本之前**必跑；不需要游戏）
cd <workspace>
pwsh -File sandbox\glslcheck\make_synthetic.ps1
# ⚠️ `-File` 模式**不认数组**：`-Extra @("a|b","c|d")` 会被当成一个参数、然后按 '|' 切成三份错路径
#    （实测报的错是"找不到 sandbox/glslcheck/pack_synthetic.fsh\",\"sandbox/...vsh"）。
#    每一对**单独调一次**：
pwsh -File sandbox\glslcheck\check.ps1 -Extra "sandbox\glslcheck\pack_synthetic.vsh|sandbox\glslcheck\pack_synthetic.fsh"
# 4) 装机后：把 Iris **真正编译的那份源码**直接拿来编译（最高保真，不需要重造注入）
pwsh -File sandbox\glslcheck\make_realcheck.ps1     # 断言版本 + 拷贝 dump
pwsh -File sandbox\glslcheck\check.ps1 -Extra "sandbox\glslcheck\pack_real2.vsh|sandbox\glslcheck\pack_real2.fsh"
# 5) 看回读：注入是否真的活着（uniform 位置、上传值 vs 回读值）
Get-Content <run>\patched_shaders\028_entities_cutout.fsh | Select-String 'mmtrFragmentLmCoord|uniform.*mmtr'
Get-Content <run>\dev-client.out.log | Select-String 'mmtrModelMat 回读|光影采样器|注入开关生效|光场占比'
```

（2026-09-28 §17.24 起，第 4 步从"剥壳 + 用 PowerShell 重造注入"改成"直接编译 Iris 的 dump"：
dump 本来就是注入之后、编译之前的那份源码，直接编译它比模拟更接近真相，也少了一整套自己会出错的代码。
版本靠 `make_realcheck.ps1` 里的硬断言钉住：片元 dump 必须含 `mmtrFieldMix`、顶点 dump 必须**不含** `mmtrLut`。）

### 17.23 "固定光源不行"的第一轮（**结论后来被推翻**）：`max()` 兜底 + 它留下的坑

用户报「**手持光源完全正常，为什么固定光源不行？**」。当时手上的证据只有两条：

1. `mix=0`（= MTR 那套 per-draw 光）时固定光源**能**照亮车厢，但是"整车一个值"；
2. 5 秒统计里那句 `车心光场采样=0/0 真实光照=6/15`。

由此推的结论是"光场在车体表面那些顶点上读到的方块光是 0，所以 per-draw 的光源被丢了"，于是加了兜底：

```glsl
float mmtrBlock = max(float(mmtrOriginalUv2.x >> 4), field.light.x);   // "绝不丢光源"
```

**这一轮的两个问题**（都在 §17.24 里算清）：

- **兜底句本身是错的方向**。`mmtrOriginalUv2` 是 MTR 的 **per-draw** 值 —— 对整辆车是**一个常数**。
  取 `max` 之后，这个常数就变成了**整辆车的亮度地板**：只要车附近有光，车身每个面（哪怕背光那面）
  都至少有这么亮 ⇒ 症状正是用户之前报过的"亮度完全统一、没有暗的地方"，
  而且**固定光源的衰减永远出不来**。用户对这版的反馈是"有反应了但不对"，与这条一致。
- **"光场读到的方块光是 0"这个前提没被证实**。那句诊断是**单点采样**（`floor(world − 0.5)`，
  见 `sampleCpu`），而 `真实光照` 是另一个点（`floor(world)`）—— 两者**差半格**，落在方块边界上时
  就根本不是同一个方块：车厢中心 y=65.00 → 单点探针读的是 y=64（**道床/地板内部**，天空光本来就是 0），
  真实光照读的是 y=65（空气，6/15）。所以 `0/0` 与 `6/15` 完全可以同时成立，
  它**证明不了**"光场里没数据"。

事后从更早一次的日志里找到了**反证** —— 光场其实一直带着方块光，而且与真实世界逐格一致：

```
draw #4: 平移=(-6.92, -4.38, 20.17) light=240 || A 口径世界=(-3800.53, 68.24, 1405.01) 光=6/15
         || B 口径世界=(-3773.80, 70.00, 1393.50) 光=0/15 (光场采样 5/15 / 0/0)
```

`A 口径世界` 的 `光=6/15` 与它旁边那格的光场采样 `5/15` 正好差 1 级（方块光每格衰减 1）
⇒ **采样值是对的，位置也对**。（`sampleCpu` 当时打的是 `floor(world − 0.5)` 那一格，
即 y=67 而 `真实光照` 是 y=68 —— 同样是"差半格"的对照。）

**教训**：诊断里"两个数字对不上"必须先证明**它们问的是同一个问题**（同一格、同一口径），
否则追下去的方向可以完全是错的。这次代价是一整轮 A/B（`max()` 兜底）＋一次客户端重启。
`sampleCpu` 后来在 §17.24 里换成了**与着色器同构的三线性镜像**。

### 17.24 真正的根因：**粒度**——固定光源必须和 held light 走同一条路（逐片元）

用户自己给出了方向：「既然手持光源是正常的，那能不能复用这个路径？」**能，而且这就是根因。**

**证据 1：包的手持光是"逐片元按世界距离"算的**（`lib/lighting/heldLighting.glsl`）：

```glsl
vec3 GetHeldLighting(vec3 playerPos, vec3 color, float emission) {
    vec3 playerPosLightM = playerPos + relativeEyePosition;
         playerPosLightM.y += 0.7;
    float lViewPosL = length(playerPosLightM) + 6.0;
    heldLight = pow2(pow2(heldLight * 0.47 / lViewPosL));      // ← 每个像素各算各的距离
    …
    vec3 heldLighting = pow2(heldLight * DoLuminanceCorrection(heldLightCol)) + …;
}
// 调用点（lib/lighting/mainLighting.glsl）：
blockLighting = sqrt(pow2(blockLighting) + heldLighting);
```

**证据 2：我们的注入**只**能到顶点阶段** —— 注入器第一版是这么写的：

```java
final String name = path.substring(path.lastIndexOf('/') + 1);
if (!name.endsWith(".vsh")) { return null; }        // ← 片元着色器直接放行，从不注入
```

于是固定光源唯一的入口是**光照贴图顶点属性**：MTR 的车体是**大面片低模**（一节车厢的侧面常常就是
几个四边形），几个角各采一次光、中间线性插值 ⇒ **取到的光是对的，落在面上是错的**：
面与面之间亮度会跳、相邻面对光的反应不同。用户前面几轮的反馈（"不同面对光的反射不同"、
"没变化/变差了"）全都指向这同一个东西；§17.22 的 A（采样点外移）+ B（逐 draw 一致性）无效，
也正好排除了"落点"和"来源混用"两个假设，剩下的就是**粒度**本身。

**证据 3：包在片元 main() 里已经把逐像素世界坐标算好了，白送**（`program/gbuffers_entities.glsl:137-144`）：

```glsl
vec3 screenPos = vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z);
vec3 viewPos   = ScreenToView(screenPos);      // 只用 gbufferProjectionInverse 反投影，**不读深度纹理**
vec3 playerPos = ViewToPlayer(viewPos);        // = 世界 − 相机（与 held light 同一个量）
…
vec2 lmCoordM  = lmCoord;                      // ← 我们只改这一行
```

`ViewToPlayer(pos) = mat3(gbufferModelViewInverse) * pos + gbufferModelViewInverse[3].xyz`，
而这个矩阵**是纯旋转（平移为零）**（§17.19 实测）⇒ `playerPos` 就是 `mmtrSampleField` 要的
"相机相对世界坐标"。**所以片元阶段不需要加 varying、不需要深度纹理、不需要 CPU 往返。**

#### 实现（三段）

1. **片元段注入**（新文件 `assets/minecraft/shaders/include/mmtr_lightfield_pack_frag.glsl`，
   385 行，含共享核心）：插在 `#version` 之后，内容是 `uniform` + 共享取光核心 +
   ```glsl
   vec2 mmtrFragmentLmCoord(vec2 mmtrLm, vec3 mmtrPlayerPos) {
       if (mmtrProbe == 5) return mmtrLm;                          // A/B：整条路关闭
       if (mmtrProbe == 1 || mmtrProbe == 3) return vec2(0.9, 1.0); // 诊断：满亮
       if (mmtrModelMat[3][3] == 0.0) return mmtrLm;               // 哨兵：不是 MTR 的 draw
       MmtrField field = mmtrSampleFieldLightOnly(mmtrCameraPosition(), mmtrPlayerPos);
       if (!field.valid) return mmtrLm;                            // 网格外/无数据 ⇒ 原样
       float origBlock = mmtrLm.x * 15.0;                          // 包的 lmCoord = 等级/15（x 被包夹到 0.9）
       float origSky   = mmtrLm.y * 15.0;
       float block = mix(origBlock, field.light.x, mmtrFieldMix);
       if (mmtrProbe == 4) block = max(origBlock, field.light.x);  // 旧的兜底版（§17.23），留着 A/B
       float sky = mix(origSky, field.light.y, mmtrFieldMix);
       return vec2(min(block / 15.0, 0.9), clamp(sky / 15.0, 0.0, 1.0));   // 回到包的空间
   }
   ```
   **值的口径不需要猜矩阵**：包自己在顶点段把光照贴图换算成了"等级/15"
   （`clamp((raw − 0.03125) * 1.06667, 0, 1)`，再把 x 夹到 0.9 = 包自己注释的
   "reducing the max blocklight"）⇒ 乘 15 就是等级、除 15 就回到包的空间。
2. **锚点改写**（文本替换，一行）：`vec2 lmCoordM = lmCoord;` →
   `vec2 lmCoordM = mmtrFragmentLmCoord(lmCoord, playerPos);`
   —— 下游（方块光曲线 `lightmapXM`、天空光、阴影、AO、漫反射）**一个字都不用改**，
   而且 `probe`/`mix` 之外没有任何我们自己的状态泄漏到包里。
3. **顶点段的取光改成恒等**（`mmtrPickUv2` 直接返回入参），只保留**法线包装**
   （`mmtrNormalFromPack`，§17.19）。为什么必须退掉：逐顶点那道近似一旦仍旧写进 `lmCoord`，
   片元再取一次就是"在已经被插值糊过的值上做 max"，面内的假梯度反而被固化。包装函数留着不删，
   是为了将来若要回退只改函数体。**顶点段不再 include 共享核心**（片元段才有）。

**失败一定是安全的**：片元锚点找不到 ⇒ 只插函数、不改包源码（函数成死代码）；文本读不到 ⇒ 整段不注入。
两条都只记一行日志，包源码保持原样（不会有编译错误、不会黑屏）。

#### 离线验证（不启动游戏，`sandbox/glslcheck`）

`make_synthetic.ps1` 现在生成**一对** `pack_synthetic.vsh|fsh`；`make_realcheck.ps1` 则是**直接拿
Iris 的 dump**（`run/patched_shaders/028_entities_cutout.vsh|fsh` —— 它本来就是"注入之后、编译之前"
的那份源码）先做版本断言、再拷成 `pack_real2.vsh|fsh` 交给编译器。两对都编译+链接通过，
并且**确认注入的 uniform 在片元阶段是活的**（这一条很关键 —— 见下面第 3 条教训）：

```
  uniform mmtrLut = 40   mmtrAtlas = 41   mmtrSolid = 42
  uniform mmtrModelMat = 28   mmtrNormalFix = 27   mmtrProbe = 26   mmtrFieldMix = 43
  active uniforms = 62 | mmtr 那组： mmtrModelMat@35676 mmtrNormalFix@5124 mmtrProbe@5124
                                  mmtrAtlas@36306=0 mmtrFieldMix@5126 mmtrLut@36306=0 mmtrSolid@36306=0
```

（`GlslCheck` 顺手加了一段 `GL_ACTIVE_UNIFORMS` 枚举输出 —— 它就是运行时 `PackSamplerBinding`
看到的那个集合；`36306 = GL_UNSIGNED_INT_SAMPLER_2D`、`35676 = GL_FLOAT_MAT4`、`5124 = GL_INT`。）

#### 实机验证（2026-09-28 20:39，Complementary Reimagined r5.9.3）

注入落地（日志原文）：

```
光影注入：顶点段文本已备好（3493 字符）
光影注入：片元段文本已备好（15383 字符，核心 11665 字符）
光影注入 /world0/gbuffers_entities.vsh：已注入顶点段（顶点 main 在第 11584 行 …）
光影注入 /world0/gbuffers_entities.fsh：已注入片元段（piece F 在第 2 行，385 行；lmCoordM 锚点命中 1 处 → 共 12041 行）
光影注入 /world0/gbuffers_entities_translucent.fsh：… lmCoordM 锚点命中 1 处 …
光影注入 /world0/gbuffers_entities_glowing.fsh：… lmCoordM 锚点命中 1 处 …
```

Iris 真正交给编译器的源码（`run/patched_shaders/028_entities_cutout.fsh`，**8 个实体变体全部命中**：
`alpha / solid / solid_diffuse / solid_bright / cutout / cutout_diffuse / translucent / text_bg`）：

```
204| vec2 mmtrFragmentLmCoord(vec2 mmtrLm, vec3 mmtrPlayerPos) {
1227| vec2 lmCoordM = mmtrFragmentLmCoord(lmCoord, playerPos);
```

运行时回读（**片元段真的在跑**，而且真实 `lmCoord` 不再被顶点段动过）：

```
光影采样器：program 257 已用单元={0, 1, 5} → 光场用 8/9/10（LUT/图集/实心位图）
  | 片元单元上限=32 | 该程序的 sampler：iris_overlay@1 mmtrAtlas@0 mmtrLut@0 shadowcolor0@5 tex@0
  | 附着着色器=2 [FS 159457 字符 mmtrLightUv=0 mmtrFragmentLmCoord=2 iris_UV2=0]
                [VS 24221 字符 mmtrLightUv=0 mmtrFragmentLmCoord=0 iris_UV2=2]
注入开关生效：program 257 normalFix=0（回读 0）probe=0（回读 0）
mmtrModelMat 回读（program 257）：平移=(-27.20, 15.87, -213.53) [3][3]=1.00
性能：平均帧时=15.08 ms（≈66.3 fps，332 帧）
```

零 shader 编译错误、零 GL 错误。**用户实测："固定光源好了，而且有距离衰减（不再整车一个值）"。**

#### 三条可复用的教训

1. **"逐顶点"不是"精度差一点"，而是结构性错的粒度**。只要包的另一个同类效果（held light）是逐片元的，
   而我们的差了一个数量级的粒度，就一定会被用户一眼看出来。**先去找包里"已经做对的那条同类路径"**，
   复用它的坐标与注入点，比自己在顶点阶段堆修正便宜得多。
2. **注入点要挑"包自己刚算好、后面全都靠它"的那个变量**。这一轮是 `lmCoordM`：改一行，
   下游（曲线/阴影/AO/漫反射）全部照旧。反过来，"另算一个亮度再加进去"（像 held light 那样加法）
   会绕开包的方块光曲线，颜色与阴影都会与地形对不上。
3. **`glGetUniformLocation` 为 -1 会把"某个功能不可用"升级成"整条路不可用"**。
   `PackSamplerBinding.create` 原来要求 `mmtrLut/mmtrAtlas/mmtrSolid` **三个都 ≥0**，而片元阶段
   并不消费 AO ⇒ `mmtrSolid` 完全可能被编译器判成死代码。已改成只要求 LUT/图集，
   `mmtrSolid < 0` 时只跳过它自己那一份绑定（实测同一驱动上它是活的，但**不能再靠这个**）。

#### 留下的活开关与已知不足

- `run/mmtr-lightfield.properties`（2 秒生效、不用重启）：`enabled`、`ao`、`normalFix`、`probe`、`mix`。
  **`probe` 语义**：`0` 正常；`1`/`3` 光照满亮（含法线钉死）；`2` 只钉法线；`4` = §17.23 的 `max` 兜底版；
  `5` = 整条路关闭（等价于 `mix=0`）。**`mix`**：0 = 纯 per-draw，1 = 纯光场（方块光与天空光一起混合）。
- **AO 在光影包这条路上还没接**（`mmtrSampleFieldLightOnly` 整段跳过 AO 的 27 次实心取位）：
  每像素 8 次图集取位已经很贵，AO 要等"逐片元 AO 怎么与包的 `noVanillaAO`/`signMidCoordPos` 共存"想清楚。
  没有光影包那条路（MTR 自己的 program）AO 照旧。
- **`车心光场采样` 那句诊断还是单点**（`floor(world − 0.5)`），与 `真实光照`（`floor(world)`）差半格，
  读起来仍会误导（实机日志里 `车心光场采样=0/0 真实光照=6/15` 就是这么来的：前者落在道床里）。
  下一步该把它换成**与着色器逐字同构的三线性镜像**（CPU 侧把 8 个 tap 混出来），
  这样日志里的数字才等于着色器真正拿到的值。
- `config/iris.properties` 里的 `enableDebugOptions=true` 还在（`patched_shaders` 的 dump 靠它）。
  不再需要看 dump 时应该关掉（每次装载都会把全部程序写到磁盘）。

## 18. 交付总结（2026-09-28 收尾）

**用户判定（原话）**：「现在光影,非光影部分都全部拥有良好的外观」。
即**两条路**都达到交付标准：有光影包（片元段注入，§17.24）、无光影包（MTR core shader，§8–§14）。

### 18.1 最终默认值

`run/mmtr-lightfield.properties`（每 2 秒重读；uniform 类开关不用重启）：

```properties
enabled=true      # 光场总开关
ao=1.0            # 世界 AO 强度（只有"无光影包"那条路消费）
debug=false       # 假色片元视图（只有"无光影包"那条路）
screenshot=0
normalFix=0       # 包内法线空间修正（只有"有光影包"那条路；实测眼睛分不出，留 0）
probe=0           # 0 正常 / 1,3 满亮 / 2 钉法线 / 4 max 兜底(旧) / 5 关闭
mix=1.0           # 光场占比（0 = 纯 per-draw，1 = 纯光场）
reload=modelmat2  # 改这个值 = 手动触发一次资源重载
```

要**整体回退**到改动前的样子（MTR per-draw 光）：`enabled=false`（两条路一起回退，2 秒生效，不用重启）。

### 18.2 收尾时做的四件事

1. **取光从顶点阶段搬到片元阶段**（§17.24）：这是"固定光源"那轮的根因修复，也是最终形态。
   顶点段的 `mmtrPickUv2` 变成**恒等包装**（留着包装是为了回退时只改函数体），只保留法线包装；
   顶点段不再 include 共享核心。
2. **撤掉 `max(per-draw, 光场)` 兜底**：per-draw 值是整车的**一个常数**，取 max 等于给它铺一层亮度地板，
   固定光源的衰减永远出不来（§17.23 记录了这个错误判断，以及我为什么当时看不出来）。
3. **`mmtrSolid` 改为可选绑定**：片元阶段不消费 AO，`mmtrSolid` 被编译器判成死代码是完全可能的；
   旧判据"三个 uniform 必须都在"会把"AO 不可用"升级成"整条取光静默失效"（§17.24 教训 3）。
4. **诊断补强**：采样器绑定那行现在还会打出**该 program 的全部 sampler（名字@单元）**、片元单元上限、
   以及附着着色器里的 `mmtrFragmentLmCoord` 计数 —— 一眼分辨"注入没进去 / 进去被优化掉 /
   采样器抢了包自己的单元"三种失败（§17.24 的实机证据就是这几行）。

### 18.3 文档

- **定稿口径真源**：[`../docs/01-设计/光场光照-车厢世界光注入-设计.md`](../docs/01-设计/光场光照-车厢世界光注入-设计.md)
  —— 两条路对照表、网格/图集/LUT/实心位图的逐字布局、注入机制与安全失败、开关表、验收读法、
  12 条踩坑清单、文件地图。`docs/README.md` 的 01-设计 表已加行。
- 本篇（notes/344）保留**过程与证据**：每一轮的错误结论、为什么错、以及推翻它的那条日志/字节码。

### 18.4 交付时的验证快照（2026-09-28 20:39–20:50）

| 项 | 值 |
| --- | --- |
| 注入 | 顶点段 4 个 program（entities / _translucent / _glowing / beaconbeam）；片元段 3 个 `.fsh`，锚点各命中 **1** 处 |
| Iris dump | `028_entities_cutout.fsh`：`204\| vec2 mmtrFragmentLmCoord(...)`、`1227\| vec2 lmCoordM = mmtrFragmentLmCoord(lmCoord, playerPos);` |
| 采样器 | `program 257 已用单元={0,1,5} → 光场用 8/9/10`；该 program 的 sampler = `iris_overlay@1 mmtrAtlas@0 mmtrLut@0 shadowcolor0@5 tex@0` |
| 逐 draw 矩阵 | `mmtrModelMat 回读：平移=(-27.20, 15.87, -213.53) [3][3]=1.00` |
| 开关回读 | `注入开关生效：normalFix=0（回读 0）probe=0（回读 0）` |
| 错误 | `glError=0`、零 shader 编译错误 |
| 性能 | 平均帧时 15.1–17.0 ms（≈59–66 fps）；`draws/frame=2326.5`、`batches/frame=7.0` |
| 离线 | `pack_synthetic` 对 + **Iris 真实 dump 对** 全部编译+链接通过，注入 uniform 在片元阶段全部存活 |

### 18.5 遗留（都已写进设计文档 §9，不阻塞交付）

1. 光影包那条路的**世界 AO 未接**（每像素再加 27 次实心取位，且要与包的 `noVanillaAO` 共存）。
2. `车心光场采样` 诊断仍是**单点且差半格**（`floor(world−0.5)` vs `floor(world)`），
   应升级成与着色器逐字同构的三线性镜像 —— 否则下一个人还会被 `0/0` vs `6/15` 误导一次（§17.23 的教训）。
3. 每 5 秒 5 行诊断日志：交付后若要静音，需要一个 `verbose=` 开关（默认维持现状）。
4. `run/config/iris.properties` 的 `enableDebugOptions=true` 是**为看 dump 打开的**（本轮 dump 123 个文件）；
   不再需要时应关掉，需要时再打开（`make_realcheck.ps1` 依赖它）。
5. **车灯在光影包那条路上**（§18 之外的另一件事，2026-09-29）：本篇的注入只做"世界光"（piece F，
   修的是 MTR 那个 draw 的光照值）；**车灯**（piece H）见 **notes/351** ——
   同一套注入机制，锚点换成包的 `DoLighting(color, …)`，加性叠加而不是改光照贴图值。
6. **车灯的判据换了**（2026-09-29 晚，notes/352）：从"按行进方向猜前/尾灯（停着不动只能含糊）"
   换成**每个驾驶室一个三档灯光开关 + 端 + 换向器 N 固定红**（引擎权威、镜像下发、纯函数判据）；
   同时**尾灯不再照世界**（上传到地形程序时被过滤）。本篇 §18.5 第 5 条那套 piece H 注入不变。













