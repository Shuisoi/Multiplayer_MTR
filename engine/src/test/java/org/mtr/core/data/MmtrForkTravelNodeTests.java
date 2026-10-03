package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * notes/171 现场缺陷（2026-09-16）的回归用例：**"前方节点/来向节点"必须按行车方向读**。
 *
 * <p>编组走行体（{@code MmtrConsistWalker}）的 {@code aheadNode()/enteredFromPosition()} 是脊线
 * A→B 的两个端点 —— 车**反向**（A 端在前）行驶时它们与行车方向正好相反。车辆侧"停在岔口前按计划
 * 补申请"那条自救路原来读裸值，于是问错了节点：{@code legIndexForRail} 必然解不出腿，每 5 秒打一行
 * "计划要的腿不在岔口腿表里"的假警报（现场它指着 {@code -170,-60,-458}，而那处按 {@code query node}
 * 只有度 2、根本不是岔口），真正该发的申请一次都没发出去 —— 车就停在站台上等。</p>
 *
 * <p>{@link MmtrRunPlanner#travelAheadNode} / {@link MmtrRunPlanner#travelEntryNode} 是引擎里既有的
 * 方向感知口径（规划器一直在用），这里把它钉成公开契约。</p>
 */
public final class MmtrForkTravelNodeTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 与 {@code MmtrConsistChangeEndsReplanTests} 同形的夹具：库里一条编组，咽喉一处岔口。 */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-fork-travel-nodes"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position yardMouth = new Position(-20, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rX = through(yardMouth, new Position(60, 0, 0));
		final Rail rY = through(yardMouth, new Position(60, 0, 14));
		final Rail rearChain = through(yardBack, new Position(-60, 0, 0));
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
		final BranchStore store = new BranchStore();

		Net() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(rX);
			sim.rails.add(rY);
			sim.rails.add(rearChain);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			siding.tick();
		}

		Vehicle spawn() {
			final Vehicle vehicle = siding.spawnMmtrConsistVehicle(siding.mmtrConsistWalkerFromYard(null, store, null), org.mtr.core.mmtr.consist.MmtrCabState.Cab.CAB_A);
			assertNotNull(vehicle, "consist seam must spawn");
			return vehicle;
		}
	}

	@Test
	public void travelNodesFollowTheDirectionOfTravelNotTheSpineOrder() {
		final Net n = new Net();
		final Vehicle v = n.spawn();
		final MmtrMotionPosition walker = v.getMmtrMotionWalker();
		assertNotNull(walker, "consist walker must be engaged");
		assertTrue(walker instanceof MmtrConsistWalker, "夹具应是编组走行体");
		final MmtrConsistWalker consist = (MmtrConsistWalker) walker;

		// 默认 CAB_A：**A 端在前**（有人那端就是前端）⇒ 裸端点与行车方向相反，必须换过来读。
		assertFalse(consist.travelsTowardB(), "默认 CAB_A 时 A 端在前");
		assertEquals(walker.enteredFromPosition(), MmtrRunPlanner.travelAheadNode(walker), "A 端在前：前方节点 = enteredFromPosition");
		assertEquals(walker.aheadNode(), MmtrRunPlanner.travelEntryNode(walker), "A 端在前：来向节点 = aheadNode");
		assertNotEquals(walker.aheadNode(), MmtrRunPlanner.travelAheadNode(walker), "A 端在前时裸 aheadNode 是反的 —— 这就是那个 bug");

		// 换端：B 端在前 ⇒ 裸端点就是行车方向，travel 口径与它们一致。
		assertTrue(v.changeEndsMmtrMotion(), "换端要成功");
		assertTrue(consist.travelsTowardB(), "换端后 B 端在前");
		assertEquals(walker.aheadNode(), MmtrRunPlanner.travelAheadNode(walker), "B 端在前：前方节点 = aheadNode");
		assertEquals(walker.enteredFromPosition(), MmtrRunPlanner.travelEntryNode(walker), "B 端在前：来向节点 = enteredFromPosition");
	}
}
