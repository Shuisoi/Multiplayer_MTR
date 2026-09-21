# 221 · 屏幕与仪表 UI 字体导入（DIN 1451 西文 + HarmonyOS Sans SC 中文）

日期：2026-09-21 · 结论：**只为 MMTR 自己的屏幕/仪表 UI 引入字体，游戏字体一个字没动**。
西文与数字走 **Alte DIN 1451 Mittelschrift**，中文走 **HarmonyOS Sans SC**（+ Bold 变体）。
两条渲染链各有一处字体入口，**同一批 TTF、同一套角色分工**。

用户口径：「先把字体导入到游戏中；中文和西文使用 `…\ShuisoiSimUniverse\Fonts` 中的字体」
+「这些字体**仅屏幕、仪表 UI** 显示，**不替换游戏内字体**」。

## 1. 选型（先量了字形覆盖，再定角色）

| 字体 | 家族名 | 字形数 | 覆盖（实测 `Font.canDisplayUpTo`） |
|---|---|---|---|
| `din1451alt.ttf` | Alte DIN 1451 Mittelschrift | **231** | ASCII/数字/德文变音/常用标点 ✓；**无箭头**（`←↑→↓` 缺） |
| `HarmonyOS_Sans_SC_Regular/Bold` | HarmonyOS Sans SC | **29221** | 中英数标点、连生僻字（龘齉爨）**全覆盖** |
| `DreamHanSerifCN-W24` | 源云宋体 CN W24 | 31027 | 全覆盖（**未采用**：用户选了黑体方案，省 15 MB） |

分配：**西文/数字 = DIN 1451**（德式工程数字体，正合 BR101 仪表），**中文 = HarmonyOS Sans SC**，
DIN 画不出的字符（箭头等）回退 HarmonyOS。Bold 变体留给标题/告警。

## 2. 两条渲染链，两个入口（这是本轮最需要记住的结构）

| 链 | 画什么 | 字体入口 | 机制 |
|---|---|---|---|
| **屏幕 UI** | 右上角司机 HUD、`[G] 进入驾驶室` 提示 | `IDrawing.withUIFont(...)` → 字体 id **`mtr:ui`** | MC 字体系统：`assets/mtr/font/ui.json` 的 `ttf` provider |
| **仪表 UI** | BR101 驾驶台 `mmtr_hud_*` 面板（速度/档位/铭牌） | `MmtrPanelFont.get(text)` | AWT 直接读 TTF，把整幅画面烘进一张贴图（`MmtrPanelCanvas`） |

两条都**不碰** `minecraft:default`（游戏字体）与 **`mtr:mtr`**（上游 MTR 自己的 Noto 那套，
它的站牌/PIDS/电梯面板在用，且受 `useMTRFont` 配置控制）—— 这是用户"仅屏幕/仪表 UI"的直接落地。

## 3. 屏幕 UI 这条链（MC 字体系统）

`assets/mtr/font/ui.json`（+ `ui_bold.json`）—— **写法照上游 `assets/mtr/font/mtr.json`**，
同一 MC 版本的权威参照；`ttf` provider 的字段名（`file`/`size`/`oversample`/`shift`/`skip`）
是从 `TrueTypeGlyphProviderDefinition.class` 里的**字面量**核对出来的，不靠记忆：

```json
{ "providers": [
  { "type": "ttf", "file": "mtr:din1451alt.ttf", "size": 12.0, "oversample": 8.0 },
  { "type": "ttf", "file": "mtr:harmonyos-sans-sc-regular.ttf", "shift": [0, 0.5], "size": 12.0, "oversample": 16.0 },
  { "type": "reference", "id": "minecraft:include/space" },
  { "type": "reference", "id": "minecraft:include/default" },
  { "type": "reference", "id": "minecraft:include/unifont" }
] }
```

> ★ **`file` 只写文件名，不要写 `font/`**（notes/222 的教训）：MC 的 `TrueTypeGlyphProviderDefinition`
> 会自己加 `font/` 前缀（类文件里有该字面量），写成 `mtr:font/x.ttf` 会被解析成
> `assets/mtr/font/font/x.ttf` → provider 加载失败 → 该字体没有字形 → **屏幕上全是方框**。
> 上游 `mtr.json` 写 `mtr:noto-sans-semibold.ttf` 而文件放在 `assets/mtr/font/` 下，两边正好互相印证。
> 守卫：`scripts/check-font-assets.ps1`（提交前跑一次；带 `-AssetsRoot` 可用夹具做 red-proof）。

- **多 provider = 天然的字符级回退**：首个支持该字符的 provider 胜出 ⇒ 西文数字拿 DIN、中文拿 HarmonyOS，
  最后的原版引用保证"就算一个 TTF 都没装上也不会画成空白"。
