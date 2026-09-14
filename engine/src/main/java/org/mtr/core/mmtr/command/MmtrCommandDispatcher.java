package org.mtr.core.mmtr.command;

import org.mtr.core.data.Depot;
import org.mtr.core.data.Position;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.simulation.Simulator;

import java.util.ArrayList;
import java.util.List;

/**
 * 中控指令系统 (MMTR console command system).
 *
 * <h3>为什么要有这一层</h3>
 * <p>在这之前，"操作世界"的能力散在三处：网页的十来个专用接口（{@code mmtr-signal-op}、
 * {@code mmtr-point-op}、{@code mmtr-vehicle-op}…）、游戏端 tick 轮询的文本指令（{@code signals scan}）、
 * 以及只存在于 op 表里、控制台够不着的操作（{@code generate_by_depot_name} 等）。
 * 结果是每加一件事就要加一个接口，而且回复都是"OK 已入队"，没法核对到底做了什么。</p>
 *
 * <p>这一层把动词集中到一处，并由**引擎侧**直接执行 —— 引擎本来就持有车辆段、股道、信号、道岔的
 * 全部权威状态，所以绝大多数操作不需要绕到游戏端、也不需要重启。只有真正依赖已加载区块的操作
 * （例如扫描世界里的信号灯）才转交游戏端命令通道。</p>
 *
 * <h3>语法：名词打头</h3>
 * <pre>
 *   vehicle spawn &lt;车型&gt;... [--siding=&lt;股道id|名&gt; | --depot=&lt;车辆段id|名&gt; [--index=&lt;第几条&gt;]] [--count=&lt;n&gt;]
 *   vehicle remove &lt;车辆id|all|--siding=&lt;id&gt;|--depot=&lt;id&gt;&gt; [--dry-run]
 *   vehicle list [--depot=&lt;id|名&gt;]
 *   train couple &lt;主动车id&gt; &lt;目标车id&gt; | train uncouple &lt;车辆id&gt; &lt;在第几节之后切开&gt;
 *   train doors|changeends|cab|shunt|interlock|trace …   （转交游戏端：动的是世界里的实体）
 *   signal list [--state=&lt;红黄绿&gt;]
 *   signal set &lt;x&gt; &lt;y&gt; &lt;z&gt; [--angle=&lt;度&gt;] [--aspects=&lt;2|3|4&gt;]
 *   signal remove &lt;x&gt; &lt;y&gt; &lt;z&gt;
 *   signal bind &lt;x&gt; &lt;y&gt; &lt;z&gt; --rail=&lt;轨hex&gt;[,…]     （点选绑定：这盏灯守哪几根轨，一灯可多轨）
 *   manifest list | manifest add … | manifest remove … | manifest reload
 *   point set|lock|unlock|locks|release …
 *   query &lt;topology|signals|trains|points|sections|depots&gt;
 *   world scan-signals          （转交游戏端：只有它能枚举已加载区块）
 * </pre>
 *
 * <h3>回复</h3>
 * <p>不是"OK 已入队"，而是结构化结果：做了什么、影响了哪些 id、以及可供下一步核对的实情。
 * 失败时给出可读原因（参数怎么用、找不到什么），方便在网页指令栏里直接看。</p>
 */
public final class MmtrCommandDispatcher {

	/** 一条指令的执行结果。 */
	public static final class Result {
		public final boolean ok;
		public final String namespace;
		public final String verb;
		/** 受影响的对象 id（车辆 / 股道 / 信号键 …），调用方可以直接拿去核对。 */
		public final List<String> affected = new ArrayList<>();
		/** 人读的多行输出。 */
		public final List<String> lines = new ArrayList<>();

		Result(boolean ok, String namespace, String verb) {
			this.ok = ok;
			this.namespace = namespace;
			this.verb = verb;
		}

		public Result add(String id) {
			affected.add(id);
			return this;
		}

		public Result line(String text) {
			lines.add(text);
			return this;
		}
	}

	private MmtrCommandDispatcher() {
	}

