# 222 · 屏幕 UI 全变方框：`ttf` provider 的 `file` 不能带 `font/` 前缀

日期：2026-09-21 · 结论：**我写错了字体定义的路径** —— `"file": "mtr:font/din1451alt.ttf"`。
MC 会自己给这个字段加 `font/` 前缀，于是它去找 `assets/mtr/font/font/din1451alt.ttf`（不存在），
provider 加载失败 ⇒ `mtr:ui` 这个字体没有字形 ⇒ **每个字符都画成 missing glyph（方框）**。
改成 `"file": "mtr:din1451alt.ttf"` 即好。

用户原话：「字体全部变为方框」→ 紧接着一条关键补充：「**但是车上的仪表有正常字体**」。

## 1. 那句补充把范围一刀切开（这是本轮最省时间的一条信息）

| 链 | 结果 | 说明 |
|---|---|---|
| **仪表 UI**（BR101 驾驶台面板，AWT 读 TTF） | **正常** ✓ | 说明那批 TTF 文件本身没问题、`MmtrPanelFont` 的路径（`font/din1451alt.ttf`，**无 MC 前缀**）也是对的 |
| **屏幕 UI**（司机 HUD、`[G] 进入驾驶室` 提示，MC 字体系统） | **全方框** ✗ | 问题在 `assets/mtr/font/ui.json` 的 `file` 字段 |

两条链**用同一批文件**，所以"一个正常一个方框"直接排除了"字体文件坏了/装错目录"，只剩解析规则。

## 2. 真因：MC 的 `ttf` provider 会给 `file` 加 `font/` 前缀

两条独立证据互相印证：

1. `TrueTypeGlyphProviderDefinition.class` 里有 **`"font/"` 字面量**（从类文件字节里查出来的，
   不是靠记忆）；
2. 上游 MTR 的 `assets/mtr/font/mtr.json` 写的是 `"file": "mtr:noto-sans-semibold.ttf"`，
   而文件确实放在 **`assets/mtr/font/noto-sans-semibold.ttf`**（我逐个列出过 jar 条目）。

⇒ JSON 里只能写**文件名**：`mtr:din1451alt.ttf` → `assets/mtr/font/din1451alt.ttf` ✓
写 `mtr:font/din1451alt.ttf` → `assets/mtr/font/font/din1451alt.ttf` ✗（provider 加载失败 ⇒ 方框）。

**注意这条只适用于 MC 字体定义里的 `file`**；`MmtrPanelFont` 那条 AWT 链用的是
`ResourceManagerHelper.readResource(new Identifier(MOD_ID, "font/xxx.ttf"))`，那是**裸资源路径**、
**不带**自动前缀 —— 所以同一批文件在两个入口的写法**故意不同**（notes/221 §2 那张表就是为这个）。

顺带清掉一个自找的风险：我还在 JSON 顶层放了 `_comment`（上游没有）。字段本会被宽松 codec 忽略，
但既然有权威参照就照抄 —— 两个 JSON 现在与上游**同形**（只有 `providers`）。

## 3. 守卫：`scripts/check-font-assets.ps1`（提交前跑一次）

把每个 `ttf` provider 的引用**按 MC 的规则**解析（`assets/<ns>/font/<file>`）并检查在位 + 文件头是
TrueType/OpenType（`00010000` / `true` / `OTTO` / `ttcf`），缺失就打印解析到的实际路径与写法提示。

```
OK   ui.json -> mtr:din1451alt.ttf (32 KB)
OK   ui.json -> mtr:harmonyos-sans-sc-regular.ttf (8068 KB)
...
check-font-assets: OK - 6 个 ttf 引用全部在位
```

带 `-AssetsRoot <dir>` 可以在**夹具目录**里做 red-proof（注入 `mtr:font/…` 后必须 FAIL：实测 exit=1 ✓）。

> **一条过程教训**：第一次 red-proof 我直接改了**真文件**再复原，结果那次工具调用被中断，
> 文件留在"改坏"状态、备份又是改坏之后才做的 ⇒ 复原有毒。**red-proof 一律在夹具里做**
> —— 这也是给守卫加 `-AssetsRoot` 的原因。

## 4. 另一条误导性证据（记下来，下次别再被它带走）

日志里这次客户端是 **16:26 / 17:20 起的**，而资源文件是 17:16 写、17:20:04 重建的 ——
"会话启动时间早于资源时间"看起来像"客户端加载的是旧资源"，但实际那个客户端（17:20:11 起）
**确实加载了**我那份写错路径的 JSON。时间线能提示"该不该重启"，但**不能替代**对内容本身的检查。

## 5. 处置与验证

1. `ui.json` / `ui_bold.json` 的 `file` 去掉 `font/`，并删掉 `_comment`（与上游同形）；
2. 守卫复核：真资源 **6/6 OK（exit 0）**；夹具注入故障 **exit 1** ✓；
3. 把修好的两个 JSON 同步进 `build/resources/main/assets/mtr/font/`（客户端正在读的那份）；
4. 客户端里 **F3+T 重载资源**即可（字体重新解析，不必重启进程；本轮没改代码，也不需要同步 jar）。

## 6. 遗留

1. 屏幕字体的 `size` / `oversample` / `shift`（照上游 12 / 8 / 16、CJK shift 0.5 起的）等实机目视后再调；
2. 面板链还没用 `ui_bold`（`MmtrHudLayout` 没有字重字段）；
3. `check-font-assets.ps1` 目前只查 `game/fabric` 的资源目录；若将来字体挪到别处（例如资源包仓库），
   把根目录参数接到那里即可。
