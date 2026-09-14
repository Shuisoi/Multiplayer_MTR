package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ② 岔区清限 / 侧面防护: a section boundary sits ON the junction node, so the plain section test calls
 * the junction clear the moment a standing consist's tail leaves the node - a movement entering from a
 * THIRD leg could then be driven into that consist's side. The clearance zone keeps the junction
 * occupied until the tail is {@link Vehicle#MMTR_JUNCTION_CLEARANCE_M} past the node, and S1 rule (4)
 * holds the other movement at the signal before the junction.
 *
 * <p>Network: yard Y(-32..-20, siding) -&gt; W(-20..0) -&gt; junction J(0) {E(0..40) | S(0 -&gt; 40,+12)}.
 * Train 1 stands on E with its tail 8 m past J (inside the 10 m clearance zone); train 2 approaches J
 * from the SOUTH yard through S cleared onto W - a different leg, so only the clearance rule can stop it.</p>
 *
 * <p>岔口现在是**有物理模型**的真道岔（判据修正 notes/130），所以车 2 那条进路要先把它同意下来：
 * 用例在车 1 就位后把道岔扳到岔股（W↔S）。修前这个夹具的岔口没有模型（岔股只偏 16.7°，
 * 落在老判据返回 null 的那一带），"S 侧进路"看起来不需要道岔同意 —— 那正是缺口本身。</p>
 */
public final class MmtrJunctionClearanceTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static final class JunctionNet {
		final Simulator sim;
		final Position j = new Position(0, 0, 0);
		final Rail w;
		final Rail e;
		final Rail s;
		final Siding westYard;
		final Siding southYard;
		final BranchStore store = new BranchStore();
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();
		/** West yard 12 m + W 20 m: the arc at which E/S begin, seen from the west. */
		final double junctionFromWestM = 12.0 + 20.0;

		JunctionNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			final Position westBack = new Position(-32, 0, 0);
			final Position westMouth = new Position(-20, 0, 0);
			final Position southMouth = new Position(40, 0, 12);
			final Position southBack = new Position(52, 0, 12);
			final Rail westY = Rail.newSidingRail(westBack, Angle.fromAngle(0), westMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			w = through(westMouth, j);
			e = through(j, new Position(40, 0, 0));
			s = diverge(j, southMouth);
			final Rail southY = Rail.newSidingRail(southBack, Angle.fromAngle(180), southMouth, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			westYard = new Siding(westBack, westMouth, 12, TransportMode.TRAIN, sim);
			southYard = new Siding(southBack, southMouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Junction Yard");
			depot.setCorners(new Position(-38, -3, -3), new Position(60, 3, 20));
			sim.rails.add(westY);
			sim.rails.add(w);
			sim.rails.add(e);
			sim.rails.add(s);
			sim.rails.add(southY);
			sim.depots.add(depot);
			sim.sidings.add(westYard);
			sim.sidings.add(southYard);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			westYard.setVehicleCars(cars);
			southYard.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(westYard), "the west yard must attach to the depot");
			assertTrue(depot.savedRails.contains(southYard), "the south yard must attach to the depot");
			westYard.tick();
			southYard.tick();
			sim.mmtrEnsureSignalColors();
		}

		Vehicle spawn(Siding yard) {
			final MmtrMotionWalker walker = yard.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = yard.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			westYard.simulateVehicles(1000, trees);
			southYard.simulateVehicles(1000, trees);
		}

		void tickUntil(java.util.function.BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}

		/** Operator sets the leg at J approached via W: 0 = straight (E), 1 = diverging (S). */
		void setLegViaW(int leg) {
			store.set(j.getX(), j.getY(), j.getZ(), w.getHexId(), leg);
		}
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	@Test
	public void aMovementIsHeldAtTheJunctionWhenAnotherConsistsTailIsStillInsideTheClearanceZone() {
		final JunctionNet n = new JunctionNet("build/mmtr-junction-clearance");
		n.setLegViaW(0);

		// Train 1 runs from the west yard into E and stops with its tail 8 m past J - inside the 10 m
		// clearance zone.
		final Vehicle first = n.spawn(n.westYard);
		first.setMmtrMotionAuto(true);
		first.setMmtrMotionStopTarget(n.junctionFromWestM + 10.0, true);
		n.tickUntil(first::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(n.e.getHexId(), first.getMmtrMotionWalker().railHex(), "train 1 stands on E");
		final double tailArcM = first.getMmtrMotionWalker().offsetM() - first.vehicleExtraData.getTotalVehicleLength();
		assertTrue(tailArcM < Vehicle.MMTR_JUNCTION_CLEARANCE_M, "train 1's tail is inside the clearance zone (arc " + tailArcM + ")");

		/*
		 * Train 2 comes from the SOUTH yard and is cleared out through the junction onto W - a different
		 * leg than the one train 1 occupies, so only the clearance rule can stop it.
		 *
		 * <p>为此必须**先把道岔扳到岔股**（W↔S）：判据修正后这个岔口有了物理模型，而一处道岔的两条
		 * 进路互斥（T1，用户 2026-09-13 的规格）。修前这个夹具的岔口**没有物理模型**（岔股只偏 16.7°，
		 * 正落在老判据返回 null 的那一带），所以"S 侧那条进路"看起来不需要道岔同意 —— 那正是缺口本身：
		 * 物理互斥在那一处根本不生效。车 1 已经在 E 上（停在清空区里的是它的**车尾**），道岔扳过去不会
		 * 把它从轨上抽走。</p>
		 */
		assertTrue(n.sim.mmtrSetPoint(n.j.getX(), n.j.getY(), n.j.getZ(), n.w.getHexId(), 1),
			"把道岔扳到岔股（W↔S）：车 2 那条进路才存在");
		n.setLegViaW(1);
		final Vehicle second = n.spawn(n.southYard);
		second.setMmtrMotionAuto(true);
		second.getMmtrMotionWalker().setTargetRailHex(n.w.getHexId());
		second.setMmtrMotionStopTarget(200.0, true);
		n.tickUntil(() -> second.getSpeed() == 0 && second.isMmtrBlockHeldFromSync(), 4000);
		assertEquals(n.s.getHexId(), second.getMmtrMotionWalker().railHex(), "held on the diverging approach, not on the junction");
		assertEquals(n.s.railMath.getLength() - 0.001, second.getMmtrMotionWalker().offsetM(), 0.05, "rests epsilon short of the junction node");
		assertFalse(n.w.getHexId().equals(second.getMmtrMotionWalker().railHex()), "never crossed the fouled junction");

		// Train 1 draws further into E, clear of the clearance zone: the same held train 2 proceeds.
		first.setMmtrMotionStopTarget(n.junctionFromWestM + 30.0, false);
		n.tickUntil(() -> first.getMmtrMotionWalker().offsetM() - first.vehicleExtraData.getTotalVehicleLength() > Vehicle.MMTR_JUNCTION_CLEARANCE_M, 4000);
		n.tickUntil(() -> n.w.getHexId().equals(second.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.w.getHexId(), second.getMmtrMotionWalker().railHex(), "crosses the junction once the clearance zone is clear");

		/*
		 * 收尾：人工搬岔 = 覆盖 + **锁定**（notes/130），锁随存档落盘 —— 不清的话下一跑这个岔口一开始
		 * 就锁在岔股位，车 1 那条"走正线到 E"会当场红（实测过）。
		 */
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = n.sim.mmtrTurnout(n.j.getX(), n.j.getY(), n.j.getZ());
		assertNotNull(turnout, "这个岔口现在是真道岔（有物理模型）");
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			n.sim.mmtrPointAuthority.unlock(n.j.getX(), n.j.getY(), n.j.getZ(), via);
		}
		n.sim.mmtrSetTurnoutPosition(n.j.getX(), n.j.getY(), n.j.getZ(), org.mtr.core.mmtr.point.MmtrTurnout.NORMAL);
		n.sim.persistMmtrPointBranches();
	}
}
