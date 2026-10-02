# MMTR 车辆动态面 · 作者指南

> 面向：**车体作者**（Blender）、**资源包作者**（JSON）、**附属模组开发者**（Java）。
> 系统设计见 `01-设计/车辆动态面系统-设计.md`，实现过程与判据见 `notes/359`。
> **逐键的权威表见 [`车辆动态面-面文档格式.md`](../01-设计/车辆动态面-面文档格式.md)**（v2）——
> 本文讲"怎么上手、怎么查"，那份讲"每个键是什么、缺省多少、什么语义"。
> 一句话：**动态面 = 车体上一块平面 + 一段"画什么、什么时候画"的 JSON**。你只管建模与排版，
> 数据由引擎推、绘制由客户端做。

---

## 1. 五种典型用法（先认清你手上是哪一类）

| 你想做的 | 做法 |
| --- | --- |
| 水牌 / 目的地牌、下一站牌 | **已经有现成的**：`mmtr_pid_*` / `mmtr_next_*` 锚点 + `pid` / `next` 段（notes/357/358）。不用写文档。 |
| 已有锚点上换一套画法 | 给它写一段 `faces` 文档（**键 = 锚点名**），立刻接管。 |
| 车里再来一块屏（PIS / 内饰屏 / 灯箱） | 模型里加一个 `mmtr_face_<cab>[_<n>]` 组 + JSON 里写 `faces.face_<cab>_<n>`。 |
| **一块牌要显示好几页**（去程/回程、停站表）、或者要**翻牌机**那种翻转水牌 | 写 `pages`（+ `pageSeconds` / `pageExpr` / `drum`），见 §5.1 / §5.2。 |
| 屏上要**会动**（闪、走马灯、淡入淡出、转） | 元素上写 `anim`，见 §6。 |
| 牌上要**贴图**（路徽、标志） | `image` 元素，图放资源包 `assets/<命名空间>/…`，见 §5.3。 |
| 屏上要显示一个**现在没有**的量 | 能从已有字段算出来 ⇒ 让附属模组加一个**扩展字段**（§10 第 5 级，现在就能做）；要引擎里多一个**权威量** ⇒ 走"加字段四步曲"（§10 第 2 级）。 |
| 要一个**现成元素画不出**的东西（渐变条、贴图动画） | 升级阶梯第 3 级：附属模组注册一个元素类型（§10）——**现在就能做**；工作室顶部有「导出 Java 骨架」帮你把桩搭好（§10.1）。 |

---

## 2. 五分钟上手

**① 建模**：在 Blender 里建一个**独立组**，命名 `mmtr_face_1`（= 驾驶室 1 的这块面；同一端第二块叫
`mmtr_face_1_2`；B 端叫 `mmtr_face_2`）。要点只有三条：

- 组里的面就是屏的**矩形区域**，尺寸就是屏的尺寸（打包器会把这块面的几何剥掉，只留"位置 + 朝向 + 大小"）；
- **法线朝读它的人**（乘客站在哪边，法线就朝哪边）—— 反了的表现是"站台上看不见这块屏"；
- 面的"上方向"就是文字的上方向（别把它画歪了再指望客户端转）。

**② 写文档**（车辆配置里，比如 `tools/obj-mtr-packager/consist/<车型>.json` 的那节车）：

```json
"faces": {
  "face_1": {
    "background": "#FF101418",
    "textColor":  "#FFF2F4F6",
    "require":    {"!!": [{"var": "pid.service"}]},
    "elements": [
      {"type": "text", "text": "{pid.service}", "x": 0.03, "y": 0.5, "size": 0.46, "align": "left"},
      {"type": "text", "text": "开往 {pid.terminus}", "x": 0.97, "y": 0.5, "size": 0.62, "align": "right",
       "when": {"!!": [{"var": "pid.terminus"}]}},
      {"type": "rect", "x": 0, "y": 0.94, "w": 1, "h": 0.06, "color": "#FFFFB300"}
    ]
  }
}
```

**③ 打包**：`pwsh -File mmtr\scripts\pack-consist.ps1 mmtr\tools\obj-mtr-packager\consist\<车型>.json -Version <n+1>`
（日志里会有一行 `faces: authored 1 块 [face_1] …`）。

⚠ 后面两条命令（出图与自检）读的是**打包出来的** `mmtr_anchors_<车型>.json`（在 `assets/mtr/` 下），
**不是**你手写的那份 `consist/<车型>.json` —— 后者的 `faces` 嵌在 `cars[]` 里，顶层没有 `faces` 段，
喂给它只会得到一份"零个面的空 PASS"（§11 症状表最后一行）。

**④ 看一眼（不用起客户端）**：

