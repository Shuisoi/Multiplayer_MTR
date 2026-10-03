# 287 · 快速建筑模组：WorldEdit 给服务端 / Axiom 给客户端，**按端注入**（不能放 run\mods）

> 用户口径（2026-09-25）：**「给装一个快速建筑的模组」**。问过一轮后定案：WorldEdit 7.3.0 + Axiom 5.4.2。

## 1. 装了什么

环境：MC **1.20.4** / Fabric Loader **0.19.5** / fabric-api **0.97.3+1.20.4**（dev 环境里已加载 60 个模组）。
1.20.4 Fabric 上可用的三个候选里（Building Gadgets / Effortless Building **没有** 1.20.4 Fabric 版本，装不了）：

| 模组 | 版本 | 大小 | sha512 | 装给谁 |
| --- | --- | --- | --- | --- |
| WorldEdit | 7.3.0（`worldedit-mod-7.3.0.jar`） | 5.84 MB | 已校验 ✔ | **服务端** |
| Axiom | 5.4.2（`Axiom-5.4.2-for-MC1.20.4.jar`） | 44.85 MB | 已校验 ✔ | **客户端** |

jar 本体放 **`vendor\mods\`**（第三方只读输入目录，与 MTR/fabric-api 的 jar 并排；不入库）。
下载走 Modrinth CDN，**sha512 逐个比对**（WorldEdit 直接下；Axiom 45 MB 用 `Invoke-WebRequest` 会超时，
改用 `curl.exe -L --retry 10 -C -` 断点续传，见 `logs\2026-09\mods-installed-*.txt`）。

## 2. 硬坑：放进 `run\mods` 会让"服务端在跑 → 客户端起不来"

dev 服务端与 dev 客户端**共用** `game\fabric\run\`（同一份 `mods/`、`options.txt`、`world/`、`logs/`），
也就共用 `.fabric\processedMods` 这个 **Fabric 运行时重映射缓存**。生产版模组（run/mods 里的 jar）在 dev
环境里每次启动都要被重映射一次，而重映射**每次都重写**输出 jar；先起的那一端正锁着它。实测：

```
[15:16:47] [main/ERROR] (FabricLoader) Failed to remap mods!
 net.fabricmc.loader.impl.FormattedException: java.nio.file.FileSystemException:
   ...\run\.fabric\processedMods\axiom-5.4.2-64b5720b4b825f21.jar: 另一个程序正在使用此文件，进程无法访问。
	at ...RuntimeModRemapper.remap(RuntimeModRemapper.java:111)
