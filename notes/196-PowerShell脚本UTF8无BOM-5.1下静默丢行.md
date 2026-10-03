# 196 · 工作区 PowerShell 脚本的编码地雷：UTF-8 无 BOM + 中文注释 = 5.1 下静默丢行

> 本轮在**重启客户端**时踩到的，与建模无关，但差点让"按 G 进驾驶室"的验收变成
> "脚本根本没跑"。修法一行：给所有 `.ps1` 加 **UTF-8 BOM**（工具 `mmtr/scripts/add-utf8-bom.ps1`）。

---

## 0. 症状

按项目文档的写法启动客户端：

```
powershell -File mmtr\scripts\dev-client.ps1
```

客户端没起来，`fabric/run/dev-client.out.log` 里只有：

```
ERROR: JAVA_HOME is set to an invalid directory: C:\Program Files\Java\jre1.8.0_431
```

而 `dev-client.ps1` 的注释**恰好就是在讲这个坑**，第 7 行也明明 dot-source 了
`env/workspace.env.ps1`（那个文件的职责就是把 `JAVA_HOME` 指到 `env\jdk-21`）。
换句话说：**修复代码在，但没生效。**

---

## 1. 真因：Windows PowerShell 5.1 用 ANSI(GBK) 解码无 BOM 的 UTF-8 文件

工作区脚本一律 UTF-8 **不带 BOM**，注释里全是中文。PS 5.1 遇到无 BOM 文件时用**系统 ANSI 代码页**
（本机 = GBK）解码，UTF-8 的中文三字节在 GBK 下被两两错配，**字节对齐整体错位**。
于是出现两种症状，**第二种才是真正咬人的**：

| 症状 | 例子 | 为什么难查 |
| --- | --- | --- |
| ① 直接**解析失败** | `env\workspace.env.ps1`（"Unexpected token '}'"）、`dev-server.ps1`、`deploy-engine.ps1` 等 **38 个** | 好查：5.1 一跑就报语法错 |
| ② **解析"成功"、但有一行被吞掉** | `mmtr\scripts\dev-client.ps1` | 要命：`Parser` 报 **0 个错误**，只有把 AST 打出来才看得见 |

症状 ② 的实测（PS 5.1 解析 `dev-client.ps1` 的顶层语句）：

```
parse errors   = 0
top statements = 7          <- 真实文件有 13 行、应当有 8 条语句
  1. line 2   $ErrorActionPreference = 'Continue'
  2. line 5   $mmtr = ...   <- 行号整体前移，真实第 7 行的 dot-source 不在 AST 里
  ...
```

被错配的中文注释把那行**整个吞进了注释**，文件照常"解析通过"——
于是 `env\workspace.env.ps1` **从来没被执行过**，`JAVA_HOME` 保持机器上的 JRE 8，
gradle 拒绝启动。**"能解析"不等于"语句还在"**，这就是它躲过逐行 review 的原因。

---

## 2. 修法

给工作区**所有** `.ps1` 加 UTF-8 BOM（不在 BOM 里的三字节内容一律不动，只前置 `EF BB BF`）：

```
powershell -File mmtr\scripts\add-utf8-bom.ps1 -WhatIfOnly   # 只列，不改
powershell -File mmtr\scripts\add-utf8-bom.ps1               # 执行（幂等）
```

脚本**故意不"智能挑选"**要修哪些文件：症状 ② 让"哪些文件真的坏了"无法靠"能不能解析"判定，
所以规则是统一的 —— **凡是无 BOM 的 `.ps1` 一律加**。首轮 38 个（解析失败）+ 23 个
（解析通过但会丢行）；二次运行 0 个（幂等）。

验证（两个都必须过）：

```
powershell -File sandbox\parse_audit.ps1 .   # 5.1 解析审计：修前 38 处失败 -> 修后 0
powershell -File sandbox\ast_probe.ps1       # dev-client.ps1 的 AST：dot-source 回到第 7 行、8 条语句
```

修后同一句 `powershell -File mmtr\scripts\dev-client.ps1`：`JAVA_HOME` = `env\jdk-21`，
客户端正常起来（`world render running, client vehicles=8`）。

---

## 3. 教训

1. **中文注释 + 无 BOM + PS 5.1 是一类静默故障**，不是风格问题。工作区文档里让用户跑
   `powershell -File ...` 的地方全部受它影响（`pwsh` 7 默认 UTF-8，所以**用 `pwsh` 跑的人看不到**——
   这正是它活到现在的原因）。
2. **判据要选对**：这类故障的正确判据是 **AST 里语句在不在**，不是"解析有没有报错"。
   与建模那边"调用成功 ≠ 结果正确"是同一个教训，只是换了个层面。
3. **`.bat` 早就要求 UTF-8 BOM**（台账 §9 提过 `verify.bat`），`.ps1` 一直没有这条要求 —— 现在统一。
4. **副作用**：本轮客户端/服务端都被重启（客户端由本脚本的重启流程拉起）。
   服务端（引擎）本轮未改代码，未重启。
5. 顺带补了按键的中文/英文名（`assets/mtr/lang/en_us.json` 里
   `key.mmtr.cab/couple/wiper`）—— 之前控件菜单里显示的是原始 key 串。