```powershell
# 这块车有哪些面、有哪些锚点、锚点多大
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <解包出来的 mmtr_anchors_x.json> -List

# 一块面 × 四个状态各出一张图
foreach ($p in 'running','stopped','return','idle') {
  pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <json> -Face face_1 -Preset $p -Out ".\sandbox\face-preview\face_1_$p.png"
}

# 换个尺寸看排版（比例坐标 ⇒ 同一份文档自动等比）
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <json> -Face face_1 -WidthM 1.6 -HeightM 0.12

# v2：看**第 2 页、8 秒处**的样子（有 pages/anim 时必须给时刻，不然只能看到第 0 帧）
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <json> -Face face_1 -Page 1 -TimeMs 8000 -Out .\sandbox\face-preview\p1_t8s.png

# v2：image 元素的图从哪找（每个目录是一个资源包根，里面有 assets\）
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <json> -Face face_1 -Pack .\mmtr\game\fabric\run\resourcepacks\MMTR_SAF420_v37
```

**⑤ 自检**：`node mmtr\tools\anchor-check\verify_face.js --anchors <json>`
九项全 PASS 再进游戏 —— 它抓的都是"进游戏不报错、只是那块面不出现/少画一样"的错。
写了 `pages` 的文档**也在它的遍历范围里**（每条问题都带页名/页下标，例如 `faces.pid_1.pages[1].elements[0]`），
所以"自检说没问题、游戏里不画"这条最坏的情况不会再发生。

---

## 3. 锚点约定（Blender 侧）

| 组名 | 是什么 | 客户端认的 kind |
| --- | --- | --- |
| `mmtr_face_<cab>[_<n>]` | **动态面**（屏 / 灯箱 / PIS） | `face` |
| `mmtr_pid_<cab>[_<n>]` | 水牌（班次号 + 本趟终点） | `pid` |
| `mmtr_next_<cab>[_<n>]` | 下一站牌 | `next` |
| `mmtr_hud_<cab>[_<n>]` | 司机台仪表面板 | `hud` |

- `cab`：1 = A 端、2 = B 端（与 `mmtr_hud_*` 同一套约定）；`_<n>` 是同一端的第几块。
- **面名 = 文档键**：`mmtr_face_1_2` 的文档写在 `faces.face_1_2`（去掉 `mmtr_` 前缀）。
  名字写错的表现是**那块面根本不画**（`verify_face.js` 的 P1 就是抓这个）。
- **已有的可见件可以改名成锚点**：SAF420 的水牌就是模型里那块可见的 `dest_board` 改名成 `mmtr_pid_1`
  得来的（零建模成本）。改名用配置里的 `groupRename`（打包器先改名、再按名字判种类）。
  ⚠ 不要把动态面命名成 `mmtr_dest_board_*` 这类**会被 `groupMap.body` 最长匹配吞掉**的名字。
- **同名的任何锚点都能挂文档**（含水牌、仪表）：有文档的锚点由面系统画，没文档的仍归老渲染器 ——
  所以"把水牌换成动态面"是**纯资源包动作**，客户端一行代码都不用改。

---

## 4. 面文档参考

```json
"faces": {
  "<锚点名>": {
    "background": "#FF101418",
    "textColor":  "#FFF2F4F6",
    "pxPerMetre": 512,
    "side":       "normal",
    "require":    {"!!": [{"var": "pid.service"}]},
    "vars":       {"arriving": {"<": [{"var": "lzb.targetM"}, 200]}},
    "elements":   [ ... ]
  }
}
```

| 键 | 缺省 | 说明 |
| --- | --- | --- |
| `background` | **透明** | 牌底通常交给模型（不写就不会盖住模型上的图案）；`#RRGGBB` / `#AARRGGBB` |
| `textColor` | `#FFF2F4F6` | 文本元素没写 `color` 时用它 |
| `pxPerMetre` | `512` | 贴图像素密度；字很小时调高（长边有 512 px 上限，见格式文档 §2 的 `pxPerMetre`） |
| `side` | `normal` | `normal` = 锚点法线那一侧（朝读它的人，屏/水牌用这个）；`driver` = 司机那一侧（仪表面板）；`both` = 两侧 |
| `require` | 无 | **整块面**画不画（表达式）；不成立 ⇒ 这块面完全不画（连底都不铺） |
| `vars` | 无 | 给条件起名字：求值一次，元素里直接 `{"var": "arriving"}` |
| `elements` | — | 元素数组，**按顺序**画（后面的盖前面的）。**没写 `pages` 时它就是第 0 页**（v1 文档就只有它） |
| `pages` | 无 | 多页（§5.1）。**写了它就忽略顶层 `elements`** |
| `pageSeconds` | `6` | 自动轮转周期（秒）；`0` = 不自动转 |
| `pageExpr` | 无 | 指定当前页：数 = 下标（回绕），字 = 页名；写了它就压过 `pageSeconds` |
| `fps` | `8` | **动画重画节拍**（1..30），见 §6 |
| `roll` / `tilt` | `0` | 整面绕自身法线滚转 / 绕水平轴抬起（度），贴斜装的牌子用 |
| `drum` | 无 | 翻牌机（§5.2） |

