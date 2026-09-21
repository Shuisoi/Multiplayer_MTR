# 226 上车后收起物品栏与手 —— 以及把"只有进游戏才看得见"的错误提前到离线

用户口径：**「上车后能隐藏物品栏和手吗」**。

## 1. 做了什么

两个 mixin，判据同源：

| 目标 | 注入点 | 收起来的东西 |
| --- | --- | --- |
| `InGameHud` | `renderHotbar(FLnet/minecraft/client/gui/DrawContext;)V` HEAD，`cancellable` | 物品栏（含副手格） |
| `HeldItemRenderer` | `renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/network/ClientPlayerEntity;I)V` HEAD，`cancellable` | 第一人称的手/物品 |

判据放在 `MmtrVanillaHud.hideHotbarAndHand()` 一处，内容是
`MmtrDriverSeat.ridingVehicle() != null` —— 即**骑着任何一辆 MTR 车**（乘客上车走
`VehicleRidingMovement.startRiding`、进驾驶室走 `mmtrEnterCab`，两条路都会置上 `ridingVehicleId`，
而且它还会确认这辆车在客户端车辆列表里，所以"刚下车那一拍"不会误判）。
只想在司机位收起来，把那一行换成 `MmtrDriverSeat.isAtControls()` 即可。

**没动的东西**（用户说的是"物品栏"，不是"整条 HUD"）：血量、饥饿、经验条、准星、聊天照旧。
要连整条一起收，改的是 `GameOptions.hudHidden`（等同按 F1），不是这两个 mixin。

**只掐了正确的那一个 `renderItem` 重载**：`HeldItemRenderer` 有两个 `renderItem`，另一个
`renderItem(LivingEntity, ItemStack, ModelTransformationMode, boolean, MatrixStack,
VertexConsumerProvider, int)` 是**任何实体**手里拿的物品（含别的玩家）。掐错那个 = 把所有玩家
手里的东西都抹掉。第三者视角下本地玩家的手持物走实体渲染，不受影响。

## 2. 真正值得记的是新增的离线守卫

`@Inject(method = "…")` 里的名字/描述符**就是个字符串**：

- 写错了**编译期毫无反应**；
- 要到**游戏启动、mixin 应用时**才炸 `InjectionError` —— 那时人已经在游戏里；
- 而且它和"贴图落在屏幕哪里"是同一类问题：**只有进游戏才看得见**（notes/224b 的教训）。

所以这一轮把它做成离线判据：`sandbox/run-mixin-probe.ps1` + `sandbox/MixinTargetProbe.java`。

做法不是解析 `javap` 文本（那本身就会引入新的格式 bug），而是：

1. 把 `org/mtr/mixin/*.java` 当文本读，抠出 `@Mixin(X.class)` 与每个 `method = "名字(描述符)返回"`；
2. 用文件里的 `import` 把 `X` 还原成全名；
3. 用 `URLClassLoader` 挂上**映射名**的 Minecraft jar，反射拿 `getDeclaredMethods()`，
   描述符由 JDK 的 `Type.getMethodDescriptor` 逻辑自己生成（`descriptorOf`），逐字比对；
4. 只写名字（不带描述符）时，退化成"必须存在同名方法"。

输出：

```
OK  ClientWorldRenderingMixin.java → net.minecraft.client.world.ClientWorld（方法 1 个）
OK  HeldItemRendererMixin.java → net.minecraft.client.render.item.HeldItemRenderer（方法 1 个）
OK  InGameHudMixin.java → net.minecraft.client.gui.hud.InGameHud（方法 1 个）
OK  PlayerRendererOffsetMixin.java → net.minecraft.client.render.entity.PlayerEntityRenderer（方法 1 个）
核对 4 个注入目标，失败 0 个
```

顺带把**已有**的两个客户端 mixin 也纳入了核对（它们以前没人验证过）。

**red-proof（真跑）**：把 `renderHotbar(FLnet/minecraft/client/gui/DrawContext;)V` 故意写成
`renderHotbar(Lnet/minecraft/client/gui/DrawContext;F)V`（参数顺序颠倒），守卫立刻 FAIL，并且把
同名方法的真实签名打出来帮人对照：

```
FAIL InGameHudMixin.java：net.minecraft.client.gui.hud.InGameHud 里没有
     renderHotbar(Lnet/minecraft/client/gui/DrawContext;F)V
     （同名方法：renderHotbar private void renderHotbar(FLnet/minecraft/client/gui/DrawContext;)V）
```

## 3. 验收

- 三个新文件 `javac` 通过（`MmtrVanillaHud` / `InGameHudMixin` / `HeldItemRendererMixin`）；
- mixin 守卫：4/4 目标存在（含描述符精确匹配），red-proof 能判 FAIL；
- `mtr.mixins.json` 的 `client` 数组已加入两个 mixin。

**进游戏要验的三件事**（离线验不了，列出来免得漏）：① 上车后物品栏消失、下车回来；
② 第一人称的手消失，但**别的玩家**手里的物品还在（第三方视角看别人拿着东西）；
③ 按 F1 与坐车互不影响。

## 4. 提交时踩到的坑：`org/mtr/mixin/` 被 .gitignore 挡着

第一次提交这一轮改动，`git status` 看着干净、提交也"成功"，但**两个新 mixin 根本没进去**：
`game/.gitignore` 第 36 行有一条 upstream 留下的规则

```
**/org/mtr/mixin/
```

于是这个目录里**新建**的文件会被 git 静默忽略（`git add <路径>` 只给一句 hint，
混在 LF/CRLF 警告里很容易漏看）。更要紧的是：查下来**这个目录从来没有被提交过任何文件**
（`git ls-files` 为空、`git log` 无历史），而 `mtr.mixins.json` **是**跟踪的、并且引用着其中的类
—— 也就是说**从仓库干净克隆根本起不来**。

处理：这一轮用 `git add -f` 把自己的两个 mixin 加进仓库；另外三个（`ClientWorldRenderingMixin`、
`PlayerRendererOffsetMixin`、`PlayerTeleportationStateAccessor`）不是本轮写的，**没动**，
留给用户决定是改 `.gitignore` 还是逐个 `-f` 加。

顺手把这条坑做进守卫：`run-mixin-probe.ps1` 在核对完目标后会 `git ls-files` 一遍，
任何"在盘上但没被跟踪"的 mixin 都会列出来并提示 `git add -f`：

```
提醒：这些 mixin 源码**没有被 git 跟踪**（.gitignore 的 **/org/mtr/mixin/ 把它们挡了）：
  - HeldItemRendererMixin.java
  - InGameHudMixin.java
  ...
  提交时要 git add -f；否则 mtr.mixins.json 引用着仓库里不存在的类。
```

## 5. 附：这一轮工作区里有**另一个会话在同时改代码**

跑全量编译时出现了两次**间歇性**失败（同一棵树前一次 OK、后一次 FAILED），报错位置在：

```
Init.java:456 / MmtrBoardPlayer.java:84,100 / MmtrCommandExecutor.java:338（找不到符号）
```

这些属于一套"board player（让玩家上车）"的改动（`MmtrBoardRequest` / `MmtrBoardPlayer` /
`PacketMmtrBoardPlayer` + `Init` 注册），**没有进版本库、也不是我写的**，时间戳落在本轮工作期间。
结论：工作区里有并行编辑在进行，全量编译的失败来自那批**正在改到一半**的文件。
本轮提交只包含我自己的 5 个文件；那批并行改动保持原样、未触碰、未提交。
**因此如果你在 IDEA 里编译整个客户端，可能会先在那些文件上失败** —— 那是并行编辑的中间状态。
