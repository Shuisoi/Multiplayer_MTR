package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **存档里的轨 hex 键在载入时归一化**（notes/130 §6b 的遗留项）。
 *
 * <p>同一条实体轨有两种写法（互为逆序），对外一律发规范写法（{@code canonicalHex}），而
 * {@code mmtr-points.json} 的行（{@code switches[].via}）与人工锁（{@code locks[].via}）存的是
 * **引擎内部写法**。同一份存档读回来是一致的，所以今天不会出错；但**世界改画**（某根轨反向重画）
 * 之后旧键就成了孤儿键 —— 锁还在文件里，却静默锁不住任何东西。</p>
 *
 * <p>修法：在 {@code refreshMmtrTurnouts} 的"轨图签名变了"那一刻，用 {@code mmtrResolveRailHex}
 * （两种写法都认）把持久化的键翻成引擎内部写法。载入的那一刻做不了 —— 那时轨图还没建好。</p>
 */
public final class MmtrPointPersistenceTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 同一条轨的**另一种写法**（两个端点互换，与 {@code canonicalHex} 用的是同一个公式）。 */
	private static String otherWriting(String hex) {
		final String[] p = hex.split("-");
		return p[3] + "-" + p[4] + "-" + p[5] + "-" + p[0] + "-" + p[1] + "-" + p[2];
	}

	/** 单开道岔夹具（与 {@code MmtrTurnoutAuthorityTests} 同形）。 */
	private static Simulator forkNet(String path) {
		/*
		 * 夹具目录是**复用**的：上一次跑落盘的 mmtr-points.json（归一化之后的键）会被载入，
		 * 于是"归一化之前"这个前提就不成立了。本组用例测的正是载入/归一化那条路，所以先清干净。
		 */
		try {
			// 存档实际落在 <path>/<dimension>/（Simulator 会拼上维度名）
			java.nio.file.Files.deleteIfExists(Paths.get(path).resolve("mmtr-points.json"));
			java.nio.file.Files.deleteIfExists(Paths.get(path).resolve("test").resolve("mmtr-points.json"));
		} catch (java.io.IOException ignored) {
			// 删不掉就让下面断言自己说话
		}
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(path), false);
		final Position node = new Position(0, 0, 0);
		simulator.rails.add(through(new Position(-20, 0, 0), node));
		simulator.rails.add(through(node, new Position(20, 0, 0)));
		simulator.rails.add(through(node, new Position(20, 0, 14)));
		simulator.sync();
		return simulator;
	}

	/**
	 * 让下一次 {@code refreshMmtrTurnouts} 真的重跑：轨图签名变了它就重建一次
	 * （归一化正是挂在那条路上的）。
	 */
	private static void forceTurnoutRefresh(Simulator simulator) {
		simulator.rails.add(through(new Position(500, 0, 500), new Position(520, 0, 500)));
		simulator.sync();
		simulator.mmtrTurnout(0, 0, 0);
	}

	@Test
	public void aLockWrittenInTheOtherHexWritingKeepsLockingAfterNormalisation() {
		final Simulator simulator = forkNet("build/mmtr-point-hex-lock");
		final MmtrTurnout turnout = simulator.mmtrTurnout(0, 0, 0);
		assertTrue(turnout != null, "夹具必须是一处单开道岔");
		final String internal = turnout.stemRailHex;
		final String stale = otherWriting(internal);
		assertNotEquals(internal, stale, "夹具：这条轨的两种写法确实不同（否则本用例测不到东西）");

		// "世界改画之后读回来的旧存档"：锁存着另一种写法
		simulator.mmtrPointAuthority.restoreLock("0,0,0|" + stale);
		assertTrue(simulator.mmtrPointAuthority.isLocked(0, 0, 0, stale), "键按原样在（引擎当然认这个字符串）");
		assertFalse(simulator.mmtrPointAuthority.isTurnoutLocked(0, 0, 0, turnout),
			"归一化之前：这把锁是孤儿键 —— 文件里有锁，却锁不住这处道岔（静默失效）"
				+ " [stale=" + stale + " stem=" + turnout.stemRailHex + " far=" + turnout.farRailHex + " branch=" + turnout.branchRailHex + "]");

		forceTurnoutRefresh(simulator);

		assertTrue(simulator.mmtrPointAuthority.isLocked(0, 0, 0, internal), "锁被改写成引擎内部写法");
		assertFalse(simulator.mmtrPointAuthority.isLocked(0, 0, 0, stale), "旧写法的键不再留着（否则等于两把锁）");
		assertTrue(simulator.mmtrPointAuthority.isTurnoutLocked(0, 0, 0, turnout), "归一化之后：锁真的锁得住这处道岔");
		assertEquals(1, simulator.mmtrPointAuthority.locksSnapshot().size(), "锁的条数不变（只是换了键）");
	}

	@Test
	public void aRowWrittenInTheOtherHexWritingIsRekeyedToo() {
		final Simulator simulator = forkNet("build/mmtr-point-hex-row");
		final MmtrTurnout turnout = simulator.mmtrTurnout(0, 0, 0);
		assertTrue(turnout != null);
		final String internal = turnout.stemRailHex;
		final String stale = otherWriting(internal);
		assertNotEquals(internal, stale);

		simulator.mmtrPointBranches.set(0, 0, 0, stale, 0);
		assertTrue(simulator.mmtrPointBranches.contains(0, 0, 0, stale), "旧写法的行在");

		forceTurnoutRefresh(simulator);

		assertTrue(simulator.mmtrPointBranches.contains(0, 0, 0, internal), "行被改写成引擎内部写法");
		assertFalse(simulator.mmtrPointBranches.contains(0, 0, 0, stale), "旧写法的行不再留着（否则同一处道岔两行）");
	}

	/** 归一化是**幂等**的：第二次刷新不再改任何键（否则每次轨图变化都会写一次盘）。 */
	@Test
	public void normalisationIsIdempotent() {
		final Simulator simulator = forkNet("build/mmtr-point-hex-idempotent");
		final MmtrTurnout turnout = simulator.mmtrTurnout(0, 0, 0);
		assertTrue(turnout != null);
		final String internal = turnout.stemRailHex;

		simulator.mmtrPointAuthority.restoreLock("0,0,0|" + otherWriting(internal));
		forceTurnoutRefresh(simulator);
		assertTrue(simulator.mmtrPointAuthority.isLocked(0, 0, 0, internal));

		forceTurnoutRefresh(simulator);
		assertTrue(simulator.mmtrPointAuthority.isLocked(0, 0, 0, internal), "第二次刷新后键不变");
		assertEquals(1, simulator.mmtrPointAuthority.locksSnapshot().size(), "也不会多出一把锁");
	}

	/** 两种写法之外的东西（别的轨、垃圾键）不许被"归一化"成任何东西。 */
	@Test
	public void unrelatedAndDirtyKeysAreLeftAlone() {
		final Simulator simulator = forkNet("build/mmtr-point-hex-dirty");
		assertTrue(simulator.mmtrTurnout(0, 0, 0) != null);

		simulator.mmtrPointAuthority.restoreLock("0,0,0|not-a-hex");
		simulator.mmtrPointAuthority.restoreLock("999,0,999|" + otherWriting("0000000000000001-0000000000000002-0000000000000003-0000000000000004-0000000000000005-0000000000000006"));

		forceTurnoutRefresh(simulator);

		assertEquals(2, simulator.mmtrPointAuthority.locksSnapshot().size(), "两条都原样留着（不改、不删）");
		assertTrue(simulator.mmtrPointAuthority.isLocked(0, 0, 0, "not-a-hex"), "解析不了的键原样保留");
	}

	/** {@code rekey} / {@code rekeyLock} 的基本语义：没有那一项、或写法相同 = 不动（返回 false）。 */
	@Test
	public void rekeyIsANoOpWhenThereIsNothingToMove() {
		final Simulator simulator = forkNet("build/mmtr-point-hex-rekey");
		assertFalse(simulator.mmtrPointBranches.rekey(0, 0, 0, "missing", "target"), "没有这一行");
		assertFalse(simulator.mmtrPointAuthority.rekeyLock(0, 0, 0, "missing", "target"), "没有这把锁");

		simulator.mmtrPointAuthority.restoreLock("0,0,0|same");
		assertFalse(simulator.mmtrPointAuthority.rekeyLock(0, 0, 0, "same", "same"), "写法相同");
		assertTrue(simulator.mmtrPointAuthority.isLocked(0, 0, 0, "same"));
	}
}
