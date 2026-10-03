package org.mtr.core.mmtr;

import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.job.MmtrConsistJob;
import org.mtr.core.mmtr.job.MmtrJobStep;
import org.mtr.core.simulation.Simulator;

/**
 * **水牌 / PID**（用户 2026-10-01 口径：「接下来是水牌逻辑，也就是 PID…首先需要拿到列车终点站和班次号，
 * 还有下一站，这个要从作业单来读取」）。
 *
 * <h2>为什么必须重写，不能沿用原版</h2>
 *
 * <p>原版的"自动水牌"是**交路/时刻表**算出来的：{@code VehicleExtraData.setRoutePlatformInfo()}
 * 按车当前跑的 {@code Trip}/{@code Route} 填 {@code thisRouteDestination / nextStationName} 等字段，
 * 客户端的车体显示部件（{@code ModelPropertiesPart} 的 {@code DESTINATION / ROUTE_NUMBER /
 * NEXT_STATION}）读那几个字段出字。本仓把自动服务换成了**作业单**（{@code MmtrConsistJob}），
 * 那一步现在是一个空实现（原话："MTR route/timetable info removed with the auto service"）——
 * 于是**自动水牌整条链断在引擎侧**，字段恒为空。</p>
 *
 * <p>这份类就是把那条链接回来：真源只有一个 —— **作业单 + 车跑到第几步**。不还原原版的 route/trip
 * 语义（那套已经不存在了），也不算第二份计划。</p>
 *
 * <h2>三项数据的口径（用户 2026-10-01 选定）</h2>
 * <ul>
 *   <li><b>班次号</b> = 作业单号（{@code jobId}，现场是 {@code 00101…00110}）。不多加字段。</li>
 *   <li><b>本趟终点</b> = **本趟最后一个"停在站台"的目标**的车站名。本趟 = 从当前位置到**下一次换端**
 *       （{@code CHANGE_ENDS}）为止 —— 现场就是这样编的：{@code 00101} 是 14 趟，
 *       {@code 海山1台 ⇄ 叶楼2台} 来回，每趟之间夹一步换端。换端那一步本身已经算**下一趟**
 *       （换完就开回去了，水牌也该翻面）。</li>
 *   <li><b>下一站</b> = **当前位置之后第一个"停在站台"的目标**的车站名，**允许跨趟**
 *       （在终点站开着门时，下一站就是换端之后那一趟的第一站 —— 这正是旅客要看的下一站）。</li>
 * </ul>
 *
 * <p>"停在站台"= 这一步的目标解析出来是 {@link MmtrTaskTarget.Kind#PLATFORM}：{@code SERVE}
 * （开门乘降）与开往站台的 {@code MOVE_TO} 都算 —— 后者让"没有 SERVE 的回送趟"照样报得出终点，
 * 而两条规则共用同一个判据，不存在"终点按 SERVE 算、下一站按 MOVE_TO 算"这种两套口径。</p>
 *
 * <p>名字一律**从引擎对象现算**（{@link MmtrTaskTarget#stationName()}，站台 → 它的车站名），
 * 不抄作业单步骤那句 {@code note}：作者写的 note 可能过时，而车站改名时水牌要跟着改。</p>
 *
 * <h2>只读三项、不碰"当前站"</h2>
 *
 * <p>原版的 {@code NEXT_STATION} 在开门时显示**当前站**，本类不掺这件事：水牌要的是
 * "开往哪儿 / 下一站是哪儿"，"现在停在哪一站"由调用方（客户端 HUD）自己按
 * {@code thisStationName} 那一路决定。少一个字段就少一处两边口径不一样的机会。</p>
 */
public final class MmtrPid {

	/** 不在任何作业单上（车场里停着的车、没有作业单的世界）。 */
	public static final MmtrPid UNKNOWN = new MmtrPid("", "", "", 0);

	private final String serviceNumber;
	private final String terminus;
	private final String nextStation;
	private final int legIndex;

	private MmtrPid(String serviceNumber, String terminus, String nextStation, int legIndex) {
		this.serviceNumber = serviceNumber == null ? "" : serviceNumber;
		this.terminus = terminus == null ? "" : terminus;
		this.nextStation = nextStation == null ? "" : nextStation;
		this.legIndex = legIndex;
	}

