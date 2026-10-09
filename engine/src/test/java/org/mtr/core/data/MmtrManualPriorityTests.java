package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **司机优先**（用户口径 2026-09-21）：
 *
 * <blockquote>「主要是针对手动开车这件事情，只要司机能上车，那么什么都也阻挡不了他开车。」
 * 「过信号闯区间等问题，触发紧急制动就行了，紧急制动也是可按响应键解除的，这才是正常逻辑，
 * 而不是给车直接速度归 0。只有在道岔没设置对会发生脱轨时才直接按到 0。」</blockquote>
 *
 * <p>这一族用例把这条规则钉住：手动车在**未授权界限**（红灯 / 区间被占 / 岔区未清）上不再被把速度钉成 0，
 * 而是**施加紧急制动**（司机按响应键 R 解除后可继续开过去 = 闯区间）；唯一还硬停在 0 的是
 * **道岔物理位置不允许该走向**（会脱轨，走行器拒绝推进）。</p>
 *
 * <p>自动/无人车一个字没改：它们在界限上照旧停车等待（{@code MmtrMotionBlockingTests} 的自动用例、
 * {@code MmtrConsistVehicleMotionTests.autoRunStillHaltsAtAnUnsetForkAndContinuesAfterTheBranchIsSet}）。</p>
 */
