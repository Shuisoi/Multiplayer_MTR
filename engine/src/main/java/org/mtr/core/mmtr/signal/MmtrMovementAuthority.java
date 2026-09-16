package org.mtr.core.mmtr.signal;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.simulation.Simulator;

/**
 * 行车许可 (movement authority) —— T2：把**信号显示**翻译成"车能走到哪、到那儿该多快"。
 *
 * <h3>为什么要有这个对象</h3>
 * <p>修前灯色在全仓库只有一个消费者是**影响行车**的 —— AWS 告警（{@code Vehicle.java:3033-3037}）；
 * 停车一律由闭塞占用与岔前闸门决定，**没有一条读灯色**。也就是说**红灯是一块显示屏**。
 * 本对象是"信号 = 行车许可"这条设计的落点：四显示 → {@code {目标位置, 目标距离, 目标速度}}，
 * 供 min 链（S1 停车规则 / LZB 曲线）取用。</p>
 *
 * <h3>纯派生，不持有状态</h3>
 * <p>与 {@code MmtrRoute} 同一纪律：每次问就现算，不缓存、不登记、**不动车**。
 * 它读的是信号层**已有的**结论（{@link MmtrSignalAspect#aspectFrom}），所以"下一架管我的信号"
 * 用的是与灯显同源的 v2 选腿规则，**不另写一套** —— 两份选腿算法正是 notes/114 那类
 * "三层各持一份视图"的来源。</p>
 *
 * <h3>四显示 → 许可（T2 口径，含一条重要的取舍）</h3>
 * <table>
 *   <tr><td>{@code GREEN}</td><td>无约束（轨限速与闭塞说了算）</td></tr>
 *   <tr><td>{@code RED}</td><td>**目标速度 0**：停在这架信号前。这是唯一的**停车义务**来源</td></tr>
 *   <tr><td>{@code SINGLE_YELLOW}</td><td>无停车义务，只有**注意**（AWS 已在做）</td></tr>
 *   <tr><td>{@code DOUBLE_YELLOW}</td><td>同上（预告危险在再下一架）</td></tr>
 * </table>
 *
 * <p><b>为什么黄灯不给停车目标</b>（这是本片唯一的取舍，写清楚免得日后被当成漏做）：
 * 四显示是一条**链**——单黄的意思正是"下一架是红"。列车往前开一段，它读到的**下一个信号就是红**，
 * 停车义务在那一刻自然产生。所以黄灯不需要"停在下一架信号前"这个**跨区间的目标距离**：
 * 那个距离要按行进方向算方向性区间的长度，而按方向取弧号是这一段最容易写错的东西；
 * 更关键的是**它是多余的** —— 链会自己给出红灯。</p>
 *
 * <p>黄灯真正承担的是**预告**（AWS 的响与确认，notes/103 已实机验收）。若日后 LZB 那条连续曲线
 * 要用黄灯提前减速，需要的是"到下一架信号的距离"，那时再按需接
 * {@link MmtrSectionService#sectionEndAheadM}，并且是**加法**（本段剩余 + 下一段长度），
 * 不是替换语义。</p>
 *
 * <p>本片**只算不停**：出口是 feed 与诊断，没有任何停车规则读它（那是 T3）。所以这一片最强的验收
 * 是"**全量零期望值改动**"：算得再准，也不许改变任何既有行为。</p>
 */
public final class MmtrMovementAuthority {

	/** 没有目标（绿灯、黄灯只有注意义务，或这段没有可读的信号）。 */
	public static final double NO_TARGET_M = Double.POSITIVE_INFINITY;

	public final MmtrSignalAspect.Aspect aspect;
	/** 目标所在的轨（诊断/镜像用）；无目标时为空串。 */
	public final String targetRailHex;
	/** 目标距离（米，本车当前位置起算）；无目标时 {@link #NO_TARGET_M}。 */
	public final double targetDistanceM;
	/** 目标速度（km/h）；0 = 必须停在目标处。无目标时 {@link Double#MAX_VALUE}。 */
	public final double targetSpeedKmh;
	/** 只有注意义务（黄灯）：需要司机确认，但**不**构成停车许可约束。 */
	public final boolean cautionOnly;
	public final String reason;

	private MmtrMovementAuthority(MmtrSignalAspect.Aspect aspect, String targetRailHex, double targetDistanceM, double targetSpeedKmh,
			boolean cautionOnly, String reason) {
		this.aspect = aspect;
		this.targetRailHex = targetRailHex == null ? "" : targetRailHex;
		this.targetDistanceM = targetDistanceM;
		this.targetSpeedKmh = targetSpeedKmh;
		this.cautionOnly = cautionOnly;
		this.reason = reason == null ? "" : reason;
	}

	/** 有明确的停车/限速目标。 */
	public boolean hasTarget() {
		return targetDistanceM < NO_TARGET_M;
	}

	/** **停车义务**：必须在目标处停车（只有红灯会给）。 */
	public boolean mustStop() {
		return hasTarget() && targetSpeedKmh <= 0;
	}