几何一律是**比例**：`x`/`w` 乘牌宽，`y`/`h`/`size`/`radius` 乘牌高；`y` 从**下**往上。
所以同一份文档贴到 1.6 m 或 0.32 m 的屏上都等比。逐键的权威表（含"`x`,`y` 是哪个点"）见
[`车辆动态面-面文档格式.md`](../01-设计/车辆动态面-面文档格式.md) §3。

---

## 5. 元素表（9 种）

| type | 键 | 说明 |
| --- | --- | --- |
| `text` | `text`（模板）、`x`、`y`、`size`、`align`（left/center/right）、`color`、`shrinkToFit`（默认 0.9）、`when` | 取不到内容 ⇒ 这一行不画；超宽按 `shrinkToFit` 等比缩小 |
| `rect` | `x`、`y`、`w`、`h`、`color`、`when` | 实心矩形（`x`,`y` = **左下角**） |
| `roundRect` | 同上 + `radius`（默认 0.08，牌高比例） | 圆角矩形（面板底、按钮） |
| `line` | `x`、`y`、`x2`、`y2`、`width`（默认 0.03）、`color` | 直线（圆头） |
| `circle` | `x`、`y`、`radius`（默认 0.1）、`color` | 实心圆（状态灯） |
| `arc` | `x`、`y`、`radius`、`start`、`end`（逆时针从 +X 起，默认 0→360）、`width` | 圆弧（表盘刻度） |
| `gauge` | `x`、`y`、`radius`、`start`（默认 225）、`end`（默认 -45）、`max`（默认 160）、`ticks`、`tickLength`、`labelEvery`、`labelSize`、`lineWidth`、`needle`、`needleColor`、`needleWidth`、`needleLength` | 表盘 + 指针；`needle` 是表达式，缺省 = `{"var":"speedKmh"}` |
| `image` | `src`（**必填**）、`fit`（`stretch`/`contain`/`cover`）、`alpha`、`tint` | 贴一张图进 `w`×`h` 的框（§5.3） |
| `foreach` | `var` 或 `of`、`as`（默认 `item`）、`index`（默认 `index`）、`limit`（默认 32）、`elements`（**必填**） | 按列表重复画一组元素（§5.4） |

**公共键**（哪种类型都认）：`type`、`when`、`x`、`y`、`w`、`h`、`size`、`color`、`align`、`text`、
`rotate`（绕**这个元素自己的锚点**在面内转，度，顺时针为正）、`opacity`（0..1，乘到颜色的 alpha 上）、
`anim`（§6）。

> 所有元素的 `x/y/w/h` 都要落在 `0..1`，`size` 落在 `0.02..1.5`，`opacity` 落在 `0..1` ——
> `rect`/`roundRect` 少了 `w`/`h` 就什么都画不出来。这些都归**离线自检的 P6** 管。
> **写了类型不认的键**（`colour`、`siz`、`filt` 这种拼错）由 **P9** 抓 —— 游戏里会照画，只是画得不对。

### 5.1 多页（`pages`）

```json
"pages": [
  { "name": "去程", "require": { "==": [ { "var": "handle.reverser" }, 1 ] }, "elements": [ … ] },
  { "name": "回程", "elements": [ … ] }
]
```

* 每页：`name`（可选，给 `pageExpr` 用字名）、`require`（可选门）、`elements`（**必填**）。
* **选哪一页**：`pageExpr` 有值 → 用它；否则 `pageSeconds > 0` → 按时钟轮转
  `floor(秒数 / pageSeconds) % 页数`；否则第 0 页。
* 页的 `require` 不成立 ⇒ **这一页不画**（不自动跳下一页 —— 跳哪页是作者的语义）。
* `pageExpr` 求值失败 / 下标越界 / 页名找不到 ⇒ 回落到第 0 页。
* ⚠ **写了 `pages` 就不要再写顶层 `elements`**：后者被**整段忽略**（自检报 P6，游戏里也记一条）。
  这条在图上只表现为"东西少了"，所以最容易白查半天。

### 5.2 翻牌机（`drum`）

```json
"pages": [ { "name": "上行", "elements": [ … ] }, { "name": "下行", "elements": [ … ] } ],
"drum": { "count": 2, "turnFraction": 0.25, "radiusM": 0.02 }
```

一个 N 面棱柱，第 i 面贴第 i 页（页不够就回绕）；换页节奏仍由 `pageSeconds`/`pageExpr` 给
（`pageExpr` 指定页时**直接跳**，不翻滚）。

| 键 | 缺省 | 含义 |
| --- | --- | --- |
| `count` | `2`（**2..8**） | 棱柱面数。超出范围引擎会钳回去 —— 牌面就不是你算的那个样子了（自检报 P6） |
| `turnFraction` | `0.25` | 一个换页周期里**用来翻**的比例：前 25% 转过去，其余停住 |
| `radiusM` | `0` → 正棱柱 `w / (2·tan(π/N))` | 棱柱外接半径（米）；写小一点 = 薄牌。**`count: 2` 时正棱柱算出来是 0**（两面共面 = 一块翻来翻去的平板，牌会从自己中间穿过）——想要厚度就写 `radiusM`（例如 `0.02`） |

