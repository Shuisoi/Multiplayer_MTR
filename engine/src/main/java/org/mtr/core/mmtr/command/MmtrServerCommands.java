package org.mtr.core.mmtr.command;

import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;

/**
 * {@code server …}：服务端自己的运维动词（重启、停机）。
 *
 * <h3>为什么需要"标记文件"配合</h3>
 * <p>一个 JVM 不能重启自己 —— 引擎能让游戏端**优雅停机**，但把服务端再拉起来必须是**启动器**的事。
 * 所以这里拆成两半，各做各能做的那部分：</p>
 * <ol>
 *   <li>指令把"我要重启"写成启动器看得懂的标记文件（{@code game/fabric/run/mmtr-restart.request}），
 *       并请求优雅停机（存档、断开连接都走正常流程）；</li>
 *   <li>启动器（{@code scripts/dev-server.ps1}）在服务端退出后看到标记，就再启动一次 —— 这就是重启。</li>
 * </ol>
 * <p>用文件而不是"退出码"传达意图：退出码会被 gradle 吞掉（runServer 之后无论游戏怎么结束都返回 0），
 * 而文件是两边都能读、也看得见的东西。</p>
 *
 * <p>{@code 停机}不写重启标记，改写**停机标记**（{@code mmtr-stop.request}）：启动器看到它就知道
 * "是你要我停的"，既不会重启，也不会把这次当成启动失败去重试。</p>
 */
final class MmtrServerCommands {

	private MmtrServerCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "restart":
				return restart(simulator, options);
			case "stop":
			case "shutdown":
				return stop(simulator);
			default:
				return MmtrCommandDispatcher.usage("server 支持 restart / stop");
		}
	}

	/**
	 * {@code server restart [--delay=<秒>]}：优雅停机并让启动器再拉起来。
	 *
	 * @param options {@code --delay} 是"多久之后真的停"，默认 2 秒 —— 留给指令把结果回给调用方，
	 *                否则网页那边会看到连接被切断而不是"已受理"。
	 */
	private static MmtrCommandDispatcher.Result restart(Simulator simulator, Map<String, String> options) {
		final int delaySeconds = MmtrCommandDispatcher.intOption(options, "delay", 2);
		simulator.mmtrRequestRestart(delaySeconds);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "server", "restart");
		result.line("已受理重启：约 " + delaySeconds + " 秒后优雅停机，启动器看到标记会再启动一次。");
		result.line("标记文件：" + simulator.mmtrRestartMarkerPath());
		result.line("注意：只有由带重启循环的启动器（scripts/dev-server.ps1）拉起的服务端才会自己回来；直接 java -jar/IDE 启动的停掉就结束了。");
		return result;
	}

	/** {@code server stop}：优雅停机，不写重启标记（并写停机标记，免得启动器把它当成启动失败去重试）。 */
	private static MmtrCommandDispatcher.Result stop(Simulator simulator) {
		// 先把可能残留的重启标记清掉，否则"只想停机"会被启动器当成重启
		simulator.mmtrClearRestartMarker();
		simulator.mmtrRequestStopWithMarker(2);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "server", "stop");
		result.line("已受理停机：约 2 秒后优雅停机，启动器不会再拉起来（停机标记已写，它也不会把这次当成启动失败去重试）。");
		return result;
	}
}
