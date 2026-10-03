package org.mtr.core.mmtr.physics;

/**
 * **纵向动力学的包线数学**：距离 / 速度 / 减速度三者的互换 —— 纯函数、单位无关。
 *
 * <h2>为什么要有这一个类（notes/234）</h2>
 *
 * <p>{@code 0.5·v²/a} 这条换算原来在**六个地方各写了一遍**：无人自动巡航的减速包线、LZB 的减速包线、
 * 无人车的停点刹车、司机越界的紧急制动包线、保护层（SCR/TPWS）的判据，以及"要不要开始刹车"的判据。
 * 它们**必须永远给出同一个答案**（"车该在哪儿开始刹车"只应该有一个真源），否则现场表现就是
 * "冲过站台 / 提前停死 / 有的地方刹有的地方不刹"——这类 bug 在本仓已经出现过（notes/155）。
 * 而且客户端镜像跑的是**同一份**物理（{@code createMirrorConsistTypeFromSync}），
 * 所以这份数学必须能被两侧共用、且可单测。</p>
 *
 * <h2>这一层是"反向半"</h2>
 *
 * <p>正向（由手柄/缸压算加速度）在 {@link TrainPhysics} 与各控制器里；
 * 本类负责**反向**：由"还剩多少米"反解"现在需要多大减速度 / 最多能进多快"。
 * 两者互为逆运算，{@code DynamicsEnvelopeTests} 用往返一致性把它钉住（这是防"包线漂移"的那道闸）。</p>
 *
 * <h2>单位</h2>
 *
 * <p>单位无关，只要求**自洽**：引擎内部一律 m/ms 与 m/ms²（{@code MmtrSupport} 负责 SI 换算），
 * 测试里用 SI。距离必须是同一个长度单位。</p>
 */
public final class DynamicsEnvelope {

	/** "来不及 / 不许进"：比任何真实米数都大，参与 {@code min} 时会被可用减速度截断。 */
	public static final double NEVER = Double.POSITIVE_INFINITY;

	private DynamicsEnvelope() {
	}

	/**
	 * 从 {@code v1} 减到 {@code v2} 所需的距离（等减速度 {@code decel}）。
	 *
	 * @return {@code v2 >= v1} → 0；{@code decel <= 0} 且还需要减速 → {@link #NEVER}
	 */
	public static double brakingDistance(double v1, double v2, double decel) {
		final double delta = v1 * v1 - v2 * v2;
		if (delta <= 0) {
			return 0;
		}
		return decel <= 0 ? NEVER : delta / (2 * decel);
	}

	/**
	 * 在 {@code distance} 内从 {@code v1} 减到 {@code v2} 所需的等减速度。
	 *
	 * @return {@code v2 >= v1} → 0；{@code distance <= 0} 且还需要减速 → {@link #NEVER}
	 */
	public static double requiredDecel(double v1, double v2, double distance) {
		final double delta = v1 * v1 - v2 * v2;
		if (delta <= 0) {
			return 0;
		}
		return distance <= 0 ? NEVER : delta / (2 * distance);
	}

	/**
	 * 反解：从 {@code vTarget} 出发、在 {@code distance} 内减到 {@code vTarget}（先加速再刹）所允许的
	 * **最大入口速度**。给规划器/停车点用 —— "这条进路有多长 ⇒ 我最多能进多快"。
	 */
	public static double maxEntrySpeed(double distance, double vTarget, double decel) {
		if (distance <= 0) {
			return vTarget;
		}
		return Math.sqrt(Math.max(0, vTarget * vTarget + 2 * decel * distance));
	}

	/**
	 * "现在就必须开始减速了吗"：所需减速度超过了可用减速度的 {@code margin} 倍。
	 *
	 * <p><b>余量的方向</b>（很容易记反）：{@code margin < 1} ⇒ **更早**动手（所需还没到可用就动手，留余量）；
	 * {@code margin > 1} ⇒ **更晚 / 更宽容**（要先超出可用那么多才认）。余量是**调用方的策略**，
	 * 所以留成参数而不是写死在数学里：自动巡航与 LZB 的减速包线用 0.98（宁早不晚）；
	 * 保护层（SCR/TPWS）用 1.05（最后一次机会才紧急制动）。</p>
	 */
	public static boolean requiresBraking(double v, double vTarget, double distance, double decel, double margin) {
		return requiredDecel(v, vTarget, distance) > decel * margin;
	}

	/**
	 * 这一拍**可用的减速度**：{@code min(所需, 可用)}。直接喂给积分器（乘 dt 就是这一拍的速度降幅），
	 * 于是"该刹多少"与"最多能刹多少"永远由同一个数说话。
	 */
	public static double usableDecel(double v, double vTarget, double distance, double decel) {
		return Math.min(requiredDecel(v, vTarget, distance), decel);
	}

	/**
	 * 已经越过了该停的地方（还剩 0 米或已经是负数）且**还有速度** —— 保护层与"司机越界"两条共用这条判据。
	 */
	public static boolean isPast(double v, double distance) {
		return v > 0 && distance <= 0;
	}
}