	/**
	 * 执行一条指令。
	 *
	 * @param simulator 目标模拟器（引擎侧权威状态）
	 * @param command   整条指令文本
	 * @return 执行结果；语法不认识时 {@code ok=false} 并给出用法
	 */
	public static Result execute(Simulator simulator, String command) {
		final List<String> words = tokenize(command);
		if (words.isEmpty()) {
			return usage("（空指令）");
		}
		final String namespace = words.get(0).toLowerCase(java.util.Locale.ENGLISH);
		final String verb = words.size() > 1 ? words.get(1).toLowerCase(java.util.Locale.ENGLISH) : "";
		final List<String> positional = new ArrayList<>();
		final java.util.Map<String, String> options = new java.util.HashMap<>();
		for (int i = 2; i < words.size(); i++) {
			final String word = words.get(i);
			if (word.startsWith("--")) {
				final int equals = word.indexOf('=');
				if (equals < 0) {
					options.put(word.substring(2).toLowerCase(java.util.Locale.ENGLISH), "true");
				} else {
					options.put(word.substring(2, equals).toLowerCase(java.util.Locale.ENGLISH), word.substring(equals + 1));
				}
			} else {
				positional.add(word);
			}
		}

		switch (namespace) {
			case "vehicle":
				return MmtrVehicleCommands.execute(simulator, verb, positional, options);
			case "train":
				return MmtrTrainCommands.execute(simulator, verb, positional, options);
			case "signal":
				return MmtrSignalCommands.execute(simulator, verb, positional, options);
			case "point":
				return MmtrPointCommands.execute(simulator, verb, positional, options);
			case "manifest":
				return MmtrManifestCommands.execute(simulator, verb, positional, options);
			case "query":
			case "world":
				return MmtrQueryCommands.execute(simulator, namespace, verb, positional, options);
			case "server":
				return MmtrServerCommands.execute(simulator, verb, positional, options);
			default:
				return usage("不认识的名词「" + namespace + "」");
		}
	}