```

顺序反过来一样死（客户端先起，服务端就起不来）—— 只要两端都要重映射**同一个** jar，就**永远**不能同时跑。
先查过 Fabric Loader 0.19.5：`RuntimeModRemapper` 只认 `fabric.remapClasspathFile`，**输出目录没有开关**
（由 `FabricLoaderImpl` 按 gameDir 拼死），所以"给缓存目录换个位置"这条路不通。

## 3. 修法：按端注入（`-Dfabric.addMods`）

把两个 jar 移出 `run\mods`，改由 loom 的 run 配置分别注入 —— 服务端只吃 WorldEdit、客户端只吃 Axiom，
两端重映射的是**不同文件**，互不锁：

```groovy
// mmtr\game\fabric\build.gradle（loom 块内）
runConfigs.configureEach {
	ideConfigGenerated = true
	if (name == "server") { property "fabric.addMods", worldEditJar.absolutePath }
	else if (name == "client") { property "fabric.addMods", axiomJar.absolutePath }
}
```
两个路径由 `file("${rootDir}/../../vendor/mods/…")` 算出（**不写绝对路径字面量**，`check-paths.ps1` 会查），
且**缺文件就 `throw new GradleException`** —— 这套流程最恨"模组没装上却看不出来"。
带空格的工作区路径没问题：实测启动命令行里 `-Dfabric.addMods` 的值就是 `vendor\mods\` 下那个 jar 的
绝对路径（工作区路径里那个空格原样保留），loom 的空格转义（`@@0020` 那一套）照常生效。

## 4. 验证（两端**同时**在跑，这是本轮唯一算数的判据）

| 判据 | 结果 |
| --- | --- |
| 服务端模组表 | `Loading 45 mods:` 内含 `worldedit 7.3.0+6678-55745ad`，**不含 axiom** |
| WorldEdit 起来了吗 | `WorldEdit for Fabric (version 7.3.0…) is loaded` + `Registering commands … FabricPlatform`；`config\worldedit\worldedit.properties`、`schematics\`、`sessions\` 都已生成 |
| 客户端模组表 | `Loading 64 mods:` 内含 `axiom 5.4.2`（+ 内嵌 `com_moulberry_axiomclientapi / mixinconstraints / lattice`），**不含 worldedit** |
| 客户端进游戏 | `Initializing Axiom/5.4.2` → `Sound engine started`（到达主菜单） |
| **两端共存** | `runServer PID 17360` + `runClient PID 43460` 同时存活，服务端 8888 照常应答（rails=0，仍是新图） |
| 指令面 | RCON `worldedit version` → `7.3.0` + 平台/能力清单；`worldedit help` 列全指令 |

## 5. ⚠️ MTR 侧的口径：**这两把工具造不出"引擎里的轨"**

查过源码，轨进引擎只有两条路：`ItemRailModifier`（MTR 的铺轨物品）→ `PacketUpdateData.sendDirectlyToServerRail`，
以及引擎指令 `rail add`；节点删除钩在 `BlockNode.onBreak2`（**玩家敲掉**才发 `PacketDeleteData`）。

⇒ WorldEdit / Axiom 直接 setBlock 出来的轨方块、**复制粘贴的整段轨道**、以及它们拆掉的轨，
**都不会进引擎**：网页地图看不见、车也跑不上去。所以分工是：
**地形 / 路基 / 站台 / 建筑 / 装饰用 WorldEdit 与 Axiom；轨一律用铺轨物品或 `rail add`**（notes/233 §3 同款结论）。

## 6. 用起来

- **WorldEdit**：服务端指令式（客户端不用装）。`//wand`、`//pos1 //pos2`、`//set`、`//copy //paste`、
  `//stack`、`//move`、`//schem save/load`、`//brush`、`//undo`；也走 RCON（中控/脚本里能批量发）。
- **Axiom**：客户端交互式编辑器，开关键位 `Toggle Editor UI`（默认 `右Shift`；不对就去
  **选项 → 控制 → Axiom** 分类里看/改）。构造/克隆/笔刷/蓝图都在里面。
- **改口径（想换模组/去掉）**：只动 `fabric\build.gradle` 里那两行 `property` 与 `vendor\mods\` 里的 jar；
  不要去动 `run\mods`（那里现在应当是空的）。
- **Axiom 现在没装在服务端**：这是"两端能同时跑"的代价 —— Axiom 的服务端侧功能（权限/服务端蓝图等）不可用，
  纯客户端编辑照常。要服务端侧功能就得放弃两端同时运行。
- 服务端启动会有一行 `Missing data pack axiom` 警告：那是世界 `level.dat` 里记着上一轮（Axiom 还在服务端时）
  启用过的数据包，**无害**（不影响任何玩法与数据）。

## 7. ⚠️ Axiom 5.4.2 在**中文界面**下必崩（Dear ImGui 字体图集溢出）

装上后第一次进游戏就崩，而且**没有 Java crash report**（原生崩溃）：

```
Process '…java.exe' finished with non-zero exit value -1073740791      // 0xC0000409 STATUS_STACK_BUFFER_OVERRUN

