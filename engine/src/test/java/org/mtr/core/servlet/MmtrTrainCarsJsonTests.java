package org.mtr.core.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.signal.MmtrSectionService;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **`mmtr-trains` 的逐节数据 `cars[]`**：一节车一个记号那条前端功能（notes/173）全靠它。
 *
 * <h2>为什么要有这条用例</h2>
 * <p>前端把"一节车一个记号"画出来，前提是每节车的 {@code railHex + railArcM + railArcLengthM + forward}
 * 都对。而这几个数在活页面上**几乎验不了**：dev 世界里 7 辆车全是同一个 16 米车型的单节编组，
 * 一节与八节看起来一样；真要验到"编组里第二节在第三节前面 17 米"这种量，必须**自己造一列八节编组**。</p>
 *
 * <p>判据是**几何的、与实现无关的两条**（notes/170 就是靠这种"另一条判据"才逮到弧空间翻错）：</p>
 * <ol>
 *   <li><b>链</b>：相邻两节车的世界距离必须 ≈ 两节车长之半的和（编组是背靠背连着的）。
 *       弧空间没翻对时，那一节会整整偏一个轨长（17–43 米），这条立刻红；</li>
 *   <li><b>头</b>：接口自己的 {@code headX/headZ}（那个字段已被 notes/170 独立验过）必须落在**某一节车的车身内**。</li>
 * </ol>
 * <p>世界坐标用 {@code rail.railMath.getPosition(arc)} 反算 —— 与引擎报的弧长同一套几何，但没有复用
 * `mmtrCarJson` 的任何一行代码。世界存档不在时整条跳过。</p>
 */
public final class MmtrTrainCarsJsonTests {

	/** 引擎工程目录（Gradle 跑测试时的当前目录）下的真实 dev 世界。**相对路径**：这里不许写死机器路径。 */
	private static final Path DEV_WORLD_MTR_ROOT = Paths.get("../game/fabric/run/world/mtr");
	private static final String DIMENSION = "minecraft/overworld";
	/** 那条能停下 132 米编组的长股道（与 `MmtrConsistMultiCarPlacementTests` 同一个）。 */
	private static final long LONG_YARD_SIDING_ID = -5385228036074278397L;
	/** 车长之和的容差（米）：引擎把米数按两位数发出来。 */
	private static final double LENGTH_TOLERANCE = 0.05;

	@Test
	public void anEightCarConsistGetsEightPlacementsOnTheRightRails() {
		Assumptions.assumeTrue(Files.isDirectory(DEV_WORLD_MTR_ROOT), "live dev world not present - skipping");
		final Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, DEV_WORLD_MTR_ROOT, false);
		final Vehicle vehicle = spawnEightCarConsist(simulator);
		assertNotNull(vehicle, "consist vehicle spawned");

		final JsonObject payload = SystemMapServlet.FEEDS.get("mmtr-trains").builder().apply(simulator);
		final JsonObject train = findTrain(payload.getAsJsonArray("trains"), String.valueOf(vehicle.getId()));
		assertNotNull(train, "the spawned consist must show up in mmtr-trains");

		// 每辆车都必须有逐节数据 —— 前端"没有 cars 就退回车头一节"那条退路，在**新引擎上不该被走到**
		final JsonArray cars = train.getAsJsonArray("cars");
		final var vehicleCars = vehicle.getVehicleCarsAndPositions();
		assertNotNull(cars, "the train must carry a cars array");
		assertEquals(vehicleCars.size(), cars.size(), "one entry per car of the consist");

		final Map<String, Rail> railsByCanonicalHex = new HashMap<>();
		for (final Rail rail : simulator.rails) {
			railsByCanonicalHex.put(MmtrSectionService.canonicalHex(rail.getHexId()), rail);
		}

