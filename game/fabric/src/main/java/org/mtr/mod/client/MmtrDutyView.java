package org.mtr.mod.client;

import org.mtr.mod.data.VehicleExtension;

/**
 * **值守状态的客户端词表与可用性表**（notes/408 §2.1 / §2.2 的客户端那一半）。
 *
 * <h2>为什么要有这么一个类</h2>
 * <p>HUD、综合运转面板（PDA）、以后可能的车载屏都要说同一句话，而"这句话怎么写、
 * 这个按钮能不能点"必须是**一份**：以前把同一件事写在两处，结果两边当着司机的面互相矛盾
 * （notes/215 的教训）。所以状态词与那张可用性表都只在这里定义一次。</p>
 *
 * <h2>它不判业务，它只排版</h2>
 * <p>这里读的是**引擎推过来的镜像**（{@code mmtrDutyState} / {@code mmtrDutyCrew}），
 * 不是客户端自己算出来的结论。引擎仍然是唯一的权威：这里显示"可点"而引擎拒绝时，
 * 拒绝原因会写进引擎指令日志（{@code [MMTR-DUTY]}），面板照原样把拒绝原因显示出来 ——
 * 客户端**不许**为了"按钮好看"而放宽条件。</p>
 */
public final class MmtrDutyView {

	private MmtrDutyView() {
	}

	/**
	 * 引擎侧 {@code MmtrDutyRegistry.State} 的镜像读法。
	 *
	 * <p>{@code NONE} 不是引擎的状态，而是"这趟车没人值守"（镜像字段为空串）在本地的表示 ——
	 * 字段为空与"有个叫 IDLE 的人"是两件不同的事，混在一起会让面板把自由车显示成被占用。</p>
	 */
	public enum State {
		NONE("无人"),
		IDLE("空闲"),
		WAITING("等待接站"),
		ABOARD("已上车·未获驾驶权"),
		DRIVING("运转中"),
		DRIVING_EXIT_ARMED("运转中·下一站退出"),
		RELEASED("已退出");

		private final String word;

		State(String word) {
			this.word = word;
		}

		public String word() {
			return word;
		}
	}

	/** 引擎给的状态名 → 本地枚举。认不出来的名字**不当作自由**（宁可显示成占用，也别把别人的车放给玩家）。 */
	public static State parse(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return State.NONE;
		}
		try {
			return State.valueOf(raw.trim());
		} catch (IllegalArgumentException e) {
			return State.DRIVING;
		}
	}

	/** 这趟车的值守状态（读镜像字段）。 */
	public static State of(VehicleExtension vehicle) {
		return parse(vehicle.getMmtrDutyStateFromSync());
	}

	/** 这趟车的值守人 uuid（空串 = 无人）。 */
	public static String crewOf(VehicleExtension vehicle) {
		return vehicle.getMmtrDutyCrewFromSync();
	}

	/**
	 * 值守人是不是我。
	 *
	 * @param myUuid 本地玩家的 uuid 字符串；{@code null} / 空 = 不在游戏里（不该发生），返回 false
	 */
	public static boolean isMine(VehicleExtension vehicle, String myUuid) {
		return isMine(crewOf(vehicle), myUuid);
	}

	/**
	 * 值守人是不是我（**按 uuid 字符串比**）。
	 *
	 * <p>PDA 车次列表的行不再来自本地车辆镜像（那一份按玩家位置同步，站在几百格外就看不到车），
	 * 而是引擎给的 {@code MmtrPdaList.Row} —— 那种行没有 {@link VehicleExtension} 可比，
	 * 只有 {@code dutyCrew} 这个字符串。为了避免"谁的算我的"在两处各写一遍，
	 * 两个入口共用这一条判据。</p>
	 */
	public static boolean isMine(String crewUuid, String myUuid) {
		return myUuid != null && !myUuid.isEmpty() && myUuid.equalsIgnoreCase(crewUuid == null ? "" : crewUuid);
	}

	/**
	 * **引擎有没有把驾驶权交给我**（notes/409 §4.6 第 13 条）。
	 *
	 * <p>这是 brief 里"明确赋予玩家驾驶的权利并使玩家与列车之间绑定"那一步在**客户端**的读法：
	 * 引擎推过来的镜像说"这趟车在 {@code DRIVING} / {@code DRIVING_EXIT_ARMED}，且值守人是我"。
	 * 它是"下载旁路至对账纠正路径 + 开始上行"的**前置条件** ——
	 * 光坐在驾驶室里（{@code MmtrDriveInput.holdsCabOf}）**不算**：派车后车还在动（{@code ABOARD}）
	 * 或归还作业之后，引擎根本没把驾驶权给谁，那时上行每一条都会被引擎丢掉，
	 * 两边各算各的 ⇒ 对账误差长大 ⇒ 权威在本地/服务端之间翻转 ⇒ 下载那一份把速度拉回引擎的 0
	 * （现场表现就是用户报的"经过一个节点速度就归 0"）。</p>
	 *
	 * <p>{@code DRIVING_EXIT_ARMED}（下一站退出）**算**：那一态司机还在开车，只是约好了到站交还。</p>
	 *
	 * @param myUuid 本地玩家 uuid 字符串；空 / {@code null} = 不在游戏里 ⇒ false（宁可不旁路）
	 */
	public static boolean holdsDrivingRight(VehicleExtension vehicle, String myUuid) {
		final State state = of(vehicle);
		return (state == State.DRIVING || state == State.DRIVING_EXIT_ARMED) && isMine(vehicle, myUuid);
	}

	/**
	 * **两条路能不能走**（notes/408 §2.2 那张表）。
	 *
	 * <table>
	 *   <tr><th>这趟车</th><th>直接上车</th><th>站台接站</th></tr>
	 *   <tr><td>无人 / 已退出</td><td>可以</td><td>可以</td></tr>
	 *   <tr><td>有玩家·下一站退出</td><td>不行（到站才有位置）</td><td><b>只有这一条</b></td></tr>
	 *   <tr><td>有玩家·运转中 / 等待接站 / 已上车未获权</td><td>不行</td><td>不行</td></tr>
	 * </table>
	 *
	 * <p>是我自己的值守时两条都不出（面板改成"取消值守"）—— "自己跟自己接站"没有任何意义。</p>
	 */
	public static boolean canBoardDirect(State state, boolean mine) {
		if (mine) {
			return false;
		}
		return state == State.NONE || state == State.IDLE || state == State.RELEASED;
	}

	/** @see #canBoardDirect */
	public static boolean canMeetAtPlatform(State state, boolean mine) {
		if (mine) {
			return false;
		}
		return state == State.NONE || state == State.IDLE || state == State.RELEASED || state == State.DRIVING_EXIT_ARMED;
	}

	/** 这趟车有没有正在值守的人（面板用它决定要不要画那一行"值守："）。 */
	public static boolean isManned(State state) {
		return state == State.WAITING || state == State.ABOARD || state == State.DRIVING || state == State.DRIVING_EXIT_ARMED;
	}
}