[15:24:07] [STDERR]: Dear ImGui Assertion Failed: pack_id != ImFontAtlasRectId_Invalid && "Out of texture memory."
[15:24:07] [STDERR]: Assertion Located At: /tmp/imgui/jni/imgui_draw.cpp:4764
	at imgui.moulberry92.ImFontAtlas.nBuild(Native Method)
	at imgui.moulberry92.ImFontAtlas.build(ImFontAtlas.java:540)
	at com.moulberry.axiom.editor.EditorUI.initFonts(EditorUI.java:341)
	at com.moulberry.axiom.editor.EditorUI.init(EditorUI.java:200)
	at com.moulberry.axiom.editor.EditorUI.drawOverlay(EditorUI.java:663)
```

**A/B 定位**（四次真跑，每次都是"到主菜单后看 25–30 秒"）：

| # | `lang` | `guiScale` | 结果 |
| --- | --- | --- | --- |
| 1 | `zh_cn` | 4（用户原设置） | ❌ 主菜单后约 7 秒崩 |
| 2 | `zh_cn` | 2 | ❌ 同一处断言崩 |
| 3 | `en_us` | 2 | ✅ 稳定 |
| 4 | `en_us` | 4（还原用户设置） | ✅ 稳定 |

⇒ **触发的是语言（CJK 字形集），不是界面缩放**。Axiom 的本体是自带 Dear ImGui 的原生 UI，
`initFonts` 给当前语言准备字形时把图集撑爆，ImGui 断言回调只 dump 栈、随后 JNI 层直接带走 JVM。
Axiom 的语言文件里也**没有**可关的字体/本地化开关（只有文本工具的 `use_builtin_font`），所以没有配置层绕法。

**处置（用户 2026-09-25 拍板：要 Axiom，界面用英文）**：
- `run\options.txt` 改 `lang:en_us`（`guiScale` 保持用户的 **4**）；原文件备份 `run\options.txt.bak-axiom-gui4`。
- 想回中文就必须**同时**把 Axiom 从 client 的 `fabric.addMods` 注入里拿掉（否则一进游戏就崩），
  WorldEdit 不受影响（它在服务端）。
- `config\axiom\imgui.ini` 删过一次（让 Axiom 重建默认布局），它会自己再生成。

## 8. 8 小时后：**Axiom 已卸下**（用户 2026-09-25：「给Axiom删了吧太复杂了」）

卸掉的东西（**WorldEdit 不动**）：

| 对象 | 处置 |
| --- | --- |
| `vendor\mods\Axiom-5.4.2-for-MC1.20.4.jar` | 删除（Modrinth 可重下，sha512 见 `logs\2026-09\mods-installed-*.txt`） |
| `fabric\build.gradle` 里 client 的 `property "fabric.addMods", axiomJar…` | 删除（**server 那条 WorldEdit 的保留**） |
| `run\config\axiom\`（blueprints / imgui.ini） | 删除 |
| `run\.fabric\processedMods\` 里 `axiom-*` / `com_moulberry_*` / `lattice-*` 四个缓存 | 删除（`worldedit-*` 那条留着，服务端正锁着它） |
| `run\options.txt` 的 `lang` | 改回 `zh_cn`（`guiScale` 一直是用户的 **4**） |

**留下的结论照样成立**：§2 那个"生产版模组不能放 `run\mods`、要按端注入"的坑，现在由 WorldEdit 一个人在用；
`fabric\build.gradle` 那一段注释也改成了只讲 WorldEdit（并留了一行"客户端曾装过 Axiom、为何卸下"的考古线索）。

**验证**（卸完重启客户端，中文界面）：`Loading 60 mods:`（回到装模组前的基线）、模组表里**没有** axiom / worldedit、
到主菜单后 25 秒无任何报错；服务端仍是 `Loading 45 mods:` 含 `worldedit`。
`/datapack list` 里已无 axiom（那条 `Missing data pack axiom` 只是世界存档里的一行历史记录，启动时提一句，无害）。

**要再装回来**：把 jar 放回 `vendor\mods\`，在 client 的 `fabric.addMods` 上加回一行即可 —— 但**中文界面下它必崩**（§7），
要 Axiom 就得同时把客户端语言设成 `en_us`。只想批量建造的话，服务端的 WorldEdit 够用（`//` 系列指令，RCON 也能发）。
