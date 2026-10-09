# 394 客户端 `submit` 高：逐 draw 的三次 program 查询（含两条被证伪的结论）

> 用户口径（2026-10-05）：「这应该与 CPU 方面光场、列车实体渲染强相关吧，和阴影距离有个毛的关系？
> 降低游戏窗口帧率都不变，和 GPU 没关系啊」→「视觉有些东西只需要每帧一次刷新就行了，再快有啥用呢，
> 开始做修改吧」。

探针读数（`[MMTR-FRAME]`）见 `docs/02-运行与作业/性能探针-运行手册.md` §8；本轮的判据与验收口径
已写进那里的 **§8.2 D9** 与 **§8.5**，本篇只记**判决过程**与**两条被证伪的路**。

## 0 结论速查

| 症状 / 想法 | 实际 | 依据 |
|---|---|---|
| 「分辨率拉到极低帧率不动」 | 主因是**逐 draw 的 CPU 成本**，不是 fill。分辨率只减片元，**一个 draw 都不少** | §1 |
| 「`submit`=11.3ms 是 GPU 提交/等 GPU」 | `render` 路径上**没有** `glFinish`/`glFlush`/swap（grep 实证）⇒ 它量的是 draw 循环里的 CPU 工作 | §1 |
| 「`main.submit` 11.3 vs `shadow.submit` 8.7，阴影也不便宜」 | 两遍**像素量差一个数量级以上**、价钱却相当 ⇒ 随 **draw 数**走，不随像素走。这正是 CPU 逐 draw 的形状 | §1 |
| 「阴影距离 32→8 能救」 | **错杠杆**（§3①）。会按比例省下 `shadow.submit` 的一部分，但只碰 8.7ms 那一半、要拿画质换，且病根（每 draw 多收 207 倍的钱）一动没动 | §3 |
| 「模型矩阵可以按内容去重」 | **做不了**（§3②）。`draw()` RETURN 每个 draw 都把 `mmtrModelMat` 清成全零哨兵 ⇒ GL 里那个 uniform 的真实值每个 draw 都不一样 | §3 |
| 逐 draw 挂点有几个 | **三个**，不是两个。`clearPackModelMatrix` 那个最容易漏（它不在 `MmtrLightField.onDrawState` 里，挂在 `BatchManagerRenderCallMixin` 的 RETURN 上） | §2 |
| 改完 beware | `game/.gitignore:36` 的 `**/org/mtr/mixin/` 把整个 mixin 层挡在版本控制外（既存坑，notes/226 §4、notes/348） ⇒ 本轮的 `ShaderManagerMixin` 改动**不会进提交** | §5 |

## 1 判决：为什么是 CPU 逐 draw

一开始把 `submit`（= `OPTIMIZED_RENDERER_WRAPPER.render(...)`，`MainRenderer` 只包这一行）当成"GPU 提交"，
并从"阴影贴图分辨率与屏幕分辨率无关"推出"去调阴影距离"。三处查证把这条推翻：

1. **`org/mtr/mod/render` 整包里没有 `glFinish` / `glFlush` / `glClientWaitSync` / `swapBuffers`**（grep 零命中）
   ⇒ `submit` 的墙钟里没有显式的 GPU 同步点。
2. **`main.submit` ≈ `shadow.submit`（11.3 / 8.7 ms）**，而两遍的像素量差一个数量级以上。
   fill/带宽主导的话主 pass 应当远贵于阴影 —— 所以它是**逐 draw** 的量。
3. **用户实测：降分辨率帧率不变**。这一条最直接：分辨率只减片元、不减 draw 数。

（§2 的算术又反过来印证：`draws/frame=2487`、`batches/frame=12.00`，`submit` 摊下来 **4.5 µs/draw** ——
正是五六个驱动调用的价钱。）

**用户的两个直觉其实是一笔账**：光场/车灯的钩子挂在 `VertexAttributeState.apply()` HEAD 上，而
`apply()` 是**每个 draw** 调一次 —— 所以"光场 CPU"与"列车实体渲染"不是两个原因，
是**同一个原因的两个名字**（税收在每个列车 draw 上）。

## 2 三个逐 draw 挂点

调用顺序（读 `ShaderManagerMixin` + `BatchManagerRenderCallMixin` + MTR 二进制库的注释得出）：

```text
setupShaderBatchState HEAD      program 还没 bind   （onBatch / logDrawTimeMatricesOnce / bindLutForBatch）
   （MTR 内部 bind program）
setupShaderBatchState RETURN    program 已 bind     （bindPackSamplers）   ← 每批次一次的挂点
   for each RenderCall in this batch:
       draw() HEAD    → countDraw
       apply() HEAD   → MmtrLightField.onDrawState   ← 挂点①（+ 挂点②）
       glDrawElements
       draw() RETURN  → clearPackModelMatrix()       ← 挂点③，同时把 mmtrModelMat 清零
```

| 挂点 | 逐 draw 做的事 | 一个批次内会变吗 |
|---|---|---|
| ① `uploadPackModelMatrix` | `glGetInteger(GL_CURRENT_PROGRAM)` + `packSamplerBindings.get` + `glUniformMatrix4fv` + 3× `glUniform1i` | program **不变**；三个标量**不变** |
| ② `MmtrHeadlights.upload` | `glGetInteger` + `locationsByProgram.get` | **不变** |
| ③ `clearPackModelMatrix` | `glGetInteger` + `packSamplerBindings.get` + `glUniformMatrix4fv`（清零） | **不变** |

**算术**：`draws/frame=2487`、`batches/frame=12.00` ⇒ 每批次 **207 个 draw**。
乘 `通行=2.00` ⇒ 一个视觉帧 ~4974 个 draw：