**能直接粘的例子（一块 2 页的翻牌水牌）**：

```json
"faces": {
  "pid_1": {
    "background": "#FF101418",
    "textColor":  "#FFF2F4F6",
    "side":       "normal",
    "pageSeconds": 5,
    "drum": { "count": 2, "turnFraction": 0.25, "radiusM": 0.02 },
    "pages": [
      { "name": "上行",
        "elements": [
          {"type": "text", "text": "上行 开往 {pid.terminus}", "x": 0.5, "y": 0.5, "size": 0.5, "align": "center"},
          {"type": "rect", "x": 0, "y": 0.94, "w": 1, "h": 0.06, "color": "#FFFFB300"}
        ] },
      { "name": "下行",
        "elements": [
          {"type": "text", "text": "下行 开往 {pid.next}", "x": 0.5, "y": 0.5, "size": 0.5, "align": "center",
           "when": {"!!": [{"var": "pid.next"}]}},
          {"type": "rect", "x": 0, "y": 0.94, "w": 1, "h": 0.06, "color": "#FF4CAF50"}
        ] }
    ]
  }
}
```

看它的两个面：`-Page 0` 与 `-Page 1` 各出一张（`-Page` 是"硬看某一页"，不受 `pageSeconds` 影响）。

### 5.3 `image`：牌上贴图

```json
{"type": "image", "src": "mmtr:vehicle/face/logo.png", "x": 0.02, "y": 0.55, "w": 0.12, "h": 0.35,
 "fit": "contain", "alpha": 1, "tint": "#FFFFFF"}
```

* `src` 是**资源包路径**：`命名空间:路径`，文件放 `<资源包>/assets/<命名空间>/<路径>`
  （例：`mmtr:vehicle/face/logo.png` ⇒ `assets/mmtr/vehicle/face/logo.png`）。**冒号不能省**。
* `fit`：`stretch`（拉满框，默认）/ `contain`（装进去、留边）/ `cover`（铺满框、超出的裁掉）。
* 图找不到 ⇒ 画一个**洋红占位框** + 一行小字（写着 `src` 的值），其余元素照画。
  离线出图时用 `-Pack <资源包目录>` 告诉它去哪找（不给就自己去找 `resourcepacks`）。

### 5.4 `foreach`：按列表重复画

```json
{"type": "foreach", "var": "calls", "as": "call", "index": "i", "limit": 8,
 "elements": [
   {"type": "text", "text": "{call.name}", "x": 0.05, "y": 0.5, "size": 0.4, "align": "left",
    "when": {"<": [{"var": "i"}, 3]}}
 ]}
```

* 数据：`var`（字段路径）或 `of`（一条表达式，结果要是列表）**二选一**。
* 每一项画 `elements` 里的东西；`as`/`index` 是**绑给子元素的名字**：子元素里 `{"var":"call.name"}`、
  `{"var":"i"}` 直接用。`limit` 默认 32（上限 256，超出钳回）。
* 数据**不是列表**（字段缺失 / 写成了标量）⇒ **一项都不画**（与"取不到内容的行不画"同一条口径）。
  列表也可以来自 `vars` 里**写死的数组**：`"vars": { "calls": ["海山", "鸥湾", "榕屿"] }` —— 数组在 JSONLogic 里就是"逐项求值后得到一个列表"，上面那段就会按 `limit` 画若干项（详见格式文档 §6.4）。

---

## 6. 动画（`anim`）

给元素加一个 `anim` 段，它就动起来（四种）：

| `kind` | 自己认的键（缺省） | 效果 |
| --- | --- | --- |
| `blink` | `onMs`（600）、`offMs`（600） | 亮 `onMs`、灭 `offMs` 交替 |
| `marquee` | `spanMs`（4000）、`direction`（`left`）、`gap`（0.3） | **文本**横向走马灯：视口 = 元素的 `w`（`0` = 从 `x` 到牌右缘），`gap` 是接缝（牌高比例） |
| `fade` | `spanMs`（1500）、`min`（0.25） | 透明度在 `min`..1 之间来回（三角波） |
| `spin` | `spanMs`（2000） | `rotate` 在一个周期里转一圈（叠在元素自己的 `rotate` 上） |

```json
{"type": "text", "text": "列车进站", "x": 0.5, "y": 0.5, "anim": {"kind": "blink", "onMs": 600, "offMs": 600}}
{"type": "text", "text": "本站 鸥湾 → 海山 → 榕屿", "x": 0, "y": 0.75, "w": 1, "size": 0.28,
 "anim": {"kind": "marquee", "spanMs": 4000, "direction": "left"}}
```

**"8 fps 是重画节拍，不是动画步长"** —— 这句要分清：

* 动画的时间轴是**客户端时钟**（毫秒），位置/透明度每帧按时间算，所以动画**不会**因为掉帧而变慢；
* 但**贴图重画**受 `fps` 限制（缺省 8，钳 1..30）：文档里有动画时，重画签名里才带
  `bucket = floor(t / (1000/fps))`。也就是说**你看到的是 8 fps 的连续画面**，
  而走马灯"该走到哪"仍由毫秒时钟算 —— 8 fps 只是取样点，不是动画走了几步。
