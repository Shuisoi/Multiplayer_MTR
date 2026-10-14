package org.mtr.core.mmtr.command;

import org.mtr.core.data.Platform;
import org.mtr.core.data.Station;
import org.mtr.core.mmtr.crowd.MmtrCrowd;
import org.mtr.core.mmtr.crowd.MmtrCrowdSystem;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code crowd …}：**车站人流系统**（客量的自动动态设置）的指令面。
 *
 * <h3>与 {@code platform crowd} 的分工</h3>
 * <ul>
 *   <li>{@code platform …} 管的是**一个数**：站台客量 0–100%。系统关着时它就是权威；
 *       系统开着时这三条指令改的是**系统的基准**（见 {@link MmtrPlatformCommands} 的注释）；</li>
 *   <li>{@code crowd …} 管的是**这套系统**：开关、时钟、曲线、基准、参数、临时事件，以及读数。</li>
 * </ul>
 *
 * <h3>读数怎么用</h3>
 * <p>{@code crowd status} 是"为什么站台上是这个数"的唯一入口：每一栏都是算式里的一项
 * （曲线 × 基准 × 班次因子 × 事件 + 冲量），最后才是目标/字段/平滑中三个数。三者不一致时，
 * "目标 ≠ 字段" = 还没写到（或系统关着），"字段 ≠ 平滑中" = 刚被别的通道改过。
 * 现场判"系统到底生没生效"就看这三栏，而不是看画面。</p>
 */
final class MmtrCrowdCommands {

