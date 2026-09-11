# engine/website — MMTR 控制台（Vite + Vue 3 + Naive UI）

> 2026-09-11：原 Angular 脚手架（连同 MTR 官网整套前端）已整体删除，改为 **Vite 7 + Vue 3 + TypeScript + Naive UI**，
> 从零重建。本文件记录**接线与硬约定**，避免再踩已经踩过的坑。

## 〇、前后端分离（最重要的一条）

**Java 侧只做两件事：把静态文件发出去、把标准 HTTP 接口暴露出来。** 网页是**独立构建、独立部署**的前端应用。

- **dev（默认）**：`scripts/dev-server.ps1` 会设 `MMTR_WEB_ROOT=engine\website\dist\website\browser`，
  引擎（`Main.resolveWebContentProvider`）于是**直接从磁盘读**这个目录。改前端只要：

  ```powershell
  cd engine\website; npm run build      # 然后刷新浏览器
  ```

  **不需要**重打 jar、**不需要**重启服务端。（早期把前端焊进 jar，改一行 CSS 都要重启 40 秒的服务端，
  这是调试慢的根结构原因。）
- **前端热重载**：`npm run dev`（5173），`vite.config.ts` 已把 `/mtr/api` 代理到 `127.0.0.1:8888`。
- **发布**：Gradle 的 `setupWebserver` 仍会把 `dist/website/browser` 嵌进 jar（`WebserverResources`），
  没有 `MMTR_WEB_ROOT` 时引擎就用内嵌那份。发布包因此仍然是自包含的单 jar。
- **接口即契约**：前端的唯一数据来源是 `/mtr/api/map/*`（清单见第四节）。Java 改接口要当成契约变更：
  要么加字段、要么开新版本路径，别让网页跟着一起炸。

## 一、构建与部署（三个硬约定）

1. **`base: "/"`** —— 引擎静态服务把 index.html 挂在 `/`，资源必须从根路径解析。
   用 `base: "a"`（Angular 时代遗留的 `--base-href a`）会让 `/a/*.js` 落到 index 回退，**页面永远白屏**。
2. **`build.outDir = "dist/website/browser"`** —— 这个目录既是 dev 的 `MMTR_WEB_ROOT`，
   也是 Gradle 任务 `WebserverSetup` 嵌 jar 的来源。改了它，两边都断。
3. **不要开 CSP 自动注入**（Angular 的 `security.autoCsp` 那类）——它会用 CSP 把样式表锁成 `media="print"`，
   再靠一个内联脚本放行；脚本一旦被 CSP 自己挡住，页面就变成"无样式 + 无框架"的裸 HTML。

构建：

```powershell
cd engine\website
npm run build                     # vue-tsc 类型检查 + vite build → dist/website/browser
```

验证：`http://127.0.0.1:8888/index.html`（`/` 同页），静态资源在 `/assets/*.js|css`、字体在 `/media/*.woff2`。


## 二、目录

```
engine/website/
├── index.html                 ← Vite 入口（<div id="app">）
├── vite.config.ts             ← base/outDir/别名/开发代理
├── package.json               ← dev / build / preview / lint
├── public/media/*.woff2       ← 字体（原样拷贝进产物，CSS 里用 /media/... 引用）
└── src/
    ├── main.ts                ← createApp + 全局样式
    ├── App.vue                ← 外壳：NConfigProvider(深色) + 上边栏 + 内容区
    ├── theme.ts               ← Naive UI 主题覆盖（纯黑、强调色 #0078D7、4px 圆角、DIN 字体）
    ├── styles/tokens.css      ← design tokens + @font-face
    ├── styles/base.css        ← 全局基线
    └── components/AppBar.vue  ← 上边栏（标题 + 读数槽）
```

## 三、字体（从 C# 端 ShuisoiSimUniverse 搬来的）

`tools/fonts/build-fonts.py` 子集化 → `public/media/*.woff2`（产物已入库，构建时不需要 Python）：

| 文件 | 字体 | 用途 |
| --- | --- | --- |
| `din1451alt-regular.woff2` (10 KB) | Alte DIN 1451 Mittelschrift | 西文/数字：数值、编号、标题里的拉丁部分 |
| `harmonyos-sans-sc-regular.woff2` (909 KB) | HarmonyOS Sans SC Regular | 中文正文（子集到 GB2312 常用字） |
| `harmonyos-sans-sc-bold.woff2` (924 KB) | HarmonyOS Sans SC Bold | 标题/强调 |
| `dream-han-serif-cn-w24.woff2` (3 KB) | Dream Han Serif CN W24 | 品牌衬线（只留用到的字） |

重新生成：`python tools\fonts\build-fonts.py`（需 `fonttools`、`brotli`）。

**CSS 坑**：颜色一律写 `rgba(...)`，不要用 8 位 hex（`#22ffffff` 这类）。
实测构建用的压缩器会把 `#22ffffff` 缩成 `rgb(34,255,255)`——半透明白雾变成青色实色，整条上边栏发蓝。

## 四、可用接口（重建时按需接）

| 接口 | 内容 |
| --- | --- |
| `GET /mtr/api/map/mmtr-topology` | 真实轨网：`{nodes[], rails[]}`（节点带 `block` 归属） |
| `GET /mtr/api/map/mmtr-sections` | 水闸区间层：`{sections[], blocks[], nodes[], railCount}` |
| `GET /mtr/api/map/mmtr-schematic` | 1×1 格对齐区间图：`{cellSize, cellM, nodes[], rails[], blocks[]}` |
| `GET /mtr/api/map/mmtr-trains` | 车辆 / 站台 / 信号灯显 / 进路 / 道岔 |
| `GET /mtr/api/map/mmtr-points` | 道岔台（进向 × 腿 × 授权状态） |
| `POST /mtr/api/map/mmtr-command` | 引擎诊断命令（`{command}`：`blocks`、`blocks-v2`、`lamps-v2`…） |
| `POST /mtr/api/map/mmtr-point-op` | 搬岔 / 清岔 / 锁定 |

开发时 `npm run dev`（5173 端口）已把 `/mtr/api` 代理到 `127.0.0.1:8888`，前端代码里永远用相对路径。

## 五、当前状态

- 上边栏完成：48px 玻璃条（深色实底 + 白色渐变雾 + 1px 高光）、标题「MMTR控制台」、右侧读数槽留空。
- 内容区为空；后续按指令一步一步接。