- `glGetInteger(GL_CURRENT_PROGRAM)`：**~1.5 万次/帧**，而上限只需 **24 次**（两遍 × 12 批次）
- 那 3 个标量 uniform：**~1.5 万次/帧**，而真正需要写的次数是 **0**（源值 2 秒才重读一次 properties）

> **差点误判的一点**：`MmtrHeadlights.upload` 里 `locations.everOurs` 那支"每次上传前复核 7 次
> `glGetUniformLocation`"看起来是逐 draw 的，其实**不是** —— `uploadedFrame == collectingFrame` 的
> 提前返回在它**之前**，所以逐 draw 只花一次 `glGetInteger` + 一次 HashMap 查询。翻代码时容易看漏这个次序。

## 3 两条被证伪的结论（别再试）

### ① 阴影距离不是这个现象的杠杆

降 `maxShadowRenderDistance` 确实会减少阴影 pass 的 draw 数，而成本逐 draw，所以它会**按比例**
省下 `shadow.submit` 的一部分。但：拿画质换钱、只碰 8.7ms 那一半、主 pass 那 11.3ms 的同一笔税一动没动。
措辞上的教训是实的：**"阴影贴图分辨率与屏幕分辨率无关"是解释，不是处方** —— 它说明"降到多低都没用"，
并不指向阴影距离是个便宜开关。运行手册 D1 早先的写法正是这个错误，已改。

### ② 模型矩阵按内容去重不成立

`BatchManagerRenderCallMixin.mmtrClearPackModelMatrix`（`draw()` RETURN）**每个 draw** 都调
`clearPackModelMatrix()` 把 `mmtrModelMat` 置**全零哨兵** —— 目的是让同 program 的**原版实体**
（它们没有 MTR 的 ModelMat）不会误用上一个 MTR draw 的矩阵。
于是 GL 里那个 uniform 的真实值**每个 draw 都不一样**（我们写了矩阵 → 清零 → 下一个 draw 再写），
"和上次相同就跳过"会直接错。**这条别再试。**

## 4 改法与安全性

（`submit` 的语义与画面都不变；只改"上传次数"。）

1. **program 按批次缓存**：`setupShaderBatchState` RETURN 处取一次（`MmtrLightField.onBatchProgramBound`），
   批次 HEAD、优化渲染器退出（`MainRenderer`）、包重载（`clearPackSamplerCache`）三处作废。
   **批次外自动回落到真正的查询 = 完全等于改前行为**，所以最坏情况只是没优化。
   依据：`RenderCall.draw()` 只绑 VAO、传逐 draw 顶点属性、`glDrawElements`，**不重绑 program**。
   地形那一路（Sodium `GlProgram.bind()`）**不在批次循环里** ⇒ `uploadForTerrainProgram()` **始终现查**，
   不吃这份缓存（这一条是必须分开的理由：否则会拿上一帧最后一个批次的 program 去查地形的 uniform 位置）。
   三个查询点改读缓存：`uploadPackModelMatrix` / `clearPackModelMatrix` / `bindPackSamplers`。
2. **三个开关标量"变化才传"**：uniform 是 **program 的状态**（写一次就一直保持，除非重新 link / 程序被销毁），
   所以按 program 记住上次写进去的值即可 —— 加在 `PackSamplerBinding` 上（它本来就是按 program 建的）。
   源值一变立刻上传 ⇒ 仍是"改 properties 不用重启"。
   `PackSamplerBinding.NONE` 是共享哨兵，三个 location 全 −1，走不到这段逻辑，所以不会被写脏。
3. **`MmtrHeadlights.upload()` 改成 `upload(int programId)`**，两个入口各自负责 program 的来路
   （逐 draw 走缓存 / 地形现查），`canUpload()` 前置守卫保持"关掉时不查 GL"的旧行为。

**没有新增任何 `@Inject` 注入点**（只在既有 injector 方法体里加语句）⇒ 不会新增
`defaultRequire = 1` 的注入失败面。

## 5 验收与两个坑

**验收**：同一编组、同一视距、同一路线，前后各取一个 5 秒 `[MMTR-FRAME]` 窗口，比：

| 读数 | 期望 |
|---|---|
| `main.submit` / `shadow.submit`（`累计ms ÷ 帧`） | 明显下降 |
| `已量段合计` ÷ `帧间隔 p50` | 覆盖率上升（改前 66–75%） |
| `optimizer draws/frame`、`batches/frame` | **必须不变** |
| 画面 | 车厢明暗、车灯、光场占比与改前一致 |

**上界对照**（零代码）：`run/mmtr-lightfield.properties` 的 `enabled=false` 会**同时**短路三个挂点，
给的是这笔开销的上界（代价是失去光场视觉效果），**不等于**修完能省这么多。

**坑一（既存）**：`game/.gitignore:36` 的 `**/org/mtr/mixin/` 把整个 mixin 层挡在版本控制外
（notes/226 §4、notes/227、notes/348 都记过）。本轮 `ShaderManagerMixin` 的改动**不在** `git status` 里，
`git clean -xdf` 会删掉它。要提交得 `git add -f`。

**坑二（既存）**：编译校验通过（`check-java-compile.ps1 -Quiet` → 504 源文件 / 760 类，exit 0）**不代表**
mixin 能注入成功 —— 那个脚本不跑 Mixin 注解处理器，注入点的正确性只有真机启动才验得到。
按 notes/281，重编前先删 `mmtr/game/fabric/build/classes/java/main`，否则 Gradle 会报 UP-TO-DATE。
