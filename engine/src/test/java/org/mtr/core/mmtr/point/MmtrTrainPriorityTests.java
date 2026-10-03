package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **优先级（服务等级 + 车号）**：两台车同时抢一处道岔时谁先走。
 *
 * <p>用户口径（2026-09-27）：</p>
 * <blockquote>
 * 「如果同时抢一个道岔，那就得设计个优先级系统了，同级（通勤）谁车号小谁先走，
 *   不同级（如区域，高铁）则按级别踩头」
 * </blockquote>
 *
 * <p>三条要守的性质：</p>
 * <ol>
 *   <li><b>同级车号小者先</b> —— 后到但车号小的车要排到队首（不是 FIFO，也不是"车号大者先"）；</li>
 *   <li><b>不同级按级别踩头</b> —— 等级高的车越过等级低的车，**与车号无关**（所以 00109 高铁
 *       排在 00102 通勤前面）；</li>
 *   <li><b>问不出优先级时逐位退回老口径</b> —— 不挂 {@link MmtrPointAuthority.OwnerPriority}
 *       （测试夹具、玩家车、无单调车）时与修前一致：计划时刻 → 到达序。</li>
 * </ol>
 */
public final class MmtrTrainPriorityTests {

	private static final String VIA = "FFFFFFFFFFFFFFEC-0000000000000000";

	/** owner → 作业单号（车号就在作业单号末两位里）。 */
	private static final Map<String, String> JOB_IDS = Map.of(
		"vC1", "00101",
		"vC2", "00102",
		"vC3", "00103",
		"vC7", "00107",
		"vC9", "00109",
		"vC10", "00110"
	);

	/** 全是通勤线的调度台（线路 001）。 */
	private static MmtrPointAuthority commuterAuthority(AtomicLong clock) {
		return new MmtrPointAuthority(clock::get)
			.withOwnerPriority(owner -> MmtrTrainPriority.of(JOB_IDS.get(owner), "通勤"));
	}

	private static ObjectArrayList<String[]> opsOn(String via, String leg) {
		final ObjectArrayList<String[]> ops = new ObjectArrayList<>();
		ops.add(new String[]{"0", "0", "0", via, leg});
		return ops;
	}

	/** 服务等级的名字与数字都认；作业单号里的车号按「前三位线路号 + 后二位车号」取。 */
	@Test
	public void thePriorityIsReadFromTheJobNumberAndTheServiceClass() {
		final MmtrTrainPriority c1 = MmtrTrainPriority.of("00101", "通勤");
		assertEquals(1L, c1.trainNumber, "00101 是 1 号车");
		assertEquals(0, c1.serviceRank, "通勤 = 最低档");
		assertEquals(MmtrTrainPriority.ServiceClass.COMMUTER.rank, c1.serviceRank);

		assertEquals(10L, MmtrTrainPriority.of("00110", "通勤").trainNumber, "末两位 = 车号，00110 是 10 号车");
		assertEquals(3, MmtrTrainPriority.of("00101", "高铁").serviceRank, "高铁 = 最高档");
		assertEquals(1, MmtrTrainPriority.of("00101", "regional").serviceRank, "英文名也认");
		assertEquals(5, MmtrTrainPriority.of("00101", "5").serviceRank, "数字等级原样保留（高于枚举里的高铁）");
		assertEquals(0, MmtrTrainPriority.of("00101", "拼错的名字").serviceRank, "认不出来按通勤 —— 不能因为拼错就把车抬成高铁");

		assertEquals(MmtrTrainPriority.NO_TRAIN_NUMBER, MmtrTrainPriority.of("morning-express", "通勤").trainNumber,
			"作业单号没有数字尾巴 ⇒ 有等级、没车号");
		assertNull(MmtrTrainPriority.of("", ""), "作业单号与等级都空 ⇒ 问不出优先级（调用方退回老口径）");
		assertNull(MmtrTrainPriority.of(null, null), "null 同样按空处理");
	}

	/** 比较规则：等级高者先；同级车号小者先；等价时**两个方向都不占先**（落到下一档键）。 */
	@Test
	public void higherClassBeatsLowerAndLowerCarNumberBeatsHigher() {
		final MmtrTrainPriority commuter1 = MmtrTrainPriority.of("00101", "通勤");
		final MmtrTrainPriority commuter2 = MmtrTrainPriority.of("00102", "通勤");
		final MmtrTrainPriority highSpeed9 = MmtrTrainPriority.of("00109", "高铁");

		assertTrue(commuter1.outranks(commuter2), "同级：车号 1 在车号 2 前面");
		assertFalse(commuter2.outranks(commuter1), "判断必须不对称");
		assertTrue(highSpeed9.outranks(commuter1), "不同级：高铁踩通勤的头（与车号无关）");
		assertFalse(commuter1.outranks(highSpeed9), "等级低的踩不动等级高的");
		assertFalse(commuter1.outranks(MmtrTrainPriority.of("00101", "通勤")), "完全等价 ⇒ 不占先");
		assertTrue(commuter1.outranks(MmtrTrainPriority.of("morning", "通勤")), "有车号的排在没车号的前面");
	}

