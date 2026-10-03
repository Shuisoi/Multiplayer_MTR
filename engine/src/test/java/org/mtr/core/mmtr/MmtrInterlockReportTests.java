package org.mtr.core.mmtr;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrMissionControl;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 联锁诊断 report the OP command {@code interlock} prints: route state and reason, outstanding
 * turnouts with their authority state, the aspect of every rail of the route, and the narrowing that
 * was mirrored to clients. This is what the in-game verification pass compares the lights against.
 */
public final class MmtrInterlockReportTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	@Test
	public void theReportDescribesRouteTurnoutsAspectsAndMirror() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-interlock-report"), false);
		final Position yardBack = new Position(-32, 0, 0);
		final Position mouth = new Position(-20, 0, 0);
		final Position node60 = new Position(60, 0, 0);
		final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), mouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Rail rX = through(mouth, node60);
		final Rail rY = through(mouth, new Position(60, 0, 14));
		final Rail rP = Rail.newPlatformRail(node60, Angle.fromAngle(0), new Position(140, 0, 0), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding = new Siding(yardBack, mouth, 12, TransportMode.TRAIN, sim);
		final Station station = new Station(sim);
		final Platform platform = new Platform(node60, new Position(140, 0, 0), TransportMode.TRAIN, sim);
		depot.setName("Yard");
		depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
		station.setName("North");
		station.setCorners(new Position(50, -5, -5), new Position(150, 5, 5));
		sim.rails.add(yardRail);
		sim.rails.add(rX);
		sim.rails.add(rY);
		sim.rails.add(rP);
		sim.depots.add(depot);
		sim.sidings.add(siding);
		sim.stations.add(station);
		sim.platforms.add(platform);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		siding.setVehicleCars(cars);
		sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
		sim.mmtrDefaultConsistTypeId = "emu";
		sim.sync();
		siding.tick();

		final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
		assertNotNull(walker, "yard walker must resolve");
		final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
		assertNotNull(vehicle, "motion seam must spawn");

		// No mission yet: the report says so and explains the free-driving display rule.
		final String before = MmtrInterlockReport.describe(sim, vehicle.getId());
		assertTrue(before.contains("route=none"), "no route yet: " + before);
		assertTrue(before.contains("自由驾驶"), "the report explains the fallback rule: " + before);

		final JsonObject json = new JsonObject();
		json.addProperty("vehicleId", String.valueOf(vehicle.getId()));
		json.addProperty("kind", "PASSENGER");
		json.addProperty("targetSidingId", String.valueOf(platform.getId()));
		json.addProperty("executor", "AUTOPILOT");
		json.addProperty("startNow", true);
		assertTrue(new MmtrMissionControl(new JsonReader(json)).dispatch(sim), "mission dispatch must succeed");
		siding.simulateVehicles(1000, null);

		final String report = MmtrInterlockReport.describe(sim, vehicle.getId());
		assertTrue(report.contains("route=MAIN/SET"), "route state is reported: " + report);
		assertTrue(report.contains("turnouts:"), "the outstanding turnouts are listed: " + report);
		assertTrue(report.contains("wantLeg="), "each turnout shows the leg the route needs: " + report);
		assertTrue(report.contains("holder=") || report.contains("lock="), "the authority state is shown: " + report);
		assertTrue(report.contains("aspects:"), "the aspects of the route's rails are listed: " + report);
		assertTrue(report.contains("mirror(客户端收窄):"), "the mirrored narrowing is listed: " + report);
		assertTrue(report.contains("->"), "the mirror shows rail -> next: " + report);
		// T5：计划/实际对照必须在报告里。这一条用的是 mission dispatch（没有任务时刻），
		// 所以它应当明确说"无计划 ⇒ 按到达序裁决"，而不是留白让人猜。
		assertTrue(report.contains("计划/实际:"), "计划/实际对照出现在报告里: " + report);
		assertTrue(report.contains("无计划"), "没有任务时刻 ⇒ 报告要说清是无计划: " + report);

		final String all = MmtrInterlockReport.describeAll(sim);
		assertTrue(all.contains("1 条进路"), "describeAll counts the live routes: " + all);
		assertTrue(all.contains(String.valueOf(vehicle.getId())), "describeAll names the train: " + all);
		assertTrue(all.contains("计划/实际:"), "T5 describeAll 每一行也带计划/实际对照: " + all);

		assertTrue(MmtrInterlockReport.describe(sim, 404L).contains("找不到车辆"), "unknown ids are reported, not thrown");
	}
}
