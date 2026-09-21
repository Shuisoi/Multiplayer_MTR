package org.mtr.core.mmtr;

import org.jspecify.annotations.Nullable;

/**
 * **一条基础操作**（用户口径：「拆一个任务到基础的操作以简化逻辑判定」）。
 *
 * <p>由 {@link MmtrTaskTemplate#expand} 用**模板 + 目标**生成：类型是模板里的那一步，
 * 人话（{@code text}）在生成时就把目标名代进去 —— 所以司机看到的是
 * 「停在 3站1台」/「停在 987654股道1」/「开门」/「等待上下客 20s」/「关门」，
 * 而不是笼统的"到站停稳"。文本**出自引擎**，客户端只负责画（notes/170 §8.4）。</p>
 *
 * <h3>为什么判定只写在 {@link Vehicle} 里</h3>
 * <p>这一层只有"这条基础操作是什么、达成没达成"；**怎么算达成**由车辆（有观测能力的那一方）判，
 * 一条基础操作一处判据。于是"到站"这件事在开往与停站乘降两个模板里是**同一句代码**
 * （2026-09-21 的回归就是因为两者各有一套：一个有链、一个没链）。</p>
 */
public final class MmtrSubTask {

	/**
	 * 基础操作的类型。**顺序即清单顺序**，链是顺序的：前一条没达成，后一条不评。
	 */
	public enum Kind {
		/** **停在目标**：编组已在目标（站台轨上有车 / 车头站在目标轨上）且停稳。 */
		STOP_AT_TARGET,
		/** **开门**：门到"开"（司机按键，或自动执行/司机不在操纵台时由引擎代开）。 */
		OPEN_DOORS,
		/** **等待上下客**：从开门起计时 ≥ 停留时长（模板参数）。 */
		WAIT_PASSENGERS,
		/** **关门**：门回到"关"。 */
		CLOSE_DOORS
	}

	public enum State { PENDING, ACTIVE, DONE }

	private final Kind kind;
	private final String text;
	private final long dwellMillis;
	private State state = State.PENDING;
	private long activeSinceMillis;
	private long doneMillis = -1;
	/**
	 * **司机侧确认**的时刻（双向确认的上行那一半）：引擎判定归引擎，司机那边也要明确说"我这边确认了"。
	 * {@code -1} = 客户端还没确认过这一条。
	 */
	private long driverAckMillis = -1;

	private MmtrSubTask(Kind kind, String text, long dwellMillis) {
		this.kind = kind;
		this.text = text == null ? "" : text;
		this.dwellMillis = Math.max(0, dwellMillis);
	}

	/** **停在目标**（模板 {@code DRIVE_TO} 与 {@code STOP_AND_SERVE} 的第一条都是它）。 */
	public static MmtrSubTask stopAtTarget(MmtrTaskTarget target) {
		return new MmtrSubTask(Kind.STOP_AT_TARGET, "停在 " + target.label(), 0);
	}

	public static MmtrSubTask openDoors() {
		return new MmtrSubTask(Kind.OPEN_DOORS, "开门", 0);
	}

	/** **等待上下客**：秒数是模板参数（用户口径「时间暂定 20 秒，但保留修改接口」）。 */
	public static MmtrSubTask waitPassengers(long dwellMillis) {
		return new MmtrSubTask(Kind.WAIT_PASSENGERS,
			"等待上下客 " + Math.round(Math.max(0, dwellMillis) / 1000.0) + "s", dwellMillis);
	}

	public static MmtrSubTask closeDoors() {
		return new MmtrSubTask(Kind.CLOSE_DOORS, "关门", 0);
	}

	public Kind kind() {
		return kind;
	}

	/** **这一条基础操作的人话**（含目标名与停留秒数；引擎生成，客户端不拼）。 */
	public String text() {
		return text;
	}

	public State state() {
		return state;
	}

	public boolean isDone() {
		return state == State.DONE;
	}

	/** 达成的时刻（引擎时钟）；未达成为 {@code -1}。 */
	public long doneMillis() {
		return doneMillis;
	}

	/** 这一条要求的等待时长（只有 {@link Kind#WAIT_PASSENGERS} 读它）。 */
	public long dwellMillis() {
		return dwellMillis;
	}

	/** 记"正在做"（第一次进入时记时刻，等待计时就从这里起算）。 */
	public void markActive(long now) {
		if (state == State.PENDING) {
			state = State.ACTIVE;
			activeSinceMillis = now;
		}
	}

	public void markDone(long now) {
		if (state != State.DONE) {
			state = State.DONE;
			doneMillis = now;
		}
	}

	/** 记一次司机侧确认（重复确认只记第一次）。 */
	public boolean markDriverAck(long now) {
		if (driverAckMillis >= 0) {
			return false;
		}
		driverAckMillis = now;
		return true;
	}

	public boolean isDriverAcked() {
		return driverAckMillis >= 0;
	}

	public long driverAckMillis() {
		return driverAckMillis;
	}

	/** 已经"在做"多久（不是 ACTIVE 时返回 0）—— 等待上下客的进度条读它。 */
	public long elapsedMillis(long now) {
		return state == State.ACTIVE ? Math.max(0, now - activeSinceMillis) : 0;
	}

	/**
	 * 线上一格：{@code STOP_AT_TARGET:DONE:A:停在 3站1台} ——
	 * {@code 类型:引擎判定:客户端是否已确认:人话}。
	 *
	 * <p>把**两个方向**与**词**都编进去是刻意的：客户端拿到的就是"引擎看到什么 + 我确认过什么 +
	 * 这一条叫什么"。于是"双向确认"在这条链上是**可见的数据**（HUD 因此能画出 {@code ✔✔} 与
	 * {@code ✔?} 两种不同的格子），而中文词仍然全部出自引擎 —— 客户端不拼、不译、不判。</p>
	 */
	public String encode() {
		return kind.name() + ":" + state.name() + ":" + (isDriverAcked() ? "A" : "-") + ":" + text;
	}

	/** 给日志/HUD 的行内一格：{@code ✔停在 3站1台} / {@code ▶开门} / {@code ·关门}。 */
	public String describe() {
		return switch (state) {
			case DONE -> "✔" + text;
			case ACTIVE -> "▶" + text;
			case PENDING -> "·" + text;
		};
	}

	/** 解析 {@link #encode()} 的一格（客户端与测试都用它，避免两处各写一个解析器）。 */
	public static Kind kindOf(String encodedChunk, @Nullable Kind fallback) {
		if (encodedChunk == null) {
			return fallback;
		}
		final int separator = encodedChunk.indexOf(':');
		final String name = separator < 0 ? encodedChunk : encodedChunk.substring(0, separator);
		for (final Kind kind : Kind.values()) {
			if (kind.name().equalsIgnoreCase(name.trim())) {
				return kind;
			}
		}
		return fallback;
	}
}