* 这不是省事的妥协：走马灯/闪烁 8 fps 已经够顺，代价是 60 fps 的 1/7。
* `blink` 与其他三种不同：**灭着的时候整个元素不画**（`fade`/`spin`/`marquee` 永远画，只改透明度/角度/位移）。
* 不认识的 `kind`（拼错 `blnik`）⇒ **当没有动画**（照常显示成静态的）—— 自检报 **P8**。
* `marquee` 只对 `text` 有效，写在别的元素上等于没写（自检也报 P8）。
* 只有动画/轮转/翻牌机才会让这块面"随时间变"；**没有动画的牌一个字都不多画**
  （回到"数据不变就不重画"，见 §11 的症状表）。

---

## 7. 模板与过滤器

`"开往 {pid.terminus}"`；`{{` `}}` 是花括号转义。过滤器写在竖线后：

| 过滤器 | 作用 |
| --- | --- |
| `upper` / `lower` / `trim` | 大小写 / 去空白 |
| `int` | 四舍五入到整数（`{speedKmh|int}`） |
| `num:N` | 保留 N 位小数（N ≤ 4） |
| `pad:N` | 前面补 0 到 N 位（N ≤ 16；班次号 `00101`） |
| `len` | 文本长度 |
| `default:文本` | 空值时用它（`{hold.reason|default:正常}`） |

数字默认不写多余的小数点：`1.0` 渲染成 `1`。

---

## 8. 逻辑方言（JSONLogic 子集）

`when`（单个元素）、`require`（整块面或某一页）、`vars`（命名条件）、`needle`（指针值）、
`pageExpr`（当前页）、`foreach` 的 `of`（数据源）—— 这几处都写这种表达式。
**格式是公开的 JSONLogic**（学它不会白学；将来换第三方实现，你的文档一个字节都不用改）：

| 算子 | 例 |
| --- | --- |
| `var` | `{"var": "pid.next"}`、带缺省 `{"var": ["x", 0]}`、整个数据 `{"var": ""}` |
| `if` | `{"if": [条件, 真值, 条件2, 值2, 兜底]}` |
| `and` `or` `!` `!!` | `{"!!": [{"var":"pid.service"}]}` = "这个字段有值" |
| `==` `!=` `===` `!==` | 宽松/严格相等 |
| `<` `<=` `>` `>=` | 可连写：`{"<": [0, {"var":"speedKmh"}, 5]}` |
| `+` `-` `*` `/` `%` | 算术（`-` 单参数 = 取负） |
| `min` `max` | 忽略 null |
| `cat` `substr` `in` | 拼接 / 截取 / 包含 |
| `missing` `missing_some` | 缺哪些字段 |
| `?:` | `{"?:": [条件, 真值, 假值]}` |
| `some` `all` `none` `filter` `map` | 数组遍历（谓词里的 `var` 相对于**当前元素**） |

**四条口径**（都是刻意的，别按直觉写）：

1. **真值**：`null`/false/0/空串/空数组 = 假。所以 `"0"`、`"false"` 是**真**（字符串非空）。
2. **"有值"要写 `{"!!": [{"var":"x"}]}`**，不要写 `{"!=": [{"var":"x"}, ""]}` ——
   取不到的字段是 `null`，而 `null != ""` 是**真**。
3. **比较遇 null 一律假**（不把"没有数据"当 0），算术里类型不匹配得 `null`（不抛异常、不当 0）。
4. 表达式有预算（≤512 节点、≤24 层）；超了那**一个元素**被跳过并记一条日志。

---

## 9. 数据字段：能显示什么

- 全表（53 行，含类型 / 单位 / 来源 / 更新节拍 / 说明）：
  [`fields.json`](../tools/face-studio/fields.json)。
  离线看一眼某块面实际拿到的数据：给 `preview.ps1` 加 `-PrintData`。
- 最常用的一批：`pid.service` / `pid.terminus` / `pid.next`、`speed`（m/ms）、`speedKmh`（派生）、
  `limitKmh`、`job.id` / `job.step` / `job.steps` / `job.note`、`hold.reason`、`driver`、`mode`、
  `cab.end`、`lzb.targetKmh` / `lzb.targetM`、`light.a` / `light.b`、`pinned`、`active`、`clock`。
- **节拍**那一列很重要：`tick` = 变了就随稀疏补丁推（每 tick 都可能变）；`snapshot` = 只在整份快照时更新
  （别拿它做"实时读数"）。
- 要显示**表里没有**的量 ⇒ §10：能算的走第 5 级（扩展字段，现在就能做），要权威量的走第 2 级（四步曲）。

---

## 10. 卡住了怎么办（升级阶梯，每一级都不用改资源包格式）

