package org.mtr.core.mmtr.command;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.manifest.MmtrRollingStockManifest;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vehicle remove --depot=<车辆段>}：**按车辆段删 = 该段每一条股道都要扫**。
 *
 * <p>修前的行为（notes/136 §2 的现场陷阱）：{@code --depot} 走 {@code resolveSiding}，而那个方法给
 * {@code --depot} 的语义是"这个段里的第 index 条股道"（给 {@code vehicle spawn} 用的：一条股道同时
 * 只停一组车）。于是"按车辆段删"实际上**只清了一条股道**，而用法文字与结果行都让人以为删干净了
 * ——现场（车场 {@code aassdd}）两辆车只删掉一辆，剩下那辆只能再按 {@code --siding} 删一次。</p>
 *
 * <p>这里用两条股道、各停一组车来钉住那条承诺：一次 `--depot` 之后，**两条股道都空**。</p>
 */
public final class MmtrVehicleCommandTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 一个车辆段、两条股道、mouth 接正线 —— 这样两列车都能真正停在场里并被扫到。 */
	private static final class Net {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-vehicle-command"), false);
		final Depot depot = new Depot(TransportMode.TRAIN, sim);
		final Siding siding1;
		final Siding siding2;
		final Rail mouth;

		Net() {
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 15));
			sim.rails.add(Rail.newSidingRail(new Position(-32, 0, 0), Angle.fromAngle(0), new Position(-20, 0, 0), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN));
			sim.rails.add(Rail.newSidingRail(new Position(-32, 0, 10), Angle.fromAngle(0), new Position(-20, 0, 10), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN));
			mouth = through(new Position(-20, 0, 0), new Position(100, 0, 0));
			sim.rails.add(mouth);
			siding1 = new Siding(new Position(-32, 0, 0), new Position(-20, 0, 0), 12, TransportMode.TRAIN, sim);
			siding2 = new Siding(new Position(-32, 0, 10), new Position(-20, 0, 10), 12, TransportMode.TRAIN, sim);
			sim.depots.add(depot);
			sim.sidings.add(siding1);
			sim.sidings.add(siding2);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			siding1.tick();
			siding2.tick();
			final String car = "{\"vehicleId\":\"probe\",\"length\":2,\"width\":1,\"capacity\":10,\"bogie1Position\":0,\"bogie2Position\":1,\"couplingPadding1\":0.1,\"couplingPadding2\":0.1}";
			sim.mmtrRollingStock = MmtrRollingStockManifest.parse("{"
				+ "\"depots\":[{\"depotId\":\"" + depot.getId() + "\",\"name\":\"Yard\",\"sidings\":["
				+ "{\"sidingId\":\"" + siding1.getId() + "\",\"name\":\"1\",\"cars\":[" + car + "]},"
				+ "{\"sidingId\":\"" + siding2.getId() + "\",\"name\":\"2\",\"cars\":[" + car + "]}"
				+ "]}]}");
		}

		List<Long> populate() {
			sim.mmtrResetAndApplyRollingStock();
			siding1.simulateVehicles(1000, null);
			siding2.simulateVehicles(1000, null);
			final List<Long> ids = new ArrayList<>();
			siding1.iterateVehicles(v -> ids.add(v.getId()));
			siding2.iterateVehicles(v -> ids.add(v.getId()));
			return ids;
		}

		List<Vehicle> on(Siding siding) {
			final List<Vehicle> out = new ArrayList<>();
			siding.iterateVehicles(out::add);
			return out;
		}
	}

	/** 每条股道各一辆：`--depot` 之后两条都空（修前只清一条）。 */
	@Test
	public void removingByDepotClearsEverySidingInThatDepot() {
		final Net n = new Net();
		final List<Long> ids = n.populate();
		assertEquals(2, ids.size(), "两条股道各停一辆");
		assertEquals(1, n.on(n.siding1).size());
		assertEquals(1, n.on(n.siding2).size());

		final MmtrCommandDispatcher.Result result = MmtrCommandDispatcher.execute(n.sim, "vehicle remove --depot=Yard");
		assertTrue(result.lines.stream().anyMatch(line -> line.contains("2 条股道")),
			"结果行要说清扫了几条股道：" + result.lines);
		assertTrue(result.lines.stream().anyMatch(line -> line.contains("删掉 2 辆车")),
			"结果行要说清删了几辆：" + result.lines);

		assertEquals(0, n.on(n.siding1).size(), "第一条股道空了");
		assertEquals(0, n.on(n.siding2).size(), "第二条股道也空了（修前这里还留着一辆）");
		for (final long id : ids) {
			assertFalse(n.sim.mmtrFindVehicle(id) != null, "车辆 " + id + " 不该还能查到");
		}
	}

	/** 空车辆段不算失败：扫过 0 辆车也要有可读的回话（脚本里靠这句话判断）。 */
	@Test
	public void removingByDepotOnAnEmptyYardSaysSoInsteadOfFailing() {
		final Net n = new Net();
		final MmtrCommandDispatcher.Result result = MmtrCommandDispatcher.execute(n.sim, "vehicle remove --depot=Yard");
		assertTrue(result.ok, "空段不是错误");
		assertTrue(result.lines.stream().anyMatch(line -> line.contains("没有匹配到任何车辆")), "说清楚没车：" + result.lines);
	}

	/** {@code --siding} 仍然是"只清这一条股道"（语义不能被上面那条改动带偏）。 */
	@Test
	public void removingBySidingStillTouchesOnlyThatSiding() {
		final Net n = new Net();
		n.populate();

		final MmtrCommandDispatcher.Result result = MmtrCommandDispatcher.execute(n.sim, "vehicle remove --siding=" + n.siding1.getId());
		assertTrue(result.ok);
		assertEquals(0, n.on(n.siding1).size(), "点名的股道清了");
		assertEquals(1, n.on(n.siding2).size(), "没点名的股道原样不动");
	}

	/** 找错车辆段要说清楚，而不是静默删掉别的段。 */
	@Test
	public void anUnknownDepotIsReportedAndNothingIsDeleted() {
		final Net n = new Net();
		n.populate();

		final MmtrCommandDispatcher.Result result = MmtrCommandDispatcher.execute(n.sim, "vehicle remove --depot=Nope");
		assertFalse(result.ok, "找不到车辆段 = 失败");
		assertTrue(result.lines.stream().anyMatch(line -> line.contains("找不到车辆段")), result.lines.toString());
		assertEquals(1, n.on(n.siding1).size(), "什么都没删");
		assertEquals(1, n.on(n.siding2).size(), "什么都没删");
	}
}