	/**
	 * 读一次水牌。
	 *
	 * @param job       车当前挂着的作业单；{@code null} 或没有步骤 ⇒ {@link #UNKNOWN}
	 * @param stepIndex 车跑到第几步（0 起）。越界会被钳进合法范围 —— 调度器换单/循环重置的那一拍
	 *                  可能先把步号推到末尾，这里不该抛异常（tick 抛异常会中断整拍剩下的工作）
	 */
	public static MmtrPid of(Simulator simulator, @Nullable MmtrConsistJob job, int stepIndex) {
		if (simulator == null || job == null || job.steps.isEmpty()) {
			return UNKNOWN;
		}
		final int index = Math.max(0, Math.min(stepIndex, job.steps.size() - 1));
		final int legEnd = nextChangeEnds(job, index);
		return new MmtrPid(job.jobId,
			platformStation(simulator, job, index, legEnd, true),
			platformStation(simulator, job, index + 1, job.steps.size(), false),
			legIndexOf(job, index));
	}

	/** 班次号（作业单号；空 = 不在作业单上）。 */
	public String serviceNumber() {
		return serviceNumber;
	}

	/** 本趟终点站名（空 = 本趟不停站台，例如最后的回库趟）。 */
	public String terminus() {
		return terminus;
	}

	/** 前方下一个停站的车站名（空 = 后面不再有站台作业）。 */
	public String nextStation() {
		return nextStation;
	}

	/** 第几趟（1 基；{@link #UNKNOWN} 是 0）。每遇到一步换端就 +1。 */
	public int legIndex() {
		return legIndex;
	}

	/** 这份水牌有没有内容（不在作业单上 = false）。 */
	public boolean isKnown() {
		return !serviceNumber.isEmpty();
	}

	/** 日志/验收用的一行：{@code 00101 第1趟 终点 海山 下一站 下水}。 */
	public String describe() {
		if (!isKnown()) {
			return "（不在作业单上）";
		}
		return serviceNumber + " 第" + legIndex + "趟 终点 " + (terminus.isEmpty() ? "—" : terminus)
			+ " 下一站 " + (nextStation.isEmpty() ? "—" : nextStation);
	}

	@Override
	public String toString() {
		return describe();
	}

	/** 当前位置之后**第一处**换端；没有则到作业单末尾（那一趟的终点就是整条作业单的最后一个站台）。 */
	private static int nextChangeEnds(MmtrConsistJob job, int index) {
		for (int i = index + 1; i < job.steps.size(); i++) {
			if (job.steps.get(i).type == MmtrJobStep.StepType.CHANGE_ENDS) {
				return i;
			}
		}
		return job.steps.size();
	}

	/**
	 * 取一段步骤里"停在站台"的那个车站名。
	 *
	 * @param last {@code true} = 取这段里**最后**一个（终点）；{@code false} = 取第一个（下一站）
	 */
	private static String platformStation(Simulator simulator, MmtrConsistJob job, int from, int to, boolean last) {
		String found = "";
		for (int i = Math.max(0, from); i < Math.min(to, job.steps.size()); i++) {
			final String stationName = stationOf(simulator, job.steps.get(i));
			if (!stationName.isEmpty()) {
				found = stationName;
				if (!last) {
					return found;
				}
			}
		}
		return found;
	}

	/** 这一步的目标车站名；不是"停在站台"的步骤 ⇒ 空串。 */
	private static String stationOf(Simulator simulator, MmtrJobStep step) {
		if (step.targetId == 0) {
			// 换端/连挂/解挂、以及"折返开到某根正规轨"那类轨目标步骤：没有站台，不参与水牌
			return "";
		}
		final MmtrTaskTarget target = MmtrTaskTarget.resolve(simulator, step.targetId, null, -1);
		return target.kind() == MmtrTaskTarget.Kind.PLATFORM ? target.stationName() : "";
	}

	/** 第几趟（1 基）：当前位置**及之前**走过几处换端。 */
	private static int legIndexOf(MmtrConsistJob job, int index) {
		int legs = 0;
		for (int i = 0; i <= index && i < job.steps.size(); i++) {
			if (job.steps.get(i).type == MmtrJobStep.StepType.CHANGE_ENDS) {
				legs++;
			}
		}
		return legs + 1;
	}
}
