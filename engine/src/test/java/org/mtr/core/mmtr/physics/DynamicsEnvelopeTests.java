package org.mtr.core.mmtr.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包线数学的真值表（notes/234）。
 *
 * <p>这一层的价值全在"**只有一个答案**"：自动巡航、LZB、无人停点、司机越界紧急制动、保护层
 * 五条路都问同一个函数。所以这里钉的不是"某个数字好看"，而是三件事：</p>
 *
 * <ol>
 *   <li><b>互为逆运算</b>：{@code requiredDecel} / {@code brakingDistance} / {@code maxEntrySpeed}
 *       两两往返必须回到原值 —— 这是"规划器算出的刹车点"与"车真的在那里停住"不会分家的根据；</li>
 *   <li><b>退化输入不产生垃圾</b>：不需要减速 → 0；没有减速度能力 / 已经越过 → {@link DynamicsEnvelope#NEVER}
 *       （它参与 {@code min} 会被可用减速度截断，所以不会把速度算成 NaN/Inf）；</li>
 *   <li><b>余量是调用方的参数</b>：自动包线 0.98、保护层 1.05 都只是同一个判据的不同实参。</li>
 * </ol>
 */
public final class DynamicsEnvelopeTests {

	@Test
	public void requiredDecelAndBrakingDistanceAreInverses() {
		final double[] speeds = {0.5, 2, 8.33, 22.2, 44.4};   // 1.8 … 160 km/h
		final double[] decels = {0.3, 0.8, 1.5, 2.5};
		for (final double v : speeds) {
			for (final double vTarget : new double[]{0, v / 2}) {
				for (final double decel : decels) {
					final double distance = DynamicsEnvelope.brakingDistance(v, vTarget, decel);
					assertEquals(decel, DynamicsEnvelope.requiredDecel(v, vTarget, distance), 1e-9,
						"requiredDecel(brakingDistance(...)) 必须回到原减速度：v=" + v + " vT=" + vTarget + " a=" + decel);
					// 同一个距离上，"能进多快"必须正好是出发点
					assertEquals(v, DynamicsEnvelope.maxEntrySpeed(distance, vTarget, decel), 1e-9,
						"maxEntrySpeed(brakingDistance(...)) 必须回到原速度：v=" + v + " vT=" + vTarget + " a=" + decel);
				}
			}
		}
	}

	@Test
	public void physicalSanity() {
		// 80 km/h、0.8 m/s² 的常用制动：真实列车大约 300 m 上下
		assertEquals(308.6, DynamicsEnvelope.brakingDistance(22.22, 0, 0.8), 0.5);
		// 速度翻倍 ⇒ 距离四倍（同一个减速度）
		final double one = DynamicsEnvelope.brakingDistance(10, 0, 1.0);
		assertEquals(4 * one, DynamicsEnvelope.brakingDistance(20, 0, 1.0), 1e-9);
		// 减速度越强、距离越短
		assertTrue(DynamicsEnvelope.brakingDistance(20, 0, 2.0) < DynamicsEnvelope.brakingDistance(20, 0, 1.0));
	}

	@Test
	public void degenerateInputsDoNotProduceGarbage() {
		// 不需要减速（目标速度不低于当前速度）
		assertEquals(0, DynamicsEnvelope.brakingDistance(10, 10, 1), 0);
		assertEquals(0, DynamicsEnvelope.brakingDistance(10, 20, 1), 0);
		assertEquals(0, DynamicsEnvelope.requiredDecel(10, 10, 5), 0);
		assertEquals(0, DynamicsEnvelope.requiredDecel(10, 20, 5), 0);
		assertFalse(DynamicsEnvelope.requiresBraking(10, 12, 5, 1, 1.0));

		// 没有制动能力 / 距离已经用光：不是 NaN，而是"无限远 / 无限大"
		assertEquals(DynamicsEnvelope.NEVER, DynamicsEnvelope.brakingDistance(10, 0, 0), 0);
		assertEquals(DynamicsEnvelope.NEVER, DynamicsEnvelope.requiredDecel(10, 0, 0), 0);
		assertEquals(DynamicsEnvelope.NEVER, DynamicsEnvelope.requiredDecel(10, 0, -3), 0);
		assertTrue(DynamicsEnvelope.requiresBraking(10, 0, 0, 1.5, 1.05), "距离用光 ⇒ 必须开始制动");
		// NEVER 进 min 会被可用减速度截断，速度不会被算成无穷
		assertEquals(1.5, DynamicsEnvelope.usableDecel(10, 0, 0, 1.5), 0);

		// 已经越过且有速度 = "past"
		assertTrue(DynamicsEnvelope.isPast(1e-9, -0.5));
		assertFalse(DynamicsEnvelope.isPast(0, -0.5));
		assertFalse(DynamicsEnvelope.isPast(0.01, 3));
	}

	@Test
	public void theMarginIsTheCallersPolicy() {
		final double v = 0.022;      // 22 m/s（引擎内部 m/ms 口径）
		final double decel = 1.5e-6;
		final double exactlyEnough = DynamicsEnvelope.brakingDistance(v, 0, decel);
		// 距离比"刚好够用"再多一点 ⇒ 不用动手（留 1e-3 的余量避开浮点刀口）
		assertFalse(DynamicsEnvelope.requiresBraking(v, 0, exactlyEnough * 1.001, decel, 1.0), "距离够用：不需要提前制动");
		// 距离少一点 ⇒ 任何余量都会动手
		assertTrue(DynamicsEnvelope.requiresBraking(v, 0, exactlyEnough * 0.999, decel, 1.0), "距离不够 ⇒ 必须制动");
		/*
		 * **余量的方向**（很容易记反，所以钉死）：margin < 1 ⇒ **更早**动手（所需还没到可用就动手，留余量）；
		 * margin > 1 ⇒ **更晚 / 更宽容**（要先超出可用那么多才认）。
		 * 现场语义：自动巡航与 LZB 的减速包线用 0.98（宁早不晚，免得冲过限速点）；
		 * 保护层（SCR/TPWS）用 1.05（最后一次机会才紧急制动，不做惊弓之鸟）。
		 */
		final double slightlyOver = exactlyEnough / 1.02;       // 所需减速度 = 可用 × 1.02
		assertTrue(DynamicsEnvelope.requiresBraking(v, 0, slightlyOver, decel, 0.98), "留余量的调用方（0.98）这时已经动手");
		assertTrue(DynamicsEnvelope.requiresBraking(v, 0, slightlyOver, decel, 1.00), "1.00 也动手（所需已超过可用）");
		assertFalse(DynamicsEnvelope.requiresBraking(v, 0, slightlyOver, decel, 1.05), "要求超出 5% 才判的调用方还在等");
		final double clearlyOver = exactlyEnough / 1.10;        // 所需减速度 = 可用 × 1.10
		assertTrue(DynamicsEnvelope.requiresBraking(v, 0, clearlyOver, decel, 1.05), "超出 10% ⇒ 最宽容的调用方也动手");
	}

	/**
	 * **包线闭环**：用同一组函数跑一遍"按恒减速律停车 + 距离收口"，车必须**正好停在目标上**。
	 *
	 * <p>这就是自动停点那一条分支的数学（{@code Vehicle.simulateMmtrMotion} 的 {@code autoBraking}
	 * 与它后面的距离夹紧）：每拍取 {@code min(所需, 可用)} 当减速度，最后一小段把走的距离夹到剩余量上。
	 * 若 {@code requiredDecel} 与 {@code brakingDistance} 哪天不互逆了，这条用例会红 ——
	 * 现场表现正是"冲过站台 / 提前停死"（notes/155）。</p>
	 */
	@Test
	public void closingOnTheTargetWithTheSharedPrimitiveLandsExactly() {
		final double decel = 1.0;
		final double dt = 0.05;
		double v = 15.0;
		double remaining = DynamicsEnvelope.brakingDistance(v, 0, decel) + 120.0;   // 先巡航一段再刹
		int guard = 0;
		while (v > 1e-9 && remaining > 1e-9 && guard++ < 200_000) {
			if (remaining <= DynamicsEnvelope.brakingDistance(v, 0, decel)) {
				v = Math.max(0, v - DynamicsEnvelope.usableDecel(v, 0, remaining, decel) * dt);
			}
			final double wanted = v * dt;
			if (wanted > remaining) {
				// 引擎的收口：把这一拍走的距离夹到剩余量上（不许冲过停车点），车也随之停住
				remaining = 0;
				v = 0;
			} else {
				remaining -= wanted;
			}
		}
		assertTrue(guard < 200_000, "必须收敛");
		assertEquals(0, v, 1e-9, "停在目标速度上");
		assertEquals(0, remaining, 1e-9, "正好停在目标位置上（不是提前停死，也不是冲过去）");
	}
}