		final double[] centreX = new double[cars.size()];
		final double[] centreZ = new double[cars.size()];
		double totalLength = 0;
		double longestCar = 0;
		for (int i = 0; i < cars.size(); i++) {
			final JsonObject car = cars.get(i).getAsJsonObject();
			final VehicleCar declared = vehicleCars.get(i).left();
			assertEquals(i, car.get("index").getAsInt(), "index must be the position in the consist, from the A end");

			// 车长与动力标记：前端画"圆角箭头 / 矩形"就看 powered（用户 2026-09-16 的形状规则）
			assertEquals(declared.getLength(), car.get("lengthM").getAsDouble(), LENGTH_TOLERANCE, "car " + i + " length");
			assertEquals(declared.getMmtrPowered(), car.get("powered").getAsBoolean(), "car " + i + " powered flag");
			assertEquals(declared.getCapacity(), car.get("capacity").getAsLong(), "car " + i + " capacity");
			assertEquals(declared.getVehicleId(), car.get("stockId").getAsString(), "car " + i + " stock id");
			totalLength += declared.getLength();
			longestCar = Math.max(longestCar, declared.getLength());

			// 所在轨：必须是世界里真有的那根，且报的轨长就是那根轨的几何长度
			final String railHex = car.get("railHex").getAsString();
			assertEquals(railHex, MmtrSectionService.canonicalHex(railHex), "car " + i + " rail hex must be the canonical form");
			final Rail rail = railsByCanonicalHex.get(railHex);
			assertNotNull(rail, "car " + i + " must sit on a rail of the world");
			final double railLength = rail.railMath.getLength();
			assertEquals(railLength, car.get("railArcLengthM").getAsDouble(), 0.1, "car " + i + " rail length");
			final double arc = car.get("railArcM").getAsDouble();
			assertTrue(arc >= -0.01 && arc <= railLength + 0.01, "car " + i + " arc " + arc + " must be within rail 0.." + railLength);

			// 世界坐标：**反算**，只用来验位置对不对
			final Vector centre = rail.railMath.getPosition(Math.max(0, Math.min(railLength, arc)), false);
			centreX[i] = centre.x();
			centreZ[i] = centre.z();
		}
		assertTrue(totalLength > 100, "the eight car consist should be longer than 100 m, got " + totalLength);

		// ① 链：相邻两节背靠背 ⇒ 中心距 ≈ 两节车长之半的和（弧空间翻错会差一个整轨长）
		final StringBuilder gaps = new StringBuilder();
		for (int i = 1; i < cars.size(); i++) {
			final double expected = (vehicleCars.get(i - 1).left().getLength() + vehicleCars.get(i).left().getLength()) / 2;
			final double actual = Math.hypot(centreX[i] - centreX[i - 1], centreZ[i] - centreZ[i - 1]);
			gaps.append(String.format("%.2f(≈%.2f) ", actual, expected));
			assertTrue(Math.abs(actual - expected) <= 4, "car " + (i - 1) + "→" + i + " centre distance " + actual + " m, expected about " + expected + " m");
		}

		// ② 头：接口自己的 headX/headZ 必须落在某一节车的车身里
		assertTrue(train.has("headX") && train.has("headZ"), "the train must carry headX/headZ - that is the independent reference for the head check");
		final double headX = train.get("headX").getAsDouble();
		final double headZ = train.get("headZ").getAsDouble();
		double nearest = Double.MAX_VALUE;
		int nearestIndex = -1;
		for (int i = 0; i < cars.size(); i++) {
			final double distance = Math.hypot(headX - centreX[i], headZ - centreZ[i]);
			if (distance < nearest) {
				nearest = distance;
				nearestIndex = i;
			}
		}
		assertTrue(nearest <= longestCar / 2 + 1, "the head must sit inside a car body: nearest car " + nearestIndex + " is " + nearest + " m away");
		System.out.println("[CARS] 8 节编组：总长 " + String.format("%.1f", totalLength) + " m；头在车 " + nearestIndex + " 里（" + String.format("%.2f", nearest) + " m）；"
			+ "相邻中心距 " + gaps.toString().trim());
	}

	/** 与 `MmtrConsistMultiCarPlacementTests` 同一套：把一列 8 节编组摆到长股道上。 */
	private static Vehicle spawnEightCarConsist(Simulator simulator) {
		Siding siding = null;
		for (final Siding candidate : simulator.sidings) {
			if (candidate.getId() == LONG_YARD_SIDING_ID) {
				siding = candidate;
				break;
			}
		}
		assertNotNull(siding, "the long siding must exist");

		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("hst_h", 15, 5, 400, -5, 5, 0, 0));
		for (int i = 0; i < 6; i++) {
			cars.add(new VehicleCar("hst_b", 17, 5, 400, -5, 5, 0, 0));
		}
		cars.add(new VehicleCar("hst_h_rev", 15, 5, 400, -5, 5, 0, 0));
		siding.setVehicleCars(cars);
		siding.clearParkedVehicles();

		final MmtrConsistWalker walker = siding.mmtrConsistWalkerFromYard(null, null, null);
		if (walker == null) {
			return null;
		}
		return siding.spawnMmtrConsistVehicle(walker, MmtrCabState.Cab.CAB_A);
	}

	private static JsonObject findTrain(JsonArray trains, String vehicleId) {
		if (trains == null) {
			return null;
		}
		for (int i = 0; i < trains.size(); i++) {
			final JsonObject train = trains.get(i).getAsJsonObject();
			if (vehicleId.equals(train.get("vehicleId").getAsString())) {
				return train;
			}
		}
		return null;
	}
}
