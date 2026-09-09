# 00-历史（M0 之前的资料，已入库）

本目录的文档写于 MMTR 立项前后（2026-09 上旬），记录了环境搭建、MTR 源码分析、可行性论证与架构决策。
**结论仍然有效，但里面出现的目录名是整理前的旧路径。** 2026-09-09 的工作区整理把目录挪了位置：

| 文档里的旧路径 | 现在的路径 |
|---|---|
| `tools/jdk-*`、`tools/idea`、`tools/Blockbench` | `env/jdk-*`、`env/idea`、`env/blockbench` |
| `tools/obj-mtr-packager` | `mmtr/tools/obj-mtr-packager` |
| `mods/` | `vendor/mods/` |
| `sources/MTR-source` | `vendor/upstream/MTR-4.0.5/` |
| `sources/Minecraft-Transit-Railway-master` | **已删除**（与上一条同版本，冗余副本） |
| `models/` | `assets/models/` |
| `docs/reference/` | `mmtr/docs/reference/` |
| `docs/00–06` | 本目录 + `docs/02-运行与作业/06-IntelliJ-IDEA配置指南.md` |
| 根目录 `*.log` | `logs/2026-09/` |
| `creator-studio/` | `mmtr/apps/creator-studio/` |

## 各文件

| 文件 | 内容 | 状态 |
|---|---|---|
| `00-环境搭建与资源清单.md` | 每个产物的版本、来源、SHA256、用途与安装方式 | 历史（路径需按上表换算） |
| `01-MTR-4.0.5-1.20.4-源码分析.md` | MTR 仓库/构建/包结构、注册体系、资源格式与扩展点 | 有效 |
| `02-可行性分析与技术路线.md` | 任务驱动客货运服务器的可行性与路线选择 | 有效（决策已定案，见 03） |
| `03-MMTR-架构决策与里程碑.md` | 产品决策 + 服务器架构 + 里程碑 M0–M5 | **有效**（多处文字仍引用） |

> 整理方案与前后对比见工作区根的 `整理方案.md`；当前项目文档入口是 `mmtr/docs/README.md`。