	/**
	 * 某列车此刻的行车许可。
	 *
	 * @param vehicleId 要排除自身足迹的车（列车不能把自己车身的影子读成"前方占用"，
	 *                  notes/112 §4 实测过这个坑）
	 */
	public static MmtrMovementAuthority forVehicle(Simulator simulator, @Nullable MmtrMotionPosition walker, long vehicleId) {
		return forVehicle(simulator, walker, vehicleId, null);
	}

	/**
	 * As above, with the train's own 进路: while that route is **not set** the departure signal in front of
	 * the train is red, whatever the lamps over the next rail happen to show.
	 *
	 * <p>这是 T3 的 ②：进路没设好（含被 T1 的物理道岔挡）⇒ 车停在**进路入口的保护信号前**，
	 * 而不是先开进咽喉再在道岔前等。修前这种车会一路开到岔前 —— 堵住咽喉、还看不出为什么。</p>
	 */
	public static MmtrMovementAuthority forVehicle(Simulator simulator, @Nullable MmtrMotionPosition walker, long vehicleId,
			@Nullable MmtrRoute route) {
		if (walker == null) {
			return none(MmtrSignalAspect.Aspect.GREEN, "车没有走行位置");
		}
		final Rail nextRail = walker.peekNextRail();
		final String railHex = nextRail == null ? walker.railHex() : nextRail.getHexId();
		final Position entryNode = nextRail == null ? walker.enteredFromPosition() : walker.aheadNode();
		// 到"即将通过的那架信号"的距离：与 AWS 触发读的是同一个量，两处不会各说各话。
		final double toSignalM = Math.max(0, MmtrRunPlanner.remainingToAheadNodeM(walker));
		if (route != null && !route.isEstablished()) {
			return new MmtrMovementAuthority(MmtrSignalAspect.Aspect.RED, railHex == null ? "" : railHex, toSignalM, 0, false,
				"进路未设好，停在出发信号前：" + route.getStateReason());
		}
		return forApproach(simulator, railHex, entryNode, vehicleId, toSignalM);
	}

	/**
	 * 给定"即将进入的轨 + 从哪个节点进 + 到它的距离"，算出许可。
	 *
	 * <p>{@link #forVehicle} 只是它在车上的**适配器**（从走行位置取这三个量）；
	 * 真正读信号的那一行只有一处 —— {@link MmtrSignalAspect#aspectFrom}，也就是灯显自己用的那个结论。
	 * 所以"下一架管我的信号"用的是 **v2 同源选腿规则**，不存在第二套算法
	 * （notes/114 的"三层各持一份视图"就是这么来的）。</p>
	 */
	public static MmtrMovementAuthority forApproach(Simulator simulator, @Nullable String signalRailHex, @Nullable Position entryNode,
			long vehicleId, double toSignalM) {
		if (signalRailHex == null || signalRailHex.isEmpty()) {
			return none(MmtrSignalAspect.Aspect.GREEN, "轨位置解不出来");
		}
		final MmtrSignalAspect.Aspect aspect = simulator.mmtrSignalAspectView().aspectFrom(signalRailHex, entryNode, vehicleId);
		return of(aspect, signalRailHex, toSignalM);
	}

	/**
	 * 四显示 → 许可的**映射本身**（纯函数，不碰世界）。
	 * 测试与诊断直接用它；世界的那一半（"下一架管我的信号是哪一架"）在上面的 {@link #forApproach}。
	 */
	public static MmtrMovementAuthority of(MmtrSignalAspect.Aspect aspect, @Nullable String targetRailHex, double toSignalM) {
		switch (aspect) {
			case RED:
				return new MmtrMovementAuthority(aspect, targetRailHex, Math.max(0, toSignalM), 0, false,
					"红灯：停在这架信号前（" + Math.round(Math.max(0, toSignalM)) + " m）");
			case SINGLE_YELLOW:
				return new MmtrMovementAuthority(aspect, "", NO_TARGET_M, Double.MAX_VALUE, true,
					"单黄：注意，需确认（下一架按链是危险，走到那里会读到红灯）");
			case DOUBLE_YELLOW:
				return new MmtrMovementAuthority(aspect, "", NO_TARGET_M, Double.MAX_VALUE, true,
					"双黄：预告，需确认（危险在再下一架）");
			default:
				return none(aspect, "绿灯：无约束");
		}
	}

	private static MmtrMovementAuthority none(MmtrSignalAspect.Aspect aspect, String reason) {
		return new MmtrMovementAuthority(aspect, "", NO_TARGET_M, Double.MAX_VALUE, false, reason);
	}

	@Override
	public String toString() {
		if (hasTarget()) {
			return aspect + " -> " + Math.round(targetDistanceM) + " m @" + (mustStop() ? 0 : Math.round(targetSpeedKmh)) + " km/h（" + reason + "）";
		}
		return aspect + (cautionOnly ? " 只注意" : " 无目标") + "（" + reason + "）";
	}
}
