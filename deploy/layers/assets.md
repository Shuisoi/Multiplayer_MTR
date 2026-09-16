# assets/ —— 自有创作源（不可再生，务必备份）

这里放**无法用命令重新生成**的创作资产：Blender 工程、导出的 OBJ/MTL、贴图。

## models/blender/ 结构

| 目录 | 内容 | 打包配置 |
|---|---|---|
| `HST_H/` | HST 头车（`hst_h.obj` + 贴图） | `mmtr/tools/obj-mtr-packager/consist/hst8.json` |
| `HST_B/` | HST 中间客车（OBJ 在根，贴图在 `tex/`） | 同上 |
| `p1/` | P1 客车（`pack/` 是打包用目录） | `consist/newstock.json` |
| `saf101/` | SAF101 双端机车 | 同上 |
| `cargotest/` | 货车测试车 | 同上 |
| `hst_car/` | **已不存在**（`example/vehicle.hst.json` 仍指向它，属历史示例） | — |
| `*.blend` | Blender 源工程 | — |
| `CARS.blend` | 多车合集源 | — |

打包配置里一律用 `${MC_ROOT}/assets/models/blender/...` 占位符，因此**本目录可以整体移动位置**，只要同步改 `env/workspace.env.*` 里的 `$MODELS` 与配置里的占位符解析。

## 规矩

- 大文件（`.blend` / `.blend1` / `.glb` / `.obj`）**不进 git**，只进备份；`mmtr/.gitignore` 已按扩展名忽略。
- 每新增一台车：建 `<id>/` → 导出 OBJ + 贴图 → 在 `mmtr/tools/obj-mtr-packager/consist/*.json` 里加一条 → 跑 `pack.bat` 验证。
- 打包流程规范见 `mmtr/docs/02-运行与作业/MMTR-OBJ车辆资源包-标准化工作流.md`。
- `*.blend1` 是 Blender 自动备份，可随时删。
