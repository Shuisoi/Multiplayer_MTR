package org.mtr.core.mmtr.command;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.core.mmtr.crowd.MmtrCrowd;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code platform …}：**站台客量**（0–100%）的设置与核对。
 *
 * <h3>为什么单开一个名词</h3>
 * <p>它既不是"站台的存在"（那是 {@code rail --platform} 铺出来的、由引擎自动建的
 * {@link Platform}），也不是"车怎么跑"（{@code vehicle}/{@code train}），而是
 * **一个站台上有多少人**：落盘在站台的 {@code crowdLevel} 字段里，由游戏侧换成
 * 站台上看得见的"村民"方块（见 {@code MmtrCrowdModule}）。</p>
 *
 * <h3>口径：1 格一人 = 100%</h3>
 * <p>用户口径（2026-10）："符合现实的情况是站台边挤，1 格一人"。所以百分比直接就是
 * **站台边缘那一排方块的占用率**：100% 时沿站台每格站一个人（人挤在靠轨道的一线），
 * 60% 就是那一排里六成的格子有人（挑哪几格由站台 id 的哈希决定，看上去是自然分布的
 * 空档，而不是均匀间隔）。所以"客量 60% 的 220 格站台"= 约 132 人 —— 这条换算是
 * {@link #list} 里直接打出来的，方便设完立刻核对。</p>
 *
 * <h3>为什么先说"改了要等多久"</h3>
 * <p>铺方块的是游戏侧（只有它碰得到世界），它每个节拍重算一次；设客量会把
 * {@link MmtrCrowd#revision()} +1，游戏侧看到新版本号就**立刻**重铺，不必等下一拍。
 * 若站台所在的区块没加载，人会在区块加载后的那一拍才出现 —— 这不是失败。</p>
 */
final class MmtrPlatformCommands {

	private MmtrPlatformCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		switch (verb) {
			case "list":
				return list(simulator, options);
			case "set":
				return set(simulator, positional);
			case "station":
				return setStation(simulator, positional);
			case "all":
				return setAll(simulator, positional);
			case "refresh":
				return refresh(simulator);
			case "scan":
				return scan(simulator);
			case "cap":
				return setGate(positional, true);
			case "radius":
				return setGate(positional, false);
			default:
				return MmtrCommandDispatcher.usage("platform 支持 list / set / station / all / refresh / scan / cap / radius");
		}
	}

	/**
	 * {@code platform crowd list [--station=<车站id|名>]}：逐站台报出客量与"按 1 格一人折合多少人"。
	 *
	 * <p>站台名、站名、两端点、轨 hex 都打出来 —— 这份输出要能直接拿去核对存档里的
	 * {@code platforms/<末两位hex>/<hex>} 与网页 {@code /mtr/api/map/mmtr-platforms}。</p>
	 */
	private static MmtrCommandDispatcher.Result list(Simulator simulator, Map<String, String> options) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "platform", "list");
		final String stationFilter = options.get("station");
		final Station filterStation = stationFilter == null ? null : findStation(simulator, stationFilter);
		if (stationFilter != null && filterStation == null) {
			return MmtrCommandDispatcher.usage("找不到车站「" + stationFilter + "」（可用车站 id / stations 文件名的 16 位 hex / 站名）");
		}

		int count = 0;
		long totalPeople = 0;
		for (final Platform platform : simulator.platforms) {
			if (filterStation != null && platform.area != filterStation) {
				continue;
			}
			final long length = approximateLengthBlocks(platform);
			final long people = Math.round(platform.getEffectiveCrowdLevel() * length / 100.0);
			final long capped = Math.min(people, MmtrCrowd.maxPerPlatform());
			final String stationName = platform.area == null ? "（未归站）" : Utilities.formatName(platform.area.getName());
			result.affected.add(platform.getHexId());
			result.line(String.format(Locale.ENGLISH, "%s  站名=%s  站台=%s  客量=%d%%  有效=%d%%  长=%d格  ≈%d人%s%s",
				platform.getHexId(),
				stationName,
				platform.getName(),
				platform.getCrowdLevel(),
				platform.getEffectiveCrowdLevel(),
				length,
				people,
				capped < people ? "（受单站台上限截断为 " + capped + " 人）" : "",
				platform.getCrowdLevel() == platform.getEffectiveCrowdLevel() ? "" : "（有调制器在起作用）"
			));
			count++;
			totalPeople += people;
		}

		result.line("共 " + count + " 个站台，按当前有效客量折合约 " + totalPeople + " 人（口径：1 格一人 = 100%；"
			+ "但**单站台上限 " + MmtrCrowd.maxPerPlatform() + " 人**、**玩家半径 " + MmtrCrowd.playerRadius() + " 格**会把实际人数截下来）");
		result.line("改：platform set <站台hex> <0-100> ｜ platform station <车站id|hex|名> <0-100> ｜ platform all <0-100> ｜ platform refresh");
		result.line("闸门：platform cap <n|show>（单站台人数上限） ｜ platform radius <n|show>（玩家半径，格） ｜ 实有读数：platform scan");
		return result;
	}

	/** {@code platform crowd set <站台hex|站台id> <0-100>}：只改一个站台。 */
	private static MmtrCommandDispatcher.Result set(Simulator simulator, List<String> positional) {
		if (positional.size() < 2) {
			return MmtrCommandDispatcher.usage("platform set 需要 <站台hex|站台id> <0-100>");
		}
		final Platform platform = findPlatform(simulator, positional.get(0));
		if (platform == null) {
			return MmtrCommandDispatcher.usage("找不到站台「" + positional.get(0) + "」（用 platform list 取 hex）");
		}
		final long level = parseLevel(positional.get(1));
		if (level < 0) {
			return MmtrCommandDispatcher.usage("客量必须是 0–100 的整数：" + positional.get(1));
		}
		return applyLevel(simulator, "set", java.util.Collections.singletonList(platform), level);
	}

	/** {@code platform crowd station <车站id|hex|名> <0-100>}：该站**所有**站台一起设（批量最常用）。 */
	private static MmtrCommandDispatcher.Result setStation(Simulator simulator, List<String> positional) {
		if (positional.size() < 2) {
			return MmtrCommandDispatcher.usage("platform station 需要 <车站id|16位hex|站名> <0-100>");
		}
		final Station station = findStation(simulator, positional.get(0));
		if (station == null) {
			return MmtrCommandDispatcher.usage("找不到车站「" + positional.get(0) + "」（可用车站 id / stations 文件名的 16 位 hex / 站名）");
		}
		final long level = parseLevel(positional.get(1));
		if (level < 0) {
			return MmtrCommandDispatcher.usage("客量必须是 0–100 的整数：" + positional.get(1));
		}
		final java.util.List<Platform> platforms = new java.util.ArrayList<>(station.savedRails);
		if (platforms.isEmpty()) {
			return MmtrCommandDispatcher.usage("车站「" + Utilities.formatName(station.getName()) + "」名下没有站台"
				+ "（站台是靠站台轨挂到车站矩形里的，检查一下站台轨是否落在这个车站区域内）");
		}
		return applyLevel(simulator, "station", platforms, level);
	}

	/** {@code platform crowd all <0-100>}：全图所有站台。 */
	private static MmtrCommandDispatcher.Result setAll(Simulator simulator, List<String> positional) {
		if (positional.isEmpty()) {
			return MmtrCommandDispatcher.usage("platform all 需要 <0-100>");
		}
		final long level = parseLevel(positional.get(0));
		if (level < 0) {
			return MmtrCommandDispatcher.usage("客量必须是 0–100 的整数：" + positional.get(0));
		}
		return applyLevel(simulator, "all", new java.util.ArrayList<>(simulator.platforms), level);
	}

	/**
	 * {@code platform crowd refresh}：**立刻**重铺一遍（不改客量）。
	 *
	 * <p>用在两种场合：站台上的人被玩家敲掉了、或者站台方块被改动过，
	 * 不想等下一个节拍（游戏侧默认间隔）才自愈。</p>
	 */
	private static MmtrCommandDispatcher.Result refresh(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "platform", "refresh");
		final long revision = MmtrCrowd.bumpRevision();
		result.line("已请求重铺站台客流（版本号 → " + revision + "）；游戏侧会在下一个节拍按各站台有效客量重算"
			+ "—— 只对已加载区块生效，未加载的等它加载后那一拍");
		result.line("当前站台数 " + simulator.platforms.size() + "；核对用 platform list");
		return result;
	}

	/**
	 * {@code platform crowd scan}：**让游戏端把世界里真实存在的客流方块数一遍**（不改任何东西）。
	 *
	 * <p>为什么必须转交游戏端：只有它碰得到世界。这一条是"设置到底生效了没有"的决定性读数 ——
	 * {@code platform list} 报的是**应该**有几个人（按客量折算），这条报的是**实际**铺出去了几个。
	 * 两者不一致时（区块没加载 / 站台面没找到 / 人被打掉）就一眼看得出来。</p>
	 */
	private static MmtrCommandDispatcher.Result scan(Simulator simulator) {
		simulator.mmtrPushCommand("crowd scan");
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "platform", "scan");
		result.line("已把 crowd scan 交给游戏端执行（只有它能数已加载区块里的客流方块）。");
		result.line("结果会写进指令日志：逐站台报 客量 / 应有 / 实有 / 缺口，未加载区块单独计数。");
		result.line("对照读数：platform list（应有）｜ platform scan（实有）");
		return result;
	}

	/**
	 * {@code platform crowd cap <n|show>} / {@code platform crowd radius <n|show>}：
	 * **人数闸门**（单站台上限 / 玩家半径）。
	 *
	 * <p>为什么做成引擎侧的可调值而不是写死在游戏侧：这两条是"客户端帧率"的旋钮，
	 * 操作员要在中控指令栏里当场调、而且调完要能立刻核对（{@code platform list} 会打出来）。
	 * 引擎是唯一的权威值，游戏侧每拍读一次；游戏侧再把当前值镜像进存档目录的
	 * {@code mmtr-crowd.json}，于是重启后仍然是你调过的数。</p>
	 */
	private static MmtrCommandDispatcher.Result setGate(List<String> positional, boolean isCap) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "platform", isCap ? "cap" : "radius");
		final String label = isCap ? "单站台上限" : "玩家半径";
		if (!positional.isEmpty() && !positional.get(0).equalsIgnoreCase("show")) {
			final long value;
			try {
				value = Long.parseLong(positional.get(0).trim());
			} catch (NumberFormatException e) {
				return MmtrCommandDispatcher.usage(label + "必须是整数：" + positional.get(0));
			}
			if (isCap) {
				MmtrCrowd.setMaxPerPlatform((int) value);
			} else {
				MmtrCrowd.setPlayerRadius((int) value);
			}
			result.line(label + "已设为 " + (isCap ? MmtrCrowd.maxPerPlatform() : MmtrCrowd.playerRadius())
				+ "（输入 " + value + "，超范围会被夹到 "
				+ (isCap ? "1–" + MmtrCrowd.MAX_PER_PLATFORM_LIMIT : "16–" + MmtrCrowd.PLAYER_RADIUS_LIMIT) + "）");
		}
		result.line(label + " = " + (isCap ? MmtrCrowd.maxPerPlatform() : MmtrCrowd.playerRadius())
			+ (isCap ? "（一个站台最多同时站这么多人）" : " 格（只在这个半径内有玩家的站台上站人）"));
		result.line("另一条闸门：" + (isCap ? "玩家半径 = " + MmtrCrowd.playerRadius() + " 格" : "单站台上限 = " + MmtrCrowd.maxPerPlatform() + " 人"));
		result.line("改完会在下一个核对节拍生效（约 5 秒）；核对用 platform scan 的 应有/实有（" + label + "会体现在读数里）");
		return result;
	}

	private static MmtrCommandDispatcher.Result applyLevel(Simulator simulator, String verb, List<Platform> platforms, long level) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "platform", verb);
		long before = 0;
		long after = 0;
		for (final Platform platform : platforms) {
			before += Math.round(platform.getEffectiveCrowdLevel() * approximateLengthBlocks(platform) / 100.0);
			platform.setCrowdLevel(level);
			after += Math.round(platform.getEffectiveCrowdLevel() * approximateLengthBlocks(platform) / 100.0);
			result.affected.add(platform.getHexId());
		}
		result.line("客量已设为 " + level + "%（" + platforms.size() + " 个站台）");
		result.line("按 1 格一人折算：约 " + before + " 人 → 约 " + after + " 人；游戏侧会在下一个节拍重铺");
		for (final Platform platform : platforms) {
			result.line("  " + platform.getHexId()
				+ "  站名=" + (platform.area == null ? "（未归站）" : Utilities.formatName(platform.area.getName()))
				+ "  站台=" + platform.getName()
				+ "  长=" + approximateLengthBlocks(platform) + "格");
		}
		result.line("核对：platform list ｜ 网页 /mtr/api/map/mmtr-platforms（读 effectiveCrowdLevel）");
		return result;
	}

	// ------------------------------------------------------------------ 解析助手

	/**
	 * 站台长度的**格数**（两端点水平距离）。
	 *
	 * <p>为什么不用 {@code railMath.getLength()}：曲线站台的真实弧长略大于弦长，
	 * 但铺方块是**沿站台边缘逐格**做的，格数才是"能站几个人的分母"。
	 * 与游戏侧 {@code MmtrCrowdModule} 的取点步长同一口径，两边的"预计人数"必须一致。</p>
	 */
	private static long approximateLengthBlocks(Platform platform) {
		final Position[] ends = platform.mmtrOrderedPositions();
		return Math.round(Math.hypot(ends[1].getX() - ends[0].getX(), ends[1].getZ() - ends[0].getZ()));
	}

	/** 客量文本 → 0–100；不是 0–100 的整数时返回 -1（调用方报用法）。 */
	private static long parseLevel(String raw) {
		if (raw == null) {
			return -1;
		}
		try {
			final long level = Long.parseLong(raw.trim().replace("%", ""));
			return level < MmtrCrowd.MIN_LEVEL || level > MmtrCrowd.MAX_LEVEL ? -1 : level;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** 站台：先当十进制 id，再当 16 位 hex（存档文件名那一串）。 */
	static @Nullable Platform findPlatform(Simulator simulator, String idOrHex) {
		if (idOrHex == null || idOrHex.isEmpty()) {
			return null;
		}
		final String token = idOrHex.trim();
		final Long id = parseIdOrHex(token);
		if (id != null) {
			final Platform byId = simulator.platformIdMap.get(id.longValue());
			if (byId != null) {
				return byId;
			}
		}
		return null;
	}

	/** 车站：十进制 id → 16 位 hex → 站名。 */
	static @Nullable Station findStation(Simulator simulator, String idOrHexOrName) {
		if (idOrHexOrName == null || idOrHexOrName.isEmpty()) {
			return null;
		}
		final String token = idOrHexOrName.trim();
		final Long id = parseIdOrHex(token);
		if (id != null) {
			final Station byId = simulator.stationIdMap.get(id.longValue());
			if (byId != null) {
				return byId;
			}
		}
		for (final Station station : simulator.stations) {
			if (station.getHexId().equalsIgnoreCase(token) || Utilities.formatName(station.getName()).equalsIgnoreCase(token)) {
				return station;
			}
		}
		return null;
	}

	/**
	 * 一个 token 可能是十进制 id（{@code 3933900461054836227}）或 16 位 hex
	 * （{@code 369805A06AEB3E03}，存档文件名；高位置位时十进制是负数，所以必须按无符号解）。
	 */
	private static @Nullable Long parseIdOrHex(String token) {
		try {
			return Long.parseLong(token);
		} catch (NumberFormatException ignored) {
			// 继续按 hex 试
		}
		if (token.length() == 16) {
			try {
				return Long.parseUnsignedLong(token, 16);
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}
}
