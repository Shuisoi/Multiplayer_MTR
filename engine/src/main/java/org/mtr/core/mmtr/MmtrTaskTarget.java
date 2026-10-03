package org.mtr.core.mmtr;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;

/**
 * **任务目标**（用户口径 2026-09-21：「将任务目标分离…其中 3站1台 则为变量，任务为一对象」）。
 *
 * <h2>为什么目标必须是独立的一份数据</h2>
 *
 * <p>"停站乘降"与"开往"是**同一套动作**用在不同的地方：停在哪儿、开往哪儿是**参数**。
 * 修前这些信息散在三处：{@code Mission.targetSidingId}（一个裸 id）、{@code Mission.targetRailHex}、
 * 以及作业单步骤自己那句 {@code note}。于是子任务只能说"到站停稳"这种不指名道姓的话，
 * 而"停在 3 站 1 台"与"停在车厂 987654 股道 1"在司机屏幕上看起来一模一样 ——
 * 用户要的正是把这个变量抽出来，让**同一条模板 + 不同目标**生成不同的人话。</p>
 *
 * <h2>名字从哪来</h2>
 *
 * <p>不抄作业单的 {@code note}（那是作者写的、可能过时），而是**从引擎对象现算**：
 * 站台 = 它的车站名 + "站" + 站台名 + "台"（现场数据里 {@code stationName=2, platformName=1}
 * ⇒ {@code "2站1台"}）；股道 = 车辆段名 + "股道" + 股道名（{@code depotName=987654, sidingName=1}
 * ⇒ {@code "987654股道1"}，与用户举的例子逐字一致）。这样"目标改名了、提示跟着改"是免费的。</p>
 */
public final class MmtrTaskTarget {

	public enum Kind { PLATFORM, SIDING, STATION, RAIL, NONE }

	private final Kind kind;
	private final long id;
	private final String railHex;
	private final double railFraction;
	private final String label;
	/**
	 * **车站名原样**（{@code "2"} —— 站台标签是"2站1台"的那种写法）。
	 *
	 * <p>水牌（PID，{@link MmtrPid}）要的是"开往**哪个站**"，不是"停在哪个站台"：
	 * 显示"开往 2站1台"是错的，显示"开往 2"才对。于是这里把车站名单独留一份，
	 * 而不是从 {@link #label()} 里反解字符串 —— 命名规则只有这一处（见类注释）。</p>
	 */
	private final String stationName;

	private MmtrTaskTarget(Kind kind, long id, String railHex, double railFraction, String label, String stationName) {
		this.kind = kind;
		this.id = id;
		this.railHex = railHex == null ? "" : railHex;
		this.railFraction = railFraction;
		this.label = label;
		this.stationName = stationName == null ? "" : stationName;
	}

	/** 没有目标（原地动作那类：换端、按按钮…）。 */
	public static MmtrTaskTarget none() {
		return new MmtrTaskTarget(Kind.NONE, 0, "", -1, "（原地）", "");
	}

	/**
	 * **按目标 id / 轨 hex 解析成目标对象**（目标只有一处真源：作业单步骤的 {@code targetId} 与
	 * 折返那类步骤的 {@code targetRailHex}）。解析顺序 = 站台 → 股道 → 车站 → 轨 hex，
	 * 一个都对不上就是 {@link Kind#NONE}（调用方据此退回老口径）。
	 */
	public static MmtrTaskTarget resolve(Simulator simulator, long targetId, @Nullable String railHex, double railFraction) {
		if (targetId != 0) {
			final Platform platform = simulator.platformIdMap.get(targetId);
			if (platform != null) {
				final Station station = platform.area;
				final String stationName = station == null || station.getName().isEmpty() ? "" : station.getName();
				final String platformName = platform.getName().isEmpty() ? "?" : platform.getName();
				return new MmtrTaskTarget(Kind.PLATFORM, targetId, "", -1,
					(stationName.isEmpty() ? "站台" : stationName + "站") + platformName + "台", stationName);
			}
			final Siding siding = simulator.sidingIdMap.get(targetId);
			if (siding != null) {
				final String depotName = siding.getDepotName();
				return new MmtrTaskTarget(Kind.SIDING, targetId, "", -1,
					(depotName == null || depotName.isEmpty() ? "车厂" : depotName) + "股道" + siding.getName(), "");
			}
			final Station station = simulator.stationIdMap.get(targetId);
			if (station != null) {
				return new MmtrTaskTarget(Kind.STATION, targetId, "", -1, station.getName() + "站", station.getName());
			}
		}
		if (railHex != null && !railHex.isEmpty()) {
			final String shortHex = railHex.length() <= 8 ? railHex : railHex.substring(0, 8) + "…";
			return new MmtrTaskTarget(Kind.RAIL, 0, railHex, railFraction,
				"轨 " + shortHex + (railFraction >= 1 ? " 尽头" : railFraction <= 0 ? " 起点"
					: " 的 " + Math.round(railFraction * 100) + "%"), "");
		}
		return none();
	}

	public Kind kind() {
		return kind;
	}

	public long id() {
		return id;
	}

	public String railHex() {
		return railHex;
	}

	public double railFraction() {
		return railFraction;
	}

	/** **人话**："2站1台" / "987654股道1" / "轨 FFFF…-FF 尽头" / "（原地）"。 */
	public String label() {
		return label;
	}

	/**
	 * **车站名原样**（{@code "2"}）；站台/车站以外的目标返回空串。
	 *
	 * <p>水牌要的是"开往哪个站"（{@link MmtrPid}），所以它读这一份而不是 {@link #label()}。
	 * 站台名（"1台"）在这一层就被丢掉了 —— 车站名是这一层唯一知道的东西。</p>
	 */
	public String stationName() {
		return stationName;
	}

	/** 有没有可指名的目标（原地动作没有）。 */
	public boolean isNamed() {
		return kind != Kind.NONE;
	}

	/** 线上/日志用的一行：{@code PLATFORM:2站1台}。 */
	public String encode() {
		return kind.name() + ":" + label;
	}

	@Override
	public String toString() {
		return encode();
	}
}