	/**
	 * **同级车号小者先**（用户口径的第一半）：后到但车号小的车拿到道岔。
	 *
	 * <p>红证：把 {@code better} 里的车号那一档去掉（退回计划时刻 → 到达序），
	 * vC3 会排在 vC7 后面 —— 本用例立刻红。</p>
	 */
	@Test
	public void amongTheSameClassTheLowerTrainNumberGoesFirst() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = commuterAuthority(clock);
		final String via = "FFFF0000";
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, via, "holder", 0, 600_000));

		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(opsOn(via, "0"), "vC7", 600_000), "00107 先到");
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(opsOn(via, "0"), "vC3", 600_000), "00103 后到");

		a.passed(0, 0, 0, via, "holder");
		assertEquals("vC3", a.holder(0, 0, 0, via), "00103 排在 00107 前面（同级车号小者先，不按到达序）");
		a.passed(0, 0, 0, via, "vC3");
		assertEquals("vC7", a.holder(0, 0, 0, via), "然后才轮到 00107");
	}

	/**
	 * **不同级按级别踩头**（用户口径的第二半）：高铁 00109 越过通勤 00102 —— 车号更大也照样先走。
	 */
	@Test
	public void aHigherClassOvertakesEvenWithABiggerCarNumber() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = new MmtrPointAuthority(clock::get).withOwnerPriority(owner -> switch (owner) {
			case "vC2" -> MmtrTrainPriority.of("00102", "通勤");
			case "vC9" -> MmtrTrainPriority.of("00109", "高铁");
			default -> null;
		});
		final String via = "FFFF0000";
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, via, "holder", 0, 600_000));

		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(opsOn(via, "0"), "vC2", 600_000), "通勤 00102 先到");
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(opsOn(via, "0"), "vC9", 600_000), "高铁 00109 后到");

		a.passed(0, 0, 0, via, "holder");
		assertEquals("vC9", a.holder(0, 0, 0, via), "等级高的先走（车号大也踩头）");
	}

	/**
	 * 让位/收回位置也读同一条链：等级低的挡着等级高的 ⇒ 该让；同级车号大的挡着车号小的 ⇒ 该让；
	 * 反过来都不让（两边同时让位等于回到振荡）。
	 */
	@Test
	public void yieldingAlsoFollowsThePriority() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = commuterAuthority(clock);
		final String via = "FFFF0000";

		// 00107 按着 leg 1，00103 要互斥的 leg 0
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, via, "vC7", 1, 600_000));
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.request(0, 0, 0, via, "vC3", 0, 600_000));
		assertTrue(a.someoneHasPriorityOver("vC7"), "挡着车号更小的车 ⇒ 该让");

		// 反过来：00103 按着 leg 1、00107 在等 ⇒ 00103 不该让（判断必须不对称，否则两边同时让位）
		a.releaseAll("vC3");
		a.releaseAll("vC7");
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, via, "vC3", 1, 600_000));
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.request(0, 0, 0, via, "vC7", 0, 600_000));
		assertFalse(a.someoneHasPriorityOver("vC3"), "我车号更小、该先走，凭什么让");
	}

	/**
	 * **不挂优先级查询 = 老口径一动不动**（基线守卫）：没有作业单的车（玩家车、无单调车）之间
	 * 仍然是"计划时刻 → 到达序"，与修前逐位一致。
	 */
	@Test
	public void withoutAnyPriorityTheOldOrderIsUntouched() {
		final AtomicLong clock = new AtomicLong(1000);
		final MmtrPointAuthority a = new MmtrPointAuthority(clock::get).withOwnerPriority(owner -> null);
		final String via = "FFFF0000";
		assertEquals(MmtrPointAuthority.Result.GRANTED, a.request(0, 0, 0, via, "holder", 0, 600_000));

		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(opsOn(via, "0"), "vFirst", 600_000));
		assertEquals(MmtrPointAuthority.Result.QUEUED, a.requestAtomically(opsOn(via, "0"), "vSecond", 600_000));
		a.passed(0, 0, 0, via, "holder");
		assertEquals("vFirst", a.holder(0, 0, 0, via), "没有优先级就是 FIFO");

		// 计划时刻仍然有效（它退到第二档，但没有优先级时就是第一档）
		final String other = "FFFF0001";
		final MmtrPointAuthority b = new MmtrPointAuthority(clock::get).withOwnerPriority(owner -> null);
		assertEquals(MmtrPointAuthority.Result.GRANTED, b.request(0, 0, 0, other, "holder2", 0, 600_000));
		assertEquals(MmtrPointAuthority.Result.QUEUED, b.requestAtomically(opsOn(other, "0"), "vNoPlan", 600_000));
		assertEquals(MmtrPointAuthority.Result.QUEUED, b.requestAtomically(opsOn(other, "0"), "vPlanned", 600_000, 500L));
		b.passed(0, 0, 0, other, "holder2");
		assertEquals("vPlanned", b.holder(0, 0, 0, other), "计划更早的仍然先走");
	}
}
