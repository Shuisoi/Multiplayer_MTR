package org.mtr.core.mmtr.signal;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现场缺陷回归（2026-09-16）：**行车区间（L2）在折返咽喉处断链** —— 南行方向的链在道岔节点上
 * 被**隔壁节点那盏灯**砍断，于是折返段（31 m）那根轨**没有南行区间**。
 *
 * <h3>现场读数（dev 世界，北端折返咽喉）</h3>
 * <pre>
 * 节点 -176,-60,-253 是一处单开道岔：根部 (-176,-222) / 正线远端 (-176,-289) / 岔股 (-170,-289)
 *   36 m 轨 (-176,-289)→(-176,-253)   31 m 轨 (-176,-253)→(-176,-222)   36.66 m 斜线 (-176,-253)→(-170,-289)
 * L2（/mmtr-block-sections）：
 *   36 m 轨：南行（入口灯 -178,-60,-289）+ 北行（入口灯 -174,-60,-253）
 *   斜线  ：南行（入口灯 -172,-60,-289）+ 北行（入口灯 -174,-60,-253）
 *   31 m 轨：**只有北行**（入口灯 -174,-60,-222）—— 南行那一段根本不存在
 * signal why -178 -60 -289 的结束原因：停在面向本区间的灯 -172,-60,-253（正常：…）
 * </pre>
 *
 * <h3>根因（本用例钉住的那一条）</h3>
 * <p>{@code lampAt(node, heading)} 找"这个节点上、朝本走行方向的下一架灯"时用的是**半径 4 m 的圆**
 * （{@link MmtrSectionService} 的 {@code NODE_BIND_RADIUS_M}，由"灯离最近节点 3.16 格"的实测值定）。
 * A 线节点 {@code -170,-253} 旁那盏朝南的灯 {@code -172,-60,-253} 距离本节点**正好 4.0 m**
 * （判据是灯格中心减节点格中心，{@code 4.0 <= 4.0} 收下），而它面朝南 = 正对"从 36 m 轨继续开进
 * 31 m 折返段"这个走行方向 ⇒ 走行在节点上就"看见下一架灯"，31 m 轨那一支**既不收 span 也不继续走**，
 * 南行链到此为止。</p>
 *
 * <p>判据改用**与其余代码同一条**："灯属于离它最近的节点"（{@link MmtrSectionService} 里
 * {@code nearestNode} 的口径，{@code resolveProtectedRailsInternal} 判"灯在哪个节点上"、
 * {@code buildSection} 判"这条腿是不是被道岔切掉的那一侧"用的都是它）。隔壁节点的灯由它自己的节点认领，
 * 不再越界砍别人的走行。</p>
 *
 * <p>几何用的是现场坐标（y 从 -60 降到 0，竖直位置的差别对区间链没有影响）。折返段尽头
 * {@code -176,-222} 在这里是死胡同（现场那里还有内容，但不影响本用例要钉的那一段）。</p>
 */
public final class MmtrThroatSectionChainTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final Position N_TAIL = new Position(-176, 0, -222);
	private static final Position N_MID = new Position(-176, 0, -253);
	private static final Position N_FAR = new Position(-176, 0, -289);
	private static final Position N_A_MID = new Position(-170, 0, -253);

	private static Rail rail(Position p1, Position p2) {
		final double bearing = Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
		return Rail.newRail(p1, Angle.fromAngle((float) bearing), p2, Angle.fromAngle((float) (bearing + 180)),
			Rail.Shape.QUADRATIC, 0, NO_STYLES, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 咽喉夹具：折返咽喉三根轨 + 两侧接轨（A 线节点也在图里，否则隔壁那盏灯不会成为"另一个节点的灯"）。 */
	private static final class Throat {
		final Simulator simulator;
		final Rail main36 = rail(N_FAR, N_MID);            // 36 m 正线
		final Rail tail31 = rail(N_MID, N_TAIL);           // 31 m 折返段
		final Rail diagonal = rail(N_MID, new Position(-170, 0, -289));   // 岔股斜线
		final Rail aLine36 = rail(new Position(-170, 0, -289), N_A_MID);  // A 线 36 m
		/** 现场 -174,-60,-253：节点旁朝北（180°）的灯，守 36 m 轨与斜线的**北行**。 */
		final String lampMidNorth = MmtrSignalRegistry.key(-174, 0, -253);
		/** 现场 -178,-60,-289：朝南（0°）的灯，守 36 m 轨的**南行**。 */
		final String lampFarSouth = MmtrSignalRegistry.key(-178, 0, -289);
		/** 现场 -172,-60,-289：朝南（0°）的灯，守斜线与 A 线 36 m 轨的南行。 */
		final String lampDiagSouth = MmtrSignalRegistry.key(-172, 0, -289);
		/** 现场 -174,-60,-222：折返段尽头旁朝北（180°）的灯，守 31 m 轨的北行。 */
		final String lampTailNorth = MmtrSignalRegistry.key(-174, 0, -222);
		/** 现场 -172,-60,-253：**A 线节点 -170,-253 旁**那盏朝南（0°）的灯 —— 它离本节点正好 4.0 m。 */
		final String lampANodeSouth = MmtrSignalRegistry.key(-172, 0, -253);
		final MmtrSectionService service;

		Throat(String savePath, int turnoutPosition) {
			simulator = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			simulator.rails.add(main36);
			simulator.rails.add(tail31);
			simulator.rails.add(diagonal);
			simulator.rails.add(rail(N_FAR, new Position(-176, 0, -306)));          // B 线往站 1
			simulator.rails.add(rail(new Position(-170, 0, -289), new Position(-170, 0, -306)));
			simulator.rails.add(aLine36);
			simulator.rails.add(rail(N_A_MID, new Position(-170, 0, -199)));
			simulator.sync();
			// 登记灯时直接写登记表（不走 mmtrSignalOp）：那条路会落盘到 savePath，污染复用同目录的用例。
			simulator.mmtrSignals.put(-174, 0, -253, 180, 2, "AUTO", "");
			simulator.mmtrSignals.put(-178, 0, -289, 0, 2, "AUTO", "");
			simulator.mmtrSignals.put(-172, 0, -289, 0, 2, "AUTO", "");
			simulator.mmtrSignals.put(-174, 0, -222, 180, 2, "AUTO", "");
			simulator.mmtrSignals.put(-172, 0, -253, 0, 2, "AUTO", "");
			if (turnoutPosition != 0) {
				simulator.mmtrPointBranches.setNode(N_MID.getX(), N_MID.getY(), N_MID.getZ(), turnoutPosition);
			}
			service = new MmtrSectionService(simulator);
		}

		MmtrSectionService.Section sectionOn(String lampKey, Rail rail) {
			final ObjectArrayList<MmtrSectionService.Section> sections = service.sectionsOfSignal(lampKey);
			for (final MmtrSectionService.Section section : sections) {
				for (final MmtrSectionService.RailSpan span : section.spans) {
					if (span.railHex.equals(rail.getHexId()) || span.railHex.equals(MmtrSectionService.canonicalHex(rail.getHexId()))) {
						return section;
					}
				}
			}
			return null;
		}

		boolean coveredBy(String lampKey, Rail rail) {
			return sectionOn(lampKey, rail) != null;
		}

		String nodeKey(Position node) {
			return MmtrJunctionState.nodeKey(node);
		}
	}

	/**
	 * 现场读数（修前）：{@code 灯 -178,-60,-289} 的南行段只有 36 m（结束原因"停在面向本区间的灯
	 * -172,-60,-253"），31 m 折返段**没有南行段**。修后：这一段要**跨过节点继续走进 31 m 轨**。
	 */
	@Test
	public void theNeighbourNodesLampDoesNotCutTheSouthboundChainAtTheTurnout() {
		final Throat throat = new Throat("build/mmtr-throat-chain-0", 0);
		final MmtrSectionService.Section south = throat.sectionOn(throat.lampFarSouth, throat.main36);
		assertNotNull(south, "南行那盏灯要守住 36 m 轨");
		assertEquals(2, south.spans.size(),
			"南行链必须跨过节点走进 31 m 折返段（修前被隔壁节点的灯砍成 1 根轨）");
		assertTrue(throat.coveredBy(throat.lampFarSouth, throat.tail31),
			"31 m 折返段必须有南行区间 —— 现场作业单 tbN 那一步正是受南行许可开进这一段");
		assertEquals(67, south.lengthM(), 1.5, "36 m + 31 m = 67 m（修前 36 m）");
		assertFalse(south.blockedAtArrival, "道岔位置 0 = 正线贯通：36 m 轨 ↔ 31 m 轨 是直股续行，不是禁行侧");
		assertEquals(throat.nodeKey(N_TAIL), south.endNodeKey, "这一段结束在折返段尽头 -176,-222");
		assertTrue(south.endsAtDeadEnd, "尽头再没有轨（现场那里还有内容，但本夹具到此为止）");
		assertTrue(south.endReason.contains("背向本方向"),
			"尽头那盏灯 -174,-222 朝北 = 背向本走行方向 ⇒ 按「灯到灯」的语义**不切断**本区间（这正是链该继续的原因）："
				+ south.endReason);
	}

	/**
	 * 反面：**属于这个节点**的灯照旧断段（"灯到灯"的语义不许被上一条改掉）。
	 *
	 * <p>{@code -174,-60,-222}（折返段尽头那盏朝北的灯）的段走到节点 {@code -176,-253}，
	 * 那里有同向（朝北）的灯 {@code -174,-60,-253} ⇒ 到此为止，出口灯就是它。</p>
	 */
	@Test
	public void theLampThatBelongsToTheNodeStillCutsTheChain() {
		final Throat throat = new Throat("build/mmtr-throat-chain-cut", 0);
		final MmtrSectionService.Section north = throat.sectionOn(throat.lampTailNorth, throat.tail31);
		assertNotNull(north, "折返段北行那盏灯要守住 31 m 轨");
		assertEquals(1, north.spans.size(), "北行链在节点上被同向的下一架灯切断（不跨进 36 m 轨）");
		assertEquals(throat.nodeKey(N_MID), north.endNodeKey, "停在道岔节点上");
		assertEquals(throat.lampMidNorth, north.exitSignalKey, "出口灯 = 节点上那盏朝北的灯");
		assertTrue(north.endReason.contains("停在面向本区间的灯"), north.endReason);

		// A 线那盏灯也照旧守着**它自己的节点**：从 A 线 36 m 轨走过来的南行链停在 -170,-253。
		final MmtrSectionService.Section aLine = throat.sectionOn(throat.lampDiagSouth, throat.aLine36);
		assertNotNull(aLine, "南行那盏灯要守住 A 线 36 m 轨");
		assertEquals(throat.nodeKey(N_A_MID), aLine.endNodeKey, "停在 A 线自己的节点上");
		assertEquals(throat.lampANodeSouth, aLine.exitSignalKey,
			"它在自己的节点上照旧是边界（本用例的修法只把它从**隔壁节点**的走行里请出去）");
	}

	/**
	 * 安全语义不许弄丢：道岔扳到岔股（位置 1）时，36 m 轨的南行**读红**（那正是当前位置禁止通行的一侧），
	 * 而南行链改由**岔股**那条腿继续走进折返段。
	 */
	@Test
	public void aProhibitedLegStillReadsRedAndTheChainRunsThroughTheOtherLeg() {
		final Throat throat = new Throat("build/mmtr-throat-chain-1", 1);
		final MmtrSectionService.Section south = throat.sectionOn(throat.lampFarSouth, throat.main36);
		assertNotNull(south, "36 m 轨上仍有那盏南行灯");
		assertTrue(south.blockedAtArrival,
			"位置 1 = 岔股开放 ⇒ 从 36 m 轨往南走是**禁行侧**，这一段必须读红（走不出去）");
		assertFalse(throat.coveredBy(throat.lampFarSouth, throat.tail31), "读红的那一支不该再跨进折返段");

		final MmtrSectionService.Section viaBranch = throat.sectionOn(throat.lampDiagSouth, throat.diagonal);
		assertNotNull(viaBranch, "岔股那条腿是这一次运行走得出去的路");
		assertTrue(viaBranch.spans.size() >= 2, "岔股那一支继续走进折返段");
		assertTrue(throat.coveredBy(throat.lampDiagSouth, throat.tail31), "折返段仍然有南行区间（走岔股这条）");
	}
}