先记住一句：**第 3 / 4 / 5 级现在就能用**（F4 已落地 —— 元素画法 / 算子 / 过滤器 / 客户端算的字段
四样都能由附属模组用 SPI 加），所以"我要一个现成元素画不出的东西"不再是"等引擎支持"。

| 级 | 卡在哪 | 谁做 | 交付物 |
| --- | --- | --- | --- |
| 1 | 能用现成的元素与条件表达（`text`/`rect`/`gauge`/`image`/`foreach` + 32 个算子 + 8 个过滤器） | 你自己，只写 JSON | 一份 `faces` 段 |
| 2 | 缺**字段**（要一个引擎里没有的权威量，例如"车门开度%"） | 引擎/模组维护者（或你自己提 PR） | 走四步曲：① 引擎算出来 + 进镜像（`schema/data/vehicle.json`、标脏、每 tick 变的量必须进 `VehicleSyncPatch.DYNAMIC_KEYS`）② `MmtrVehicleFaceSource` 加 `case` ③ `MmtrFaceFields` 加一行 ④ 重生成 `fields.json`（用例会写出 `.actual`，拷过去）。**四方核对用例**保证不会漏步 |
| 3 | 缺**公式**（新的算子）或**格式化**（新的过滤器） | 附属模组（现在就能做） | `registrar.function("厂家:名字", (arguments, data) -> …)` / `registrar.filter("厂家:名字", (value, parameter) -> …)`；文档里写 `{"厂家:名字": [...]}` / `{字段\|厂家:名字}`。**必须带命名空间** |
| 4 | 缺**画法**（渐变条、贴图动画、画不出来的形状） | 附属模组（现在就能做） | `registrar.element("厂家:名字", (canvas, document, element, paint) -> …, "它认的键"…)`；文档里写 `{"type": "厂家:名字"}` |
| 5 | 缺**取值，但可以从已有数据算**（例如由 `motor.forceN` 算"牵引/惰行/制动"） | 附属模组（现在就能做） | 一个 `MmtrFaceField`：`registrar.field(new MmtrFaceField(){ … value(Map<String,Object> values){…} })`。它只能读**已经收集好的快照**，于是扩展字段与内置字段在面文档里行为完全一样（一样进重画签名、一样能离线出图） |
| 6 | 跨面协同（整列车同步翻页等） | 还没有这条路 | 提需求：目前只有"文档级 `vars` + 数据字段"这两样，没有面级 state |

**第 6 级与第 2 级的区别一句话**：第 2 级是"让**引擎**多一个权威量"（要动同步与镜射，不是 SPI 的活）；
第 5 级是"拿**已有的**快照算一个新量"（客户端算，附属模组自己就能加）。

### 10.1 最小可抄的例子：给文档加一个 `{"type": "vendor:bar"}`

这是**完整的一份**（能直接编译；下面把注释压到最少，完整带注释的一版在参考实现里），改编自
[`sandbox/face-addon/src/vendor/faceaddon/AddonFaceExtension.java`](../../../sandbox/face-addon/src/vendor/faceaddon/AddonFaceExtension.java)：

```java
package vendor.faceaddon;                                    // ← 你自己的包名，别用 org.mtr.*

import org.mtr.mod.mmtr.face.MmtrFaceExtension;
import org.mtr.mod.mmtr.face.MmtrFaceLogic;
import org.mtr.mod.render.panel.MmtrFaceRegistrar;

public final class FaceaddonFaceExtension implements MmtrFaceExtension {

	@Override
	public void register(MmtrFaceRegistrar registrar) {
		// ① 元素画法：文档里写 {"type": "vendor:bar", "value": 0.42} 就画一根横条
		//    ★ 名字必须带命名空间（厂家:名字）：不带冒号会被引擎拒绝并记一条账
		registrar.element("vendor:bar", (canvas, document, element, paint) -> {
			// canvas 是**米制**：x/w 乘牌宽，y/h 乘牌高，(0,0) 是左下角、y 朝上
			final double x = element.x() * canvas.widthM();
			final double y = element.y() * canvas.heightM();
			final double height = Math.max(element.h(), 0.02) * canvas.heightM();
			// element.raw().get("value") 拿的是**原始 JSON**（表达式还没求值）；
			// 要表达式的结果就用 MmtrFaceLogic.eval(...) + paint.data()
			final Double fraction = MmtrFaceLogic.asNumber(MmtrFaceLogic.eval(element.raw().get("value"), paint.data()));
			final double width = Math.max(0, Math.min(1, fraction == null ? 0 : fraction)) * element.w() * canvas.widthM();
			canvas.fill(x, y, element.w() * canvas.widthM(), height, 0x40FFFFFF);          // 底槽
			canvas.fill(x, y, width, height, element.color() == 0 ? document.textColor() : element.color());
		}, "value");   // ← ★ 报上它认的键：报了之后，作者写 {"value":…} 不会被"未知键"守卫当成拼错
	}
}
```

**装法**（SPI 走 `ServiceLoader`，不是反射）：