	private MmtrCrowdCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "status":
				return status(simulator, options);
			case "why":
				return why(simulator, positional);
			case "on":
			case "off":
			case "toggle":
				return setEnabled(simulator, verb);
			case "clock":
				return setClock(simulator, positional);
			case "profile":
				return setProfile(simulator, positional);
			case "curve":
				return setCurve(simulator, positional);
			case "base":
				return setBase(simulator, positional);
			case "tune":
				return tune(simulator, options);
			case "event":
				return event(simulator, positional, options);
			case "now":
			case "snap":
				return runNow(simulator, verb.equals("snap"));
			default:
				return MmtrCommandDispatcher.usage("crowd 支持 status / why / on / off / toggle / clock / profile / curve / base / tune / event / now / snap");
		}
	}

	/** {@code crowd status [--detail]}：系统状态 + 逐站表（{@code --detail} 再逐站台列一遍）。 */
	private static MmtrCommandDispatcher.Result status(Simulator simulator, Map<String, String> options) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "status");
		final boolean detail = "true".equals(options.get("detail"));
		simulator.mmtrCrowdSystem.report(simulator, detail).forEach(result::line);
		return result;
	}

	/**
	 * {@code crowd why <站台hex|站台id>}：**只报这一条站台**的算式逐项 —— 排"这条站台为什么人多/人少"用。
	 */
	private static MmtrCommandDispatcher.Result why(Simulator simulator, List<String> positional) {
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("crowd why 需要 <站台hex|站台id>（用 platform list 取 hex）");
		}
		final Platform platform = MmtrPlatformCommands.findPlatform(simulator, positional.get(0));
		if (platform == null) {
			return MmtrCommandDispatcher.usage("找不到站台「" + positional.get(0) + "」（用 platform list 取 16 位 hex）");
		}
		final MmtrCrowdSystem system = simulator.mmtrCrowdSystem;
		final MmtrCrowdSystem.Decision decision = system.decide(simulator, platform);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "why");
		result.affected.add(platform.getHexId());
		result.line("站台 " + platform.getHexId() + "（" + platform.getName() + "）"
			+ " 站名=" + (platform.area == null ? "（未归站）" : platform.getStationName()));
		result.line("  时钟小时 = " + decision.hour() + "（" + (system.isWorldClock() ? "世界" : "现实") + "）"
			+ " ⇒ 曲线 = " + decision.curve() + "%");
		result.line("  基准 = " + decision.basePercent() + "%"
			+ "（站台覆盖 " + fmt(system.platformBaseView().get(platform.getId()))
			+ " ｜ 车站覆盖 " + fmt(system.stationBaseView().get(MmtrCrowdSystem.stationIdOf(platform)))
			+ " ｜ 全局默认 " + system.getDefaultBase() + "%）");
		result.line("  班次：本站到站计数 = " + round2(system.rateOf(platform)) + " ｜ 最忙车站 = " + system.maxRate()
			+ " ⇒ 班次因子 = " + round2(decision.serviceFactor()));
		result.line("  停站冲量 = +" + decision.surge() + "%（这一拍有车正停在这条站台上时才有）");
		result.line("  事件 = 加法 " + (decision.eventAdd() > 0 ? "+" : "") + decision.eventAdd()
			+ " ｜ 乘法 ×" + round2(decision.eventMul()));
		result.line("  ⇒ 目标 = " + decision.target() + "% ｜ 站台字段 = " + platform.getCrowdLevel() + "%"
			+ " ｜ 平滑中 = " + round2(system.currentLevelOf(platform.getId(), platform.getCrowdLevel())) + "%");
		result.line("  系统 " + (system.isEnabled() ? "开着（下一拍会把字段写成量化后的目标）" : "关着（只算不写）")
			+ " ｜ 量化 " + system.getStep() + "% ｜ τ=" + Math.round(system.getTauSeconds()) + "s");
		return result;
	}

	/** {@code crowd on|off|toggle}：开 = 系统接管客量；关 = 松开手（已经写下的值留在站台上，不还原）。 */
	private static MmtrCommandDispatcher.Result setEnabled(Simulator simulator, String verb) {
		final MmtrCrowdSystem system = simulator.mmtrCrowdSystem;
		final boolean target = verb.equals("toggle") ? !system.isEnabled() : verb.equals("on");
		system.setEnabled(target);
		system.save();
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", verb);
		result.line("车站人流系统已" + (target ? "打开（开始接管各站台客量）" : "关闭（手填的客量原样保留，系统只算不写）"));
		if (target) {
			// 开的那一下直接落到目标值：不然站台要等 τ 秒才慢慢涨上来，"开了没反应"会被当成没生效
			system.runNow(simulator, true);
			result.line("已立刻按当前曲线算一轮（不等周期、跳过平滑）：核对用 crowd status");
		} else {
			result.line("当前站台客量留在原处（不会还原成系统接管之前的值）；要手填就用 platform set/station/all");
		}
		result.line("读数：crowd status ｜ 单条：crowd why <站台hex> ｜ 网页 /mtr/api/map/mmtr-crowd-system");
		return result;
	}

	/** {@code crowd clock <world|real>}：时钟来源（世界 = 游戏内时刻，real = 服务器墙上时间）。 */
	private static MmtrCommandDispatcher.Result setClock(Simulator simulator, List<String> positional) {
		final MmtrCrowdSystem system = simulator.mmtrCrowdSystem;
		if (!positional.isEmpty()) {
			final String token = positional.get(0).trim().toLowerCase(Locale.ENGLISH);
			if (token.equals("world") || token.equals("游戏") || token.equals("世界")) {
				system.setWorldClock(true);
			} else if (token.equals("real") || token.equals("现实")) {
				system.setWorldClock(false);
			} else {
				return MmtrCommandDispatcher.usage("crowd clock 只认 world（游戏内时刻） / real（现实钟点）：" + positional.get(0));
			}
			system.save();
		}
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "clock");
		result.line("时钟 = " + (system.isWorldClock() ? "world（游戏内时刻，太阳就是时钟；/time set 能当场拨客流）"
			: "real（服务器墙上时间；日夜循环关掉时用这个）"));
		if (system.isWorldClock() && !simulator.isTimeMoving()) {
			result.line("⚠ 现在**游戏内时间没在走**（doDaylightCycle 关着，或还没收到世界时间）—— "
				+ "曲线会停在这一档不动。要么打开日夜循环，要么 crowd clock real");
		}
		result.line("当前小时 = " + system.hourOf(simulator) + "（曲线 " + system.curveAt(system.hourOf(simulator)) + "%）");
		return result;
	}

	/** {@code crowd profile <通勤|全天|夜间|空>}：换一条曲线预设（不带参数 = 列出预设与当前曲线）。 */
	private static MmtrCommandDispatcher.Result setProfile(Simulator simulator, List<String> positional) {
		final MmtrCrowdSystem system = simulator.mmtrCrowdSystem;
		if (!positional.isEmpty() && !positional.get(0).equalsIgnoreCase("list")) {
			final String name = positional.get(0);
			system.applyProfile(name, true);
		}
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "profile");
		result.line("当前曲线档 = " + system.getProfileName() + " ｜ 预设有：" + String.join(" / ", MmtrCrowdSystem.profileNames()));
		result.line("曲线上限（一天里最挤的那一档）= " + maxOf(system.getCurve()) + "%");
		result.line("逐小时：" + curveText(system.getCurve()));
		result.line("改：crowd profile 通勤 ｜ 只改一档 crowd curve <小时> <0-100> ｜ 整条 crowd curve <24 个逗号分隔的数>");
		return result;
	}

	/**
	 * {@code crowd curve …}：看/改曲线。
	 * <ul>
	 *   <li>{@code crowd curve} / {@code crowd curve show} —— 打出来；</li>
	 *   <li>{@code crowd curve <小时> <0-100>} —— 只改一档（0 = 0 点）；</li>
	 *   <li>{@code crowd curve <24 个逗号分隔的数>} —— 整条换掉（档名变"自定义"）。</li>
	 * </ul>
	 */
	private static MmtrCommandDispatcher.Result setCurve(Simulator simulator, List<String> positional) {
		final MmtrCrowdSystem system = simulator.mmtrCrowdSystem;
		if (!positional.isEmpty() && !positional.get(0).equalsIgnoreCase("show")) {
			if (positional.size() >= 2 && positional.get(0).contains(",")) {
				return MmtrCommandDispatcher.usage("crowd curve 的两种写法不能混：要么 <小时> <0-100>，要么一条 24 个数的逗号列表");
			}
			if (positional.get(0).contains(",")) {
				final String[] parts = positional.get(0).split(",");
				if (parts.length != MmtrCrowdSystem.HOURS) {
					return MmtrCommandDispatcher.usage("整条曲线要正好 " + MmtrCrowdSystem.HOURS + " 个数（0–23 点），收到 " + parts.length + " 个");
				}
				final long[] values = new long[MmtrCrowdSystem.HOURS];
				for (int i = 0; i < parts.length; i++) {
					final long value = parsePercent(parts[i]);
					if (value < 0) {
						return MmtrCommandDispatcher.usage("第 " + i + " 档不是 0–100 的整数：" + parts[i]);
					}
					values[i] = value;
				}
				system.setCurve(values);
			} else {
				if (positional.size() < 2) {
					return MmtrCommandDispatcher.usage("crowd curve <小时 0-23> <0-100>：例如 crowd curve 8 95");
				}
				final long hour = parsePercent(positional.get(0));
				final long level = parsePercent(positional.get(1));
				if (hour < 0 || hour > 23) {
					return MmtrCommandDispatcher.usage("小时必须是 0–23：" + positional.get(0));
				}
				if (level < 0) {
					return MmtrCommandDispatcher.usage("曲线值必须是 0–100 的整数：" + positional.get(1));
				}
				system.setCurveHour((int) hour, level);
			}
			system.save();
			if (system.isEnabled()) {
				system.runNow(simulator, false);
			}
		}
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "curve");
		result.line("曲线档 = " + system.getProfileName() + " ｜ 当前小时 " + system.hourOf(simulator)
			+ " ⇒ " + system.curveAt(system.hourOf(simulator)) + "%");
		result.line("逐小时：" + curveText(system.getCurve()));
		return result;
	}

	/** {@code crowd base <车站id|hex|名|站台hex|all> <0-100|default>}：自动系统的**基准客量**。 */
	private static MmtrCommandDispatcher.Result setBase(Simulator simulator, List<String> positional) {
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("crowd base 需要 <车站id|16位hex|站名|站台hex|all> <0-100|default>");
		}
		final MmtrCrowdSystem system = simulator.mmtrCrowdSystem;
		final String token = positional.get(0).trim();
		final String valueToken = positional.size() > 1 ? positional.get(1).trim().toLowerCase(Locale.ENGLISH) : "show";
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "base");

		final boolean isAll = token.equalsIgnoreCase("all") || token.equalsIgnoreCase("全部");
		final Platform platform = isAll ? null : MmtrPlatformCommands.findPlatform(simulator, token);
		final Station station = isAll || platform != null ? null : MmtrPlatformCommands.findStation(simulator, token);
		if (!isAll && platform == null && station == null) {
			return MmtrCommandDispatcher.usage("找不到车站或站台「" + token + "」（车站可用 id / 16 位 hex / 站名，站台用 platform list 的 hex）");
		}

		if (!valueToken.equals("show")) {
			final boolean clear = valueToken.equals("default") || valueToken.equals("clear") || valueToken.equals("默认");
			final long level = clear ? 0 : parsePercent(valueToken);
			if (!clear && level < 0) {
				return MmtrCommandDispatcher.usage("基准必须是 0–100 的整数，或 default（去掉覆盖）：" + positional.get(1));
			}
			if (isAll) {
				system.setDefaultBase(clear ? 100 : level);
			} else if (platform != null) {
				if (clear) {
					system.clearPlatformBase(platform.getId());
				} else {
					system.setPlatformBase(platform.getId(), level);
				}
			} else {
				if (clear) {
					system.clearStationBase(station.getId());
				} else {
					system.setStationBase(station.getId(), level);
				}
			}
			system.save();
			if (system.isEnabled()) {
				system.runNow(simulator, false);
			}
		}

		result.line("全局默认基准 = " + system.getDefaultBase() + "%（crowd base all <n> 改它）");
		if (platform != null) {
			result.affected.add(platform.getHexId());
			result.line("站台 " + platform.getHexId() + " 的基准覆盖 = "
				+ fmt(system.platformBaseView().get(platform.getId())) + "，生效值 = " + system.baseOf(platform) + "%");
		} else if (station != null) {
			result.affected.add(station.getHexId());
			result.line("车站 " + Utilities.formatName(station.getName()) + "（" + station.getHexId() + "）的基准覆盖 = "
				+ fmt(system.stationBaseView().get(station.getId())));
		}
		result.line("口径：基准乘在曲线上 —— 基准 60% ⇒ 曲线最挤的那一档也只有 60% 的人");
		return result;
	}

	/**
	 * {@code crowd tune [--period=秒] [--tau=秒] [--step=%] [--service=0-1] [--surge=%] [--base=0-100]}：
	 * 调系统参数（不带参数 = 全部打一遍，也就是读数）。
	 */
	private static MmtrCommandDispatcher.Result tune(Simulator simulator, Map<String, String> options) {
		final MmtrCrowdSystem system = simulator.mmtrCrowdSystem;
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "tune");
		boolean changed = false;
		if (options.containsKey("period")) {
			final long seconds = MmtrCommandDispatcher.longOption(options, "period", system.getPeriodMillis() / 1000);
			system.setPeriodMillis(seconds * 1000L);
			changed = true;
		}
		if (options.containsKey("tau")) {
			system.setTauSeconds(MmtrCommandDispatcher.longOption(options, "tau", Math.round(system.getTauSeconds())));
			changed = true;
		}
		if (options.containsKey("surge-tau") || options.containsKey("surgetau")) {
			final String key = options.containsKey("surge-tau") ? "surge-tau" : "surgetau";
			system.setSurgeTauSeconds(MmtrCommandDispatcher.longOption(options, key, Math.round(system.getSurgeTauSeconds())));
			changed = true;
		}
		if (options.containsKey("step")) {
			system.setStep(MmtrCommandDispatcher.longOption(options, "step", system.getStep()));
			changed = true;
		}
		if (options.containsKey("service")) {
			system.setServiceBoost(parseDouble(options.get("service"), system.getServiceBoost()));
			changed = true;
		}
		if (options.containsKey("surge")) {
			system.setDwellSurge(MmtrCommandDispatcher.longOption(options, "surge", system.getDwellSurge()));
			changed = true;
		}
		if (options.containsKey("base")) {
			system.setDefaultBase(MmtrCommandDispatcher.longOption(options, "base", system.getDefaultBase()));
			changed = true;
		}
		if (changed) {
			system.save();
			if (system.isEnabled()) {
				system.runNow(simulator, false);
			}
		}
		result.line("周期 = " + (system.getPeriodMillis() / 1000) + "s（系统多久算一次；越短越跟手、越费）"
			+ " ｜ 平滑 τ = " + Math.round(system.getTauSeconds()) + "s（0 = 不平滑，直接跳）"
			+ " ｜ 冲量上冲 τ = " + Math.round(system.getSurgeTauSeconds()) + "s（车进站人聚得快）");
		result.line("量化档 = " + system.getStep() + "%（目标与字段差不到这一档就不写，防抖）"
			+ " ｜ 班次强度 = " + round2(system.getServiceBoost()) + "（0 = 完全不看班次）");
		result.line("停站冲量 = +" + system.getDwellSurge() + "% ｜ 全局默认基准 = " + system.getDefaultBase() + "%");
		result.line("例：crowd tune --period=2 --tau=20（跟手）｜ crowd tune --tau=0（立刻到位）｜ "
			+ "crowd tune --surge-tau=8（进站那一冲更快）｜ crowd tune --service=0（只看曲线）");
		return result;
	}

	/**
	 * {@code crowd event …}：临时事件（布景/演练：把某个站临时拉高或压低一段时间）。
	 * <pre>
	 *   crowd event add +40 10                 全图 +40%，持续 10 分钟
	 *   crowd event add -20 5 --station=下水    那个站 −20%，持续 5 分钟
	 *   crowd event add x2 30 --station=all     全图客流翻倍，持续 30 分钟
	 *   crowd event add +50 forever             不过期（要自己 clear）
	 *   crowd event list | crowd event clear [id|all]
	 * </pre>
	 */
	private static MmtrCommandDispatcher.Result event(Simulator simulator, List<String> positional, Map<String, String> options) {
		final MmtrCrowdSystem system = simulator.mmtrCrowdSystem;
		final String action = positional.isEmpty() ? "list" : positional.get(0).trim().toLowerCase(Locale.ENGLISH);
		switch (action) {
			case "clear": {
				long id = 0;
				if (positional.size() > 1 && !positional.get(1).equalsIgnoreCase("all")) {
					try {
						id = Long.parseLong(positional.get(1).trim());
					} catch (NumberFormatException e) {
						return MmtrCommandDispatcher.usage("crowd event clear 只认事件 id 或 all：" + positional.get(1));
					}
					if (id <= 0) {
						return MmtrCommandDispatcher.usage("事件 id 是正整数，或 all：" + positional.get(1));
					}
				}
				final int removed = system.clearEvents(id);
				system.save();
				if (system.isEnabled()) {
					system.runNow(simulator, false);
				}
				final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "event");
				result.line("清掉 " + removed + " 条事件" + (id == 0 ? "（全部）" : "（id=" + id + "）"));
				return result;
			}
			case "add": {
				if (positional.size() < 3) {
					return MmtrCommandDispatcher.usage("crowd event add <+n|-n|x倍> <分钟|forever> [--station=<车站id|hex|名|all>]");
				}
				final String magnitude = positional.get(1).trim();
				long add = 0;
				double mul = 1.0;
				final String lower = magnitude.toLowerCase(Locale.ENGLISH).replace("×", "x");
				if (lower.startsWith("x") || lower.startsWith("*")) {
					mul = parseDouble(lower.substring(1), 1.0);
					if (mul <= 0) {
						return MmtrCommandDispatcher.usage("乘法事件的倍率要大于 0：" + magnitude);
					}
				} else {
					add = MmtrCommandDispatcher.longOption(java.util.Map.of("v", lower.replace("+", "")), "v", 0);
					if (add == 0) {
						return MmtrCommandDispatcher.usage("加法事件要给非 0 的整数百分比（可带正负号），或 x1.5 这种倍率：" + magnitude);
					}
				}
				final long duration;
				if (positional.get(2).equalsIgnoreCase("forever") || positional.get(2).equalsIgnoreCase("永久")) {
					duration = Long.MAX_VALUE;
				} else {
					final long minutes = MmtrCommandDispatcher.longOption(java.util.Map.of("m", positional.get(2)), "m", -1);
					if (minutes <= 0) {
						return MmtrCommandDispatcher.usage("时长要给正的分钟数，或 forever：" + positional.get(2));
					}
					duration = minutes * 60_000L;
				}
				final String stationToken = options.getOrDefault("station", "all");
				long stationId = 0;
				String stationLabel = "全图";
				if (!stationToken.equalsIgnoreCase("all") && !stationToken.equalsIgnoreCase("全部")) {
					final Station station = MmtrPlatformCommands.findStation(simulator, stationToken);
					final Platform platform = station == null ? MmtrPlatformCommands.findPlatform(simulator, stationToken) : null;
					if (station == null && platform == null) {
						return MmtrCommandDispatcher.usage("找不到车站「" + stationToken + "」（车站可用 id / 16 位 hex / 站名；全图用 --station=all）");
					}
					stationId = station != null ? station.getId() : MmtrCrowdSystem.stationIdOf(platform);
					stationLabel = station != null ? Utilities.formatName(station.getName()) : platform.getStationName();
				}
				final long id = system.addEvent(stationId, stationLabel, add, mul, simulator.getCurrentMillis(), duration);
				system.save();
				if (system.isEnabled()) {
					system.runNow(simulator, false);
				}
				final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "event");
				result.line("已加事件 #" + id + "："
					+ (add != 0 ? (add > 0 ? "+" : "") + add + "%" : "") + (Math.abs(mul - 1.0) > 1e-9 ? (add != 0 ? "，" : "") + "×" + round2(mul) : "")
					+ " ｜ 作用=" + stationLabel
					+ " ｜ 时长=" + (duration == Long.MAX_VALUE ? "不过期（记得 crowd event clear）" : (duration / 60_000) + " 分钟"));
				result.line("核对：crowd status（事件那一栏）｜ crowd event list");
				return result;
			}
			default: {
				final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", "event");
				final List<MmtrCrowdSystem.Event> events = system.eventsView();
				if (events.isEmpty()) {
					result.line("没有在效事件。加一条：crowd event add +40 10 [--station=<站|all>]");
					return result;
				}
				result.line("在效事件 " + events.size() + " 条：");
				final long now = simulator.getCurrentMillis();
				for (final MmtrCrowdSystem.Event e : events) {
					final String remain = e.untilMillis == Long.MAX_VALUE ? "不过期"
						: Math.max(0, Math.round((e.untilMillis - now) / 60_000.0)) + " 分钟后过期";
					result.line("  #" + e.id + "  " + (e.stationLabel.isEmpty() ? "全图" : e.stationLabel)
						+ "  " + (e.add != 0 ? (e.add > 0 ? "+" : "") + e.add + "%" : "") + (Math.abs(e.mul - 1.0) > 1e-9 ? " ×" + round2(e.mul) : "")
						+ "  " + remain);
				}
				return result;
			}
		}
	}

	/** {@code crowd now}（按 τ 平滑）/ {@code crowd snap}（直接到位）。 */
	private static MmtrCommandDispatcher.Result runNow(Simulator simulator, boolean snap) {
		final MmtrCrowdSystem system = simulator.mmtrCrowdSystem;
		if (!system.isEnabled()) {
			return MmtrCommandDispatcher.usage("系统关着，什么都不写。先 crowd on（或者只看数：crowd status）");
		}
		system.runNow(simulator, snap);
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "crowd", snap ? "snap" : "now");
		result.line((snap ? "已跳过平滑直接落到目标" : "已立刻重算一轮（仍按 τ 平滑）")
			+ "：当前小时 " + system.hourOf(simulator) + " ⇒ 曲线 " + system.curveAt(system.hourOf(simulator)) + "%"
			+ "，写入 " + system.getLastWrittenCount() + " 个站台");
		result.line("核对：crowd status ｜ platform list ｜ 网页 /mtr/api/map/mmtr-platforms");
		return result;
	}

	// ------------------------------------------------------------------ 小工具

	private static long parsePercent(String raw) {
		if (raw == null) {
			return -1;
		}
		try {
			final long value = Long.parseLong(raw.trim().replace("%", ""));
			return value < MmtrCrowd.MIN_LEVEL || value > MmtrCrowd.MAX_LEVEL ? -1 : value;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static double parseDouble(String raw, double fallback) {
		try {
			return Double.parseDouble(raw == null ? "" : raw.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String curveText(long[] curve) {
		final StringBuilder builder = new StringBuilder();
		for (int i = 0; i < curve.length; i++) {
			if (i > 0) {
				builder.append(' ');
			}
			builder.append(String.format(Locale.ENGLISH, "%02d:%d", i, curve[i]));
		}
		return builder.toString();
	}

	private static long maxOf(long[] values) {
		long max = 0;
		for (final long value : values) {
			max = Math.max(max, value);
		}
		return max;
	}

	private static String fmt(Long value) {
		return value == null ? "无" : value + "%";
	}

	private static String round2(double value) {
		return String.valueOf(Math.round(value * 100.0) / 100.0);
	}
}