	/** 按空格切词，支持双引号包住带空格的参数。 */
	static List<String> tokenize(String command) {
		final List<String> out = new ArrayList<>();
		if (command == null) {
			return out;
		}
		final StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < command.length(); i++) {
			final char c = command.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			} else if (Character.isWhitespace(c) && !quoted) {
				if (current.length() > 0) {
					out.add(current.toString());
					current.setLength(0);
				}
			} else {
				current.append(c);
			}
		}
		if (current.length() > 0) {
			out.add(current.toString());
		}
		return out;
	}

	static Result usage(String reason) {
		final Result result = new Result(false, "", "");
		result.line(reason);
		result.line("可用指令：");
		result.line("  vehicle spawn <车型>... [--siding=<id|名>|--depot=<id|名> [--index=n]] [--count=n]");
		result.line("  vehicle remove <车辆id|all|--siding=<id>|--depot=<id>>");
		result.line("  vehicle list [--depot=<id|名>]");
		result.line("  train couple <主动车id> <目标车id> | train uncouple <车辆id> <在第几节之后切开>");
		result.line("  train doors <车辆id> [open|close|toggle] [--side=left|right|both] | train changeends <车辆id> | train cab <车辆id> <A|B|out>");
		result.line("  train shunt <车辆id> <目标轨hex|off> [--minutes=n] [--kmh=n] [--kind=SUBSIDIARY_SHUNT|CALLING_ON]");
		result.line("  train interlock <车辆id|all> | train trace [on|off|status]");
		result.line("  signal list [--state=<红|黄|绿>]");
		result.line("  signal set <x> <y> <z> [--angle=n] [--aspects=2|3|4]");
		result.line("  signal remove <x> <y> <z>");
		result.line("  signal bind <x> <y> <z> --rail=<轨hex>[,…] [--add=<hex>|--remove=<hex>|--clear]   ← 点选绑定（一灯可多轨）");
		result.line("  signal bind <x> <y> <z> --node=<x,y,z> [--angle=n] [--aspects=n]                   ← 按节点绑定（旧）");
		result.line("  signal why <x> <y> <z>");
		result.line("  manifest list | manifest reload | manifest add <depotId> <sidingId> [车型...] | manifest remove <depotId> [sidingId]");
		result.line("  point set <x> <y> <z> --via=<轨hex> --branch=n | point lock|unlock|release <x> <y> <z> --via=<轨hex>");
		result.line("  point unlock --all                                               ← 解开全部人工锁（含界面上没有按钮的进向）");
		result.line("  point locks                                                      ← 引擎现在锁着哪些（逐进向列出）");
		result.line("  point why <x> <y> <z>                                             ← 这个节点为什么（没）被认成一处道岔");
		result.line("  point list");
		result.line("  query <topology|signals|trains|points|sections|depots>");
		result.line("  world scan-signals");
		result.line("  server restart [--delay=<秒>] | server stop");
		return result;
	}

	// ------------------------------------------------------------------ 参数解析助手

	static long longOption(java.util.Map<String, String> options, String key, long fallback) {
		final String raw = options.get(key);
		if (raw == null || raw.isEmpty()) {
			return fallback;
		}
		try {
			return Long.parseLong(raw.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	static int intOption(java.util.Map<String, String> options, String key, int fallback) {
		return (int) longOption(options, key, fallback);
	}

	/** 解析 {@code --node=x,y,z}。 */
	static Position positionOption(java.util.Map<String, String> options, String key) {
		final String raw = options.get(key);
		if (raw == null) {
			return null;
		}
		final String[] parts = raw.split(",");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new Position(Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()), Long.parseLong(parts[2].trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 按 id 或名字找车辆段。
	 *
	 * <p>id 是 64 位十进制（存档里的 {@code depotId}），名字是 MTR 里显示的名称；
	 * 两者都接受是因为用户在游戏里只看得见名字，而脚本里习惯用 id。</p>
	 */
	static @org.jspecify.annotations.Nullable Depot findDepot(Simulator simulator, String idOrName) {
		if (idOrName == null || idOrName.isEmpty()) {
			return null;
		}
		try {
			final long id = Long.parseLong(idOrName.trim());
			for (final Depot depot : simulator.depots) {
				if (depot.getId() == id) {
					return depot;
				}
			}
		} catch (NumberFormatException ignored) {
			// 不是数字就按名字找
		}
		for (final Depot depot : simulator.depots) {
			if (depot.getName().equalsIgnoreCase(idOrName.trim())) {
				return depot;
			}
		}
		return null;
	}

	/** 按 id 找股道（全模拟器范围）。 */
	public static @org.jspecify.annotations.Nullable Siding findSiding(Simulator simulator, long sidingId) {
		for (final Siding siding : simulator.sidings) {
			if (siding.getId() == sidingId) {
				return siding;
			}
		}
		return null;
	}

	/** 把 {@code --siding=} 的值解析成股道：先当 id，再当"某车辆段里的第几条/名字"。 */
	static @org.jspecify.annotations.Nullable Siding resolveSiding(Simulator simulator, java.util.Map<String, String> options, Depot depotHint) {
		final String raw = options.get("siding");
		if (raw != null && !raw.isEmpty()) {
			try {
				final Siding byId = findSiding(simulator, Long.parseLong(raw.trim()));
				if (byId != null) {
					return byId;
				}
			} catch (NumberFormatException ignored) {
				// 按名字找
			}
			for (final Siding siding : simulator.sidings) {
				if (siding.getName().equalsIgnoreCase(raw.trim())) {
					return siding;
				}
			}
			return null;
		}
		if (depotHint != null) {
			final int index = intOption(options, "index", 1);
			final List<Siding> sidings = new ArrayList<>(depotHint.savedRails);
			sidings.sort(java.util.Comparator.comparingLong(Siding::getId));
			if (index >= 1 && index <= sidings.size()) {
				return sidings.get(index - 1);
			}
		}
		return null;
	}

	/**
	 * 构造一辆车卡。
	 *
	 * <p>参数照 {@code VehicleCar} 的完整构造器：{@code mmtrPowered} 决定这辆车出不出牵引力
	 * （指令生成的默认给 true —— 管理员手写一条 {@code vehicle spawn} 时想要的是一列能开的车，
	 * 而不是拖不动的死车；要挂无动力车就用 {@code --unpowered}）。</p>
	 */
	static VehicleCar carOf(String vehicleId, double length, boolean powered) {
		return new VehicleCar(vehicleId, length, 5, 0, -length / 3.0, length / 3.0, 0, 0, powered, "");
	}
}