1. 把这个 `.java` 放进你模组的源码树（包名 `vendor.faceaddon`）；
2. 在**资源**目录放一个文件
   `src/main/resources/META-INF/services/org.mtr.mod.mmtr.face.MmtrFaceExtension`，
   **内容就一行**：`vendor.faceaddon.FaceaddonFaceExtension`（类的全限定名）；
3. 打模组、装客户端。资源包**一个字节都不用改** —— 它只是写了 `vendor:bar` 这个名字。

**不用手抄**：工作室（`face-studio.ps1`）打开一块面之后，顶部有「**导出 Java 骨架**」——
按这块面用到的四类东西（非内置元素类型 / 非内置算子 / 非内置过滤器 / 不在 `fields.json` 里的字段）
各生成一个带 TODO 的桩，并把上面那个 ServiceLoader 声明的内容与放置目录一并显示出来。
一份这样的骨架已经真的编译过（见 `sandbox/face-addon/export-probe/`）。

### 10.2 怎么让自检认得我的扩展（三个放宽开关）

`verify_face.js` 是**离线**跑的，看不到你装没装那个模组，所以会把扩展用的名字报成三类**假警告**：

| 报什么 | 为什么 | 怎么放行 |
| --- | --- | --- |
| **P4**「不认识的元素类型 `vendor:bar`」 | 键表（`schema.json`）里只有随包发行的 9 种 | `--allow-type vendor:bar` |
| **P3**「字段 `vendor:traction` 不在字段表里」 | `fields.json` 里只有内置字段 | `--extra-fields <扩展字段.json>`（数组 `["vendor:traction"]` 或对象 `{"vendor:traction":"牵引状态"}`） |
| **P7**「不认识的过滤器 `vendor:kmh`」 | `MmtrFaceText.filters()` 只列内置 8 个 | `--allow-filter vendor:kmh` |

```powershell
node mmtr\tools\anchor-check\verify_face.js --anchors <打包出来的 anchors.json> `
    --allow-type vendor:bar --extra-fields sandbox\face-addon\probe\extra-fields.json --allow-filter vendor:kmh
