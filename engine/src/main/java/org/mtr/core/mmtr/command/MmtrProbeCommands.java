package org.mtr.core.mmtr.command;

import org.mtr.core.mmtr.probe.MmtrProbe;
import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;

/**
 * {@code probe …}：服务端性能探针的开关与读数（notes/337）。
 *
 * <h3>为什么这条指令必须在引擎侧就受理</h3>
 * <p>它要回答的问题（"服务端为什么落后 40 s"）是**现场问题**：人坐在电脑前看着 {@code Can't keep up!}
 * 刷屏的那一刻就要读数。而两条已有的读法都不满足这个约束 —— 崩溃栈要等它崩，JFR 要停机加参数重启
 * （重启意味着重新等区块生成，而区块数正是自变量之一）。所以探针的开关必须能**运行中**翻动。</p>
 *
 * <p>分段表本身是引擎侧的（{@link MmtrProbe}），所以 {@code on}/{@code off}/{@code dump}/{@code reset}
 * 全在这里就地完成；只有"MTR 世界里到底有几盏灯/几块区块"这类读数需要游戏端遍历世界，
 * 由 {@link MmtrProbe#setExtraReporter} 注册的补充行补上。</p>
 *
 * <h3>与 {@code MmtrCommandExecutor} 里那条同名指令的分工</h3>
 * <p>两条都叫 {@code probe}，但走的是两个入口：网页指令栏 → 这里；游戏内/旧脚本 →
 * {@code MmtrCommandExecutor}。语义完全一致（都直接操作同一个静态 {@link MmtrProbe}），
 * 所以从哪边开都行、另一边立刻看得见。刻意的：诊断开关不该有"我刚才从哪开的"这种状态。</p>
 */
final class MmtrProbeCommands {

	private MmtrProbeCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "probe", verb);
		switch (verb) {
			case "on":
			case "start":
				MmtrProbe.setEnabled(true);
				result.line("已开：每 " + MmtrProbe.getReportIntervalTicks() + " tick 一行汇总，超过 "
					+ MmtrProbe.getWarnMillis() + " ms 的帧进最坏帧榜。");
				result.line("读数在服务端日志里搜 [MMTR-PROBE]；也可以随时 `probe dump` 现场读一次（不打断统计）。");
				break;
			case "off":
			case "stop":
				MmtrProbe.setEnabled(false);
				result.line("已关：分段不再计时（累计量保留，用 `probe reset` 清）。");
				break;
			case "dump":
			case "report":
			case "show":
				result.lines.add(MmtrProbe.fullReport(simulator.dimension));
				break;
			case "reset":
				MmtrProbe.resetAll();
				result.line("窗口与累计都已清零。做前后对照的口径：reset → 跑一段固定动作（例如让它空转 60 s）→ dump。");
				break;
			case "interval": {
				final int ticks = MmtrCommandDispatcher.intOption(options, "interval",
					positional.isEmpty() ? MmtrProbe.getReportIntervalTicks() : parseInt(positional.get(0), MmtrProbe.getReportIntervalTicks()));
				MmtrProbe.setReportIntervalTicks(ticks);
				System.setProperty("mmtr.probe.interval", Integer.toString(MmtrProbe.getReportIntervalTicks()));
				result.line("汇总间隔 = " + MmtrProbe.getReportIntervalTicks() + " tick（抓尖峰就调小，例如 20）。");
				break;
			}
			case "warn": {
				final long millis = MmtrCommandDispatcher.longOption(options, "warnMs",
					positional.isEmpty() ? MmtrProbe.getWarnMillis() : parseLong(positional.get(0), MmtrProbe.getWarnMillis()));
				MmtrProbe.setWarnMillis(millis);
				result.line("慢帧门槛 = " + MmtrProbe.getWarnMillis() + " ms（默认 40 ≈ 两个 tick）。");
				break;
			}
			case "file": {
				final String path = options.getOrDefault("file", positional.isEmpty() ? "" : positional.get(0));
				MmtrProbe.setFile(path == null ? "" : path);
				result.line("明细文件 = " + (MmtrProbe.getFile().isEmpty() ? "（关）" : MmtrProbe.getFile()));
				break;
			}
			case "status":
			case "":
				result.line("开=" + MmtrProbe.isEnabled()
					+ " 每=" + MmtrProbe.getReportIntervalTicks() + " tick"
					+ " 慢帧门槛=" + MmtrProbe.getWarnMillis() + " ms"
					+ " 明细文件=" + (MmtrProbe.getFile().isEmpty() ? "（关）" : MmtrProbe.getFile()));
				result.line(MmtrProbe.totalsLine(simulator.dimension));
				break;
			default:
				return MmtrCommandDispatcher.usage("probe 支持 on / off / status / dump / reset / interval / warn / file");
		}
		return result;
	}

	private static int parseInt(String raw, int fallback) {
		try {
			return Integer.parseInt(raw.trim());
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static long parseLong(String raw, long fallback) {
		try {
			return Long.parseLong(raw.trim());
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}
