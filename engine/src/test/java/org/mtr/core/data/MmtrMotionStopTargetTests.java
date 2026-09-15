package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrMotionSnapshot;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 slice 4: precise stop targets in live Motion-Core run mode. A motion vehicle running under the
 * existing cab control auto service-brakes and comes to rest EXACTLY at an armed cumulative stop
 * target (mid-rail included - no node / platform structure needed), opens the doors when the stop
 * asks for it, holds there while the driver keeps the same command, and departs again only after a
 * fresh control application - after which the next stop target can be armed while already running.
 * This is the foundation the platform/SERVE semantics will build on (target = platform stop offset).
 */
public final class MmtrMotionStopTargetTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * Chain with no forks: yard rail YR (-32..-20, buffer at -32) -> mouth rail MA (-20..0) ->
	 * platform rail PL (0..60). Single continuation everywhere, so the only thing that can stop the
	 * vehicle is the armed stop target (or the end of the line).
	 */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-stop-target"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail mouthRail = through(yardMouth, new Position(0, 0, 0));
		final Rail platformRail = through(new Position(0, 0, 0), new Position(60, 0, 0));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);

		final double targetM; // cumulative distance of the first stop (mid platform rail)

		Net() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(mouthRail);
			sim.rails.add(platformRail);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "siding must attach to the depot yard");
			siding.tick();
			targetM = yardRail.railMath.getLength() + mouthRail.railMath.getLength() + 18.0;
		}

		Vehicle spawn() {
			final BranchStore store = new BranchStore();
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void drive(UUID driver) {
			siding.simulateVehicles(1000, null);
		}
	}

	private static void boardDriver(Net n, Vehicle v, int throttle) {
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(throttle).setReverser(1), driver).apply(n.sim);
		assertTrue(v.isMmtrManualOverride(), "drive command must hold the override");
	}

	@Test
	public void motionVehicleStopsExactlyAtTargetOpensDoorsAndResumesOnFreshCommand() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		v.setMmtrMotionStopTarget(n.targetM, true);
		boardDriver(n, v, 3);

		// Run until the exact stop at the armed target.
		boolean arrived = false;
		double maxProgress = v.getRailProgress();
		for (int i = 0; i < 3000 && !arrived; i++) {
			n.drive(null);
			maxProgress = Math.max(maxProgress, v.getRailProgress());
			arrived = v.isMmtrMotionStoppedAtTarget();
		}
		assertTrue(arrived, "vehicle must arrive at the armed stop target, progress=" + maxProgress + " target=" + n.targetM);
		assertEquals(n.targetM, v.getRailProgress(), 0.05, "stop position must be exact (no overshoot/undershoot)");
		assertTrue(v.getRailProgress() <= n.targetM + 1e-3, "no overshoot past the stop target");
		assertEquals(0, v.getSpeed(), 1e-9, "vehicle at rest at the target");
		assertTrue(v.vehicleExtraData.getDoorMultiplier() > 0, "doors open at the target stop");
		assertEquals(n.platformRail.getHexId(), v.getMmtrMotionWalker().railHex(), "stopped mid platform rail");

		// Snapshot agrees: resting, doors open, live segment state.
		final MmtrMotionSnapshot snap = MmtrMotionSnapshot.from(n.siding, v);
		assertTrue(!snap.moving && snap.doorsOpen, "snapshot reports a stopped vehicle with open doors");
		assertTrue(snap.segmentOffsetM > 0, "snapshot carries the mid-rail offset");

		// Holding the SAME command does not depart the stop.
		final double heldProgress = v.getRailProgress();
		for (int i = 0; i < 20; i++) {
			n.drive(null);
		}
		assertEquals(heldProgress, v.getRailProgress(), 1e-6, "vehicle holds at the stop target while the command is unchanged");
		assertTrue(v.isMmtrMotionStoppedAtTarget(), "still stopped at the target");
		assertTrue(v.vehicleExtraData.getDoorMultiplier() > 0, "doors stay open while holding");

		// A FRESH control application departs: doors close, the vehicle moves past the stop.
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), null).apply(n.sim);
		double afterResume = v.getRailProgress();
		boolean doorsClosedWhileMoving = false;
		for (int i = 0; i < 3000 && afterResume < n.targetM + 20; i++) {
			n.drive(null);
			afterResume = Math.max(afterResume, v.getRailProgress());
			if (v.getSpeed() > 0 && v.vehicleExtraData.getDoorMultiplier() < 0) {
				doorsClosedWhileMoving = true;
			}
		}
		assertTrue(afterResume > n.targetM + 5, "vehicle must depart the stop and run on, progress=" + afterResume);
		assertTrue(!v.isMmtrMotionStoppedAtTarget(), "stop target consumed by the departure");
		assertTrue(doorsClosedWhileMoving, "doors close while the vehicle runs again");
	}

	@Test
	public void secondStopTargetCanBeArmedWhileRunning() {		final Net n = new Net();
		final Vehicle v = n.spawn();
		v.setMmtrMotionStopTarget(n.targetM, false);
		boardDriver(n, v, 3);

		boolean arrived = false;
		for (int i = 0; i < 3000 && !arrived; i++) {
			n.drive(null);
			arrived = v.isMmtrMotionStoppedAtTarget();
		}
		assertTrue(arrived, "first target stop reached");
		assertEquals(n.targetM, v.getRailProgress(), 0.05, "first stop exact");

		// Resume and arm a SECOND stop target (25 m further, still mid platform rail) while running.
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1), null).apply(n.sim);
		final double secondTarget = n.targetM + 25.0;
		boolean armedWhileRunning = false;
		for (int i = 0; i < 60 && !armedWhileRunning; i++) {
			n.drive(null);
			armedWhileRunning = v.getRailProgress() > n.targetM + 2;
		}
		assertTrue(armedWhileRunning, "vehicle is running again past the first stop");
		v.setMmtrMotionStopTarget(secondTarget, true);

		boolean arrived2 = false;
		for (int i = 0; i < 3000 && !arrived2; i++) {
			n.drive(null);
			arrived2 = v.isMmtrMotionStoppedAtTarget();
		}
		assertTrue(arrived2, "second target stop reached mid-run, progress=" + v.getRailProgress() + " target=" + secondTarget);
		assertEquals(secondTarget, v.getRailProgress(), 0.05, "second stop position exact");
		assertTrue(v.getRailProgress() <= secondTarget + 1e-3, "no overshoot on the second stop");
		assertTrue(v.vehicleExtraData.getDoorMultiplier() > 0, "second stop opens doors");
	}

	/**
	 * **停车点按"锚点"落地，不按估算里程**（notes/155 现场）。
	 *
	 * <p>现场读数：同一根站台轨，两次自臂给出 1298 m 与 1375 m 两个停车点（累计里程是"当时的位置 +
	 * 算出来的进路长度"，进路一变就漂），车于是穿过站台又往前开 77 m 才开门 —— 用户看到的就是
	 * "3 站以后不停站"。修法：除了累计里程（只当刹车目标），再给一个**锚点**（停在哪根轨、轨上多深）；
	 * 车头一进那根轨就把目标校正成量出来的值，估算偏多少都不影响停车位置。</p>
	 *
	 * <p>红证：把 {@code Vehicle#mmtrResolveStopAnchor()} 去掉（或让锚点不参与），
	 * 车会一路开到估算的 {@code targetM + 40} 才停，本用例第一段立刻红。</p>
	 */
	@Test
	public void thePlatformAnchorWinsOverACumulativeEstimateThatRunsLong() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		final double anchorOffsetM = 18.0;
		final double anchorFraction = anchorOffsetM / n.platformRail.railMath.getLength();
		// 估算值比锚点长 40 m：现场"重规划后停车点漂到站台外"就是这一种
		v.setMmtrMotionStopTarget(n.targetM + 40.0, n.platformRail.getHexId(), anchorFraction, true);
		boardDriver(n, v, 3);

		boolean arrived = false;
		for (int i = 0; i < 3000 && !arrived; i++) {
			n.drive(null);
			arrived = v.isMmtrMotionStoppedAtTarget();
		}
		assertTrue(arrived, "必须到点停车：progress=" + v.getRailProgress() + " 锚点=" + n.targetM);
		assertEquals(n.targetM, v.getRailProgress(), 0.05, "锚点说了算 —— 停在站台轨内 18 m 处，不是估算的 +40 m");
		assertTrue(v.getRailProgress() <= n.targetM + 0.05, "不许冲过锚点");
		assertEquals(n.platformRail.getHexId(), v.getMmtrMotionWalker().railHex(), "停在站台轨上（不是站台外的那根轨）");
		assertEquals(anchorOffsetM, v.getMmtrMotionWalker().offsetM(), 0.05, "车头恰好停在锚点的深度上");
		assertTrue(v.vehicleExtraData.getDoorMultiplier() > 0, "到点开门");
	}

	/**
	 * **清掉停车目标时，锚点也要跟着清**（notes/155 现场实测的一个坑）。
	 *
	 * <p>任务一结束（到位/换端/收回）就会把 {@code mmtrMotionStopTargetM} 置 -1，锚点若留在字段里，
	 * "锚点校正"会每 tick 把已经清掉的目标又写回一个停车点 —— 日志刷屏，而且等于**凭空给车派了个停车目标**。
	 * 现在两处都防：清目标的入口一起清锚点，锚点判据本身也先看"还有没有目标"。</p>
	 */
	@Test
	public void clearingTheStopTargetAlsoClearsTheAnchor() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		v.setMmtrMotionStopTarget(n.targetM, n.platformRail.getHexId(), 0.3, true);
		assertTrue(v.hasMmtrMotionStopAnchor(), "给锚点之后应当在");
		v.setMmtrMotionStopTarget(-1, false);
		assertTrue(!v.hasMmtrMotionStopAnchor(), "清目标必须把锚点一起清（否则会校正出幽灵停车点）");
	}
}