```

- 三个开关都可以重复给，也可以一次给逗号分隔的一串（`--allow-type vendor:bar,vendor:gauge2`）。
- 报告里会**单列**出放行了什么（`扩展类型：vendor:bar（--allow-type 给的）`），
  结尾还有一节「这次放宽了什么」逐条写清 —— **不许悄悄放行**。
- **不给开关时行为与从前逐字相同**（回归用过：既存锚点的输出一个字符都没变）。
- 放宽的只有"认得什么"：几何/结构（P6）、算子形状（P5）、动画（P8）照旧按内置清单判。
- 扩展的键**不进** `schema.json`，扩展字段**不进** `fields.json`，扩展算子/过滤器**不进**
  `operators()`/`filters()` 那两份最小集 —— 那三份是"随包发行"的口径，工具一致性用例逐项钉着它们。

---

## 11. 出问题怎么查（症状 → 原因）

| 症状 | 先看这里 |
| --- | --- |
| 那块面**完全不出现** | ① `faces` 的键是不是锚点名（`verify_face.js` P1）；② 锚点的法线是不是朝车外（朝里 ⇒ 被背面剔除，站台上看不见）；③ `require` 是不是不成立（`preview.ps1` 会直接说"按 require 不画"）；④ 用的是不是 `mmtr_face_*`（`mmtr_dest_board_*` 会被 `groupMap.body` 吞掉） |
| 少画了一样东西 | 元素类型写错 / 算子写错 / 字段名写错 —— 这三种游戏里都是**静默跳过 + 一条日志**（`[MMTR] 面文档 … 被跳过`），先看日志，再跑 `verify_face.js` |
| 字被牌边切掉 | `shrinkToFit`（默认 0.9）没起作用？或字号太大：`size` 是**牌高**的比例（牌很扁时 0.5 已经占满高度） |
| 字很小/很糊 | 牌太大而像素密度不够：长边被 512 px 上限夹住（1.24 m ⇒ 约 413 px/m）。把屏拆成两块锚点，或调 `pxPerMetre`（上限不变） |
| 某个字段永远空 | 字段名对不对（`fields.json`）；它在引擎里是不是**这个状态下**有值（`preview.ps1 -Preset` 换状态看）；节拍是不是 `snapshot`（那就要等整份快照） |
| 改完资源包没变化 | 资源重载（F3+T）会清掉文档缓存；换包要**提版本号**（不要覆盖已启用的包） |
| 水牌该不挂的车挂了牌 | 门写成 `{"!=": [{"var":"pid.service"}, ""]}` 了 —— 改成 `{"!!": [{"var":"pid.service"}]}`（§9 口径 2） |
| **写了 `pages` 又写了 `elements`：牌上东西少了/不对** | 顶层 `elements` **被整段忽略**（`pages` 说了算，引擎与绘制器各记一条提示）。自检报 **P6**；要留就把那些元素移进某一页 |
| **某个 `image` 是个洋红框** | 图没找到。① `src` 是不是 `命名空间:路径`（少冒号会被当 `minecraft:` 找）；② 文件在不在 `<包>/assets/<命名空间>/<路径>`；③ 离线出图要 `-Pack <资源包目录>`；④ 日志里那行 `面文档的 image 找不到图片「…」` 会原样打出 `src` |
| **动画不动** | ① **先看老规矩：数据没变就不重画** —— 这是 F0 的口径，没有动画的牌一个字都不多画。有动画时重画签名才带时间桶（`fps`，缺省 8）；② 签名里还有"文档 id"：改了 JSON 但**资源包版本号没提**，客户端拿到的还是旧文档（F3+T 重载 + 提版本号）；③ `anim.kind` 是不是拼对了（拼错 = **当没有动画**，自检报 P8）；④ `pageSeconds` 是不是 `0`（不自动转）；⑤ `drum` 有了但没写 `pages`（没面可贴） |
| **`foreach` 一项都没画** | 数据**不是列表**（字段缺失、或写成了标量）⇒ 引擎按"0 项"处理（与"取不到内容的行不画"同一条口径）。查 `var` 指的那条路径在不在（`fields.json`）、`of` 的结果是不是列表；`vars` 里写死的数组也能当数据（见 §5.4 末尾） |
| 自检说 PASS 但游戏里不对 | 先确认自检**跑的是哪份文件**：`--anchors` 要指到打包出来的 `mmtr_anchors_*.json`（车辆配置 `consist/*.json` 的 `faces` 是**嵌在 `cars[]` 里**的，顶层没有 `faces`，直接喂给它只会得到一份空 PASS）。再确认 P8/P9 是不是「没跑」（缺 `schema.json`）|

---

## 12. 四个数据预设（离线预览用）

`-Preset running`（跑着：60 km/h、有 LZB 目标）/ `stopped`（停站开门）/ `return`（回库趟：终点与下一站都为空）/
`idle`（没任务：班次号空、停放钉住）。要改某个字段就 `-Data speed=0.02,pid.next=干沙`（可多次）。
想知道这块面在预设下**具体拿到什么**，加 `-PrintData`。

---

## 13. 命令速查

```powershell
# 打包（提版本号，别覆盖已启用的包）
pwsh -File mmtr\scripts\pack-consist.ps1  mmtr\tools\obj-mtr-packager\consist\<车型>.json -Version 37
pwsh -File mmtr\scripts\pack-vehicle.ps1   mmtr\tools\obj-mtr-packager\example\vehicle.<车型>.json

# 离线出图 / 看清有什么（下面 <anchors.json> 都指**打包出来的** mmtr_anchors_*.json）
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <anchors.json> -List
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <anchors.json> -Face <面名> -Preset stopped -Out .\a.png
# 老水牌版式（pid/next 段）也照样出图
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <anchors.json> -Builtin pid -Preset running -Out .\pid.png

# v2：时刻 / 页 / 图片目录
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <anchors.json> -Face <面名> -TimeMs 8000 -Out .\t8s.png
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <anchors.json> -Face <面名> -Page 1    -Out .\p1.png
pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <anchors.json> -Face <面名> -Pack .\mmtr\game\fabric\run\resourcepacks\MMTR_SAF420_v37
# 三个可以一起给（-TimeMs 是毫秒，-Page 从 0 起，-Pack 用 `;` 分隔多个包目录）

# 静态自检（P1..P9，退出码 0 = 全过、1 = 有 FAIL、2 = 输入缺失）
# P8 = 动画 kind 白名单，P9 = 未知键（colour/siz 这种拼错）；缺 schema.json 时这两条会写「没跑」
node mmtr\tools\anchor-check\verify_face.js --anchors <anchors.json>

# 用了扩展的文档（装了那个模组才认得的名字）：显式放行那三条假警告（§10.2）
node mmtr\tools\anchor-check\verify_face.js --anchors <anchors.json> `
    --allow-type vendor:bar --extra-fields sandbox\face-addon\probe\extra-fields.json --allow-filter vendor:kmh

# 工作室：改排版 + 一键导出扩展骨架（F4）
pwsh -File mmtr\tools\face-studio\face-studio.ps1 -Action open -Anchors <anchors.json>
# 页面顶部「导出 Java 骨架」按当前这块面生成一份能编译的 MmtrFaceExtension（四类桩各留 TODO），
# 并把 META-INF/services 声明的内容与放置目录一并显示；面板也可以一条链接直达：
#   http://127.0.0.1:8910/?anchors=/data/<工作区相对路径>&export=1

# 锚点几何自检（水牌那条老链，仍然有用）
node mmtr\tools\anchor-check\verify_pid.js --anchors <anchors.json>
```

**离线出图的诚实边界**：它跑的是游戏里同一份 Java2D 与同一套逻辑，但**中文字形走系统兜底**，
与游戏内的 HarmonyOS 有细微差别 —— **排版看这张图，字形以游戏为准**。
`image` 元素也走同一条"资源包 → 磁盘兜底"的路（`-Pack`），图找不到画洋红占位框。