- 机制核对（1.20.4）：`TrueTypeGlyphProvider.getGlyph(int)` 是**按需烘字形**（不是加载时烘 2.9 万个），
  `FontTexture.SIZE = 256` 而 `FontSet.textures` 是**列表**（用完自动加张）⇒ 全量中文字体可以放心用。
- **测量必须与绘制同字体**：`GraphicsHolder.getTextWidth(MutableText)` 才有带样式的宽度；
  用默认字体量、UI 字体画，面板宽度与右对齐会差几个像素（`MmtrDriverHud` 已按此改；
  `MmtrInteractPrompt` 的背景框同样改成用带样式文本量宽）。

## 4. 仪表 UI 这条链（AWT）

`MmtrPanelFont`：`LATIN_PATH = font/din1451alt.ttf`、`CJK_PATH = font/harmonyos-sans-sc-regular.ttf`，
选择规则从"含不含中文"升级为**"含中文，或 DIN 画不出某个字符"→ HarmonyOS**：

```java
if (IGui.isCjk(text)) return cjk;
return din.canDisplayUpTo(text) < 0 ? din : cjk;   // 例：'←' DIN 没有
```

**为什么这里要显式判一次**：面板这条链是 AWT **物理字体**，没有 MC 字体定义里那种 providers 兜底链 ——
不判就会把画不出的字符烘成豆腐块。

离线验证（`sandbox/PanelFontProbe.java`，同一文件、同一条规则）：

| 文本 | 选中 |
|---|---|
| `87 km/h` / `160` / `Baureihe 101` / `101 012-3` / `DB Fernverkehr` / `Zugfunk · GSM-R` / `°C 25` | **DIN 1451** |
| `限速` / `速度 87` / `牵引 45%` / `运行 1A 1B 2 3 4 5 6 7 8 EB` / `限速 160 km/h` | **HarmonyOS（含中文）** |
| `← → ↑ ↓` | **HarmonyOS（DIN 画不出 `←`）** |

另：面板是按**墨迹高度**归一的（`MmtrPanelCanvas.text` 用 `probeInk.getHeight()` 反推字号），
所以同一 `heightM` 下 DIN 与 HarmonyOS 的"字高"一致，混排不会跳（实测同 100pt：DIN 墨高 72.4、
HarmonyOS 92.2 —— 归一后一致）。

## 5. 文件与体积

`game/fabric/src/main/resources/assets/mtr/font/`（用户选择直接入库）：

| 文件 | 体积 | 用途 |
|---|---|---|
| `din1451alt.ttf` | 31 KB | 屏幕 UI 与仪表面板的西文/数字 |
| `harmonyos-sans-sc-regular.ttf` | 7.88 MB | 两个链的中文 |
| `harmonyos-sans-sc-bold.ttf` | 7.78 MB | `mtr:ui_bold`（标题/强调；面板链暂未接字重） |
| `ui.json` / `ui_bold.json` | — | 字体定义（字体 id `mtr:ui` / `mtr:ui_bold`） |

（仓库里本来就有上游的 `mtr.json` + Noto Sans 0.5 MB + Noto Serif CJK **26.8 MB**，所以这次 +15.7 MB 与既有做法一致。）

## 6. 验证

| 项 | 结果 |
|---|---|
| ttf provider 字段名 | 从 `TrueTypeGlyphProviderDefinition.class` 的字面量核对：`file/size/oversample/shift/skip/type/ttf` 全在 ✓ |
| 字体 JSON | 照上游 `mtr.json`（同版本、已在线上跑）写；列出的文件都在资源目录里 ✓ |
| 仪表面板字体加载 + 选择 | `sandbox/PanelFontProbe.java`：两个 TTF 解析成功、15 个样本选择全部符合预期（含回退）✓ |
| 客户端 javac | `check-java-compile.ps1` → 436 源 / 641 类 OK ✓ |
| 实机 | 起客户端后看 `[MMTR-UI] 仪表面板字体：西文=… 中文=…` 一行确认加载；屏幕 HUD 与提示换字体后需目视一次（字宽变化会体现在面板宽度上） |

## 7. 遗留

1. **面板链还没用 Bold**（`MmtrHudLayout` 的 widget 没有字重字段）；`mtr:ui_bold` 已在字体系统侧可用，
   屏幕侧随时能用，面板侧要加一个 `bold` 字段才行；
2. `size`/`oversample`/`shift` 是照上游数值起的（12.0 / 8 / 16、CJK shift 0.5）——
   实机看着偏大偏小改 `ui.json` 里的三个数即可，不必改代码；
3. `ModelPropertiesPart` 的模型贴图文字仍走 `withMTRFont`（上游 Noto）——本轮没动，
   那些是 MTR 自己的模型文字（站牌/PIDS 一类），要换得先确认是否属于"我们的 UI"。
