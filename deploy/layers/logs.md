# logs/ —— 日志归档（可删）

按年月分目录，例如 `logs/2026-09/`。

## 为什么集中

整理前根目录散着 32 个 `.log`（`baseline-test.log`、`installer.log`、`speedlimit-run.log` …），
`mmtr/engine/` 里还有 15 个，`mmtr/game/` 里 2 个 —— 既找不到，又因为 `mmtr/.gitignore` 的 `*.log`
规则永远进不了版本控制。

## 规矩

1. **脚本不要再往仓库里写日志**：目标目录一律 `logs\<yyyy-MM>\`。
2. 需要当证据入库的日志，改名成 `.txt`（或放进 `notes/` 的引用里），否则会被 `*.log` 忽略掉。
3. 本目录可以整体清空；要保留的结论请写进 `mmtr/notes/`。