public final class MmtrManualPriorityTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";
	/** Simulated foreign train occupying a rail (id never collides with real vehicles). */
	private static final long OBSTACLE_VEHICLE_ID = 999_999_009L;

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** Yard (-20..-8) -> MA (-8..8) -> PL (8..68); a foreign train may occupy PL. */
	private static final class Net {
		final Simulator sim;
		final Position yardBack = new Position(-20, 0, 0);
		final Position yardMouth = new Position(-8, 0, 0);
		final Rail y;
		final Rail ma;
		final Rail pl;
		final Siding siding;
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			y = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			ma = through(yardMouth, new Position(8, 0, 0));
			pl = through(new Position(8, 0, 0), new Position(68, 0, 0));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Manual Priority Yard");
			depot.setCorners(new Position(-25, -3, -3), new Position(70, 3, 3));
			sim.rails.add(y);
			sim.rails.add(ma);
			sim.rails.add(pl);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(siding), "the siding must attach to the depot yard");
			siding.tick();
			trees.add(new Object2ObjectAVLTreeMap<>());
			trees.add(new Object2ObjectAVLTreeMap<>());
		}

		Vehicle spawn() {
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null));
			assertNotNull(vehicle, "the yard motion seam must spawn the vehicle");
			return vehicle;
		}

		/** One 1000 ms tick with the shared occupancy trees rotated (as Simulator.tick does). */
		void tick(boolean occupyPl) {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			if (occupyPl) {
				final Position orderedP1 = pl.getPosition1().compareTo(pl.getPosition2()) <= 0 ? pl.getPosition1() : pl.getPosition2();
				final Position orderedP2 = orderedP1 == pl.getPosition1() ? pl.getPosition2() : pl.getPosition1();
				Data.put(trees.get(1), orderedP1, orderedP2, vehiclePosition -> {
					final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
					newVehiclePosition.addSegment(1.0, 5.0, OBSTACLE_VEHICLE_ID);
					return newVehiclePosition;
				}, Object2ObjectAVLTreeMap::new);
			}
			siding.simulateVehicles(1000, trees);
		}
	}

	/** 司机上车（无钥匙要求、按既有口径：ride 包 isDriver + MmtrDriveControl）。 */
	private static UUID boardDriver(Simulator sim, Vehicle v, int throttle, boolean acknowledge) {
		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		v.updateRidingEntities(entities);
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(throttle).setReverser(1).setAcknowledge(acknowledge), driver).apply(sim);
		assertTrue(v.isMmtrManualOverride(), "the drive command must hold the override");
		return driver;
	}

	/**
	 * 手动车开到"**前方区间被另一列车占着**"的界限上：施加紧急制动（不是把速度钉成 0），
	 * **而且按了响应键也进不去** —— 硬界限（notes/409 §4.6 第 17 条，2026-10-09 第二次实机：
	 * 用户那趟车顶到对面那列车车头前 **2.00 m**，两车正面相对）。
	 *
	 * <p>"司机优先"仍然成立的地方是**红灯 / 自己的进路没设好**（那是信号，按响应键可以闯，
	 * 见 {@code MmtrSignalAuthorityStopTests} / {@code MmtrRedLampStopTests}）；
	 * "前方真的有一列车 / 岔区被压着"不行 —— 真车 SCR 拦的是信号，不是另一列车。</p>
	 *
	 * <p>不变量：① 紧急制动施加在**界限之前**（车不会在没刹住的情况下冲进去）；② 不按响应键一直刹住；
	 * ③ 按了之后紧急制动不再反复触发，但**位置仍被硬界限钉住**（同一脚油门也开不进那条被占的轨）。</p>
	 */
	@Test
	public void aManualDriverIsHeldAtAnOccupiedBlockEvenAfterResponding() {
		final Net n = new Net("build/mmtr-manual-priority-block");
		final Vehicle v = n.spawn();
		final UUID driver = boardDriver(n.sim, v, 3, false);

		// Run until the engine applies the emergency brake for the occupied block ahead.
		// 停车点（区间入口，x=8 处）在走行器里程里的位置：车场轨 12 m + ma 16 m = 28 m（车头停在车场轨内，
		// 所以里程从停场偏移起算，但**入口的绝对里程**就是 28 m）。
		final double entranceM = 28.0;
		boolean tripped = false;
		for (int i = 0; i < 4000 && !tripped; i++) {
			n.tick(true);
			tripped = v.isMmtrAuthorityTripped();
		}
		assertTrue(tripped, "the occupied block ahead must trip the driver's emergency brake (rail=" + v.getMmtrMotionWalker().railHex() + " progress=" + v.getRailProgress() + ")");
		assertTrue(v.isMmtrProtectionFromSync(), "the emergency brake is applied through the protection channel");

		// Unacknowledged: the emergency brake holds the train (it never creeps into the occupied rail).
		for (int i = 0; i < 30; i++) {
			n.tick(true);
		}
		assertEquals(0, v.getSpeed(), 1e-9, "the emergency brake brings the driver to a stand");
		assertEquals(n.ma.getHexId(), v.getMmtrMotionWalker().railHex(), "held before the occupied rail");
		assertTrue(v.getRailProgress() <= entranceM + 0.5, "never entered the occupied rail: " + v.getRailProgress());
		// 理由是在**下一拍**才镜像出去的（闸门跑在 tick 入口）：所以在这里断言，而不是刚触发那一拍。
		assertTrue(v.getMmtrHoldReasonFromSync().contains("响应键"), "the HUD must tell the driver which key releases it: " + v.getMmtrHoldReasonFromSync());

		// ★ 2026-10-09 现场口径（第二次实机撞车）：响应键解除的是**紧急制动**，不是那条界限 ——
		// 同一脚油门**开不进**被另一列车占着的轨。红灯/进路没设好那一路才按响应键放行。
		new MmtrDriveControl(v.getId(), new ControlState().setThrottleNotch(3).setReverser(1).setAcknowledge(true), driver).apply(n.sim);
		assertFalse(v.isMmtrProtectionFromSync(), "the response key releases the emergency brake");
		assertFalse(v.isMmtrAuthorityTripped(), "and it does not re-arm for the same boundary");
		for (int i = 0; i < 200; i++) {
			n.tick(true);
		}
		assertEquals(n.ma.getHexId(), v.getMmtrMotionWalker().railHex(),
			"按了响应键也**不许**开进被另一列车占着的轨（rail=" + v.getMmtrMotionWalker().railHex() + "）");
		assertTrue(v.getRailProgress() <= entranceM + 0.5,
			"位置被硬界限钉在区间入口：progress=" + v.getRailProgress() + " / 入口 " + entranceM);
	}

	/**
	 * 手动车在**未设好的岔口**上跟随道岔的物理位置（这里默认位 = 正线/直行），不再停在岔前等 ——
	 * 自动车照旧停在岔前（由 {@code MmtrConsistVehicleMotionTests} 的自动用例钉住）。
	 */
	@Test
	public void aManualDriverFollowsTheTurnoutInsteadOfHaltingAtIt() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-manual-priority-fork"), false);
		final Position rear = new Position(-16, 0, 0);
		final Position mouth = new Position(-8, 0, 0);
		final Rail yRail = Rail.newSidingRail(rear, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rA = through(mouth, new Position(40, 0, 0));
		final Rail rB = through(mouth, new Position(40, 0, 8));
		sim.rails.add(yRail);
		sim.rails.add(rA);
		sim.rails.add(rB);
		sim.sync();

		final BranchStore store = new BranchStore();
		final Vehicle vehicle = new Vehicle(VehicleExtraData.createWithLegs(1L, 0L, 6,
			new ObjectArrayList<>(java.util.List.of(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1))),
			new ObjectArrayList<>(), true, 120, 30000L), null, TransportMode.TRAIN, sim);
		vehicle.engageMmtrMotion(org.mtr.core.mmtr.segment.MmtrMotionWalker.start(sim, yRail, rear, store, null));
		vehicle.applyMmtrControl(new ControlState().setThrottleNotch(3).setReverser(1));
		for (int i = 0; i < 300; i++) {
			vehicle.simulate(1000, null, null);
		}
		assertFalse(vehicle.getMmtrMotionWalker().haltedAtAuthority(), "a cab driver is not held at an unset fork");
		assertEquals(rA.getHexId(), vehicle.getMmtrMotionWalker().railHex(), "follows the physically open (straight) leg");
	}
}
