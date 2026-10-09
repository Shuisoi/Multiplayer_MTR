package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 自建的"长车场股道"夹具：一条 170 米的直线股道 + 一个能停整列 8 节编组（132 米）的车场。
 *
 * <h2>为什么要有这个夹具</h2>
 * <p>2026-10 之前，长编组的几条用例（{@code MmtrConsistMultiCarPlacementTests} /
 * {@code MmtrTrainCarsJsonTests}）是**搭在 dev 世界那条 {@code aassdd} 长股道上**跑的：它们打开
 * {@code ../game/fabric/run/world/mtr}，按 id 找股道。那条股道在 2026-10 的改造里没了，于是三条用例
 * 变成"夹具不存在"的红 —— 例子的**判据**没问题，坏的是**它挂靠别人的世界**这件事本身。</p>
 *
 * <p>现在改成自建：每次构造都先把 {@code build/<名字>} 里的存档清掉（不残留上一次跑的
 * {@code mmtr-points.json} / 车辆），再现场摆一条直线股道。用例既不再依赖 dev 世界，也不再依赖
 * 上一次运行留下的东西 —— 判据一条没放松。</p>
 */
public final class MmtrLongYardSidingFixture {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String DIMENSION = "minecraft/overworld";
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	/** 股道缓冲端。 */
	public static final Position YARD_BACK = new Position(-170, 0, 0);
	/** 股道道岔口（另一端）。 */
	public static final Position YARD_MOUTH = new Position(0, 0, 0);
	/** 股道长度（米）：8 节编组 132 米，留出 38 米余量。 */
	public static final double YARD_LENGTH_M = 170;

	public final Simulator simulator;
	public final Rail yardRail;
	public final Depot depot;
	public final Siding siding;

	public MmtrLongYardSidingFixture(String savePath) {
		wipe(savePath);
		simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, Paths.get(savePath), false);
		yardRail = Rail.newSidingRail(YARD_BACK, Angle.E, YARD_MOUTH, Angle.E, Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
		depot = new Depot(TransportMode.TRAIN, simulator);
		siding = new Siding(YARD_BACK, YARD_MOUTH, YARD_LENGTH_M, TransportMode.TRAIN, simulator);
		depot.setName("Long Yard");
		depot.setCorners(new Position(-175, -5, -5), new Position(5, 5, 5));
		simulator.rails.add(yardRail);
		simulator.depots.add(depot);
		simulator.sidings.add(siding);
		simulator.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
		simulator.mmtrDefaultConsistTypeId = "emu";
		siding.setVehicleCars(eightCarConsistCars());
		simulator.sync();
		siding.tick();
	}

	/** 8 节 132 米编组：头车 15 m、6 节 17 m 中间车、尾车 15 m（与现场 HST 编组同形）。 */
	public static ObjectArrayList<VehicleCar> eightCarConsistCars() {
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("hst_h", 15, 5, 400, -5, 5, 0, 0));
		for (int i = 0; i < 6; i++) {
			cars.add(new VehicleCar("hst_b", 17, 5, 400, -5, 5, 0, 0));
		}
		cars.add(new VehicleCar("hst_h_rev", 15, 5, 400, -5, 5, 0, 0));
		return cars;
	}

	/** 把整列编组摆上这条股道并生成编组体车（与现场停车方式相同：A 端朝道岔口，整列在缓冲端一侧）。 */
	public Vehicle spawnEightCarConsist() {
		siding.setVehicleCars(eightCarConsistCars());
		siding.clearParkedVehicles();
		final MmtrConsistWalker walker = siding.mmtrConsistWalkerFromYard(null, null, null);
		assertNotNull(walker, "the 132 m consist body must fit the " + YARD_LENGTH_M + " m yard rail (rail length "
			+ String.format("%.1f", yardRail.railMath.getLength()) + " m)");
		final Vehicle vehicle = siding.spawnMmtrConsistVehicle(walker, MmtrCabState.Cab.CAB_A);
		assertNotNull(vehicle, "consist vehicle spawned");
		return vehicle;
	}

	private static void wipe(String savePath) {
		final Path path = Paths.get(savePath);
		if (!Files.exists(path)) {
			return;
		}
		try (final Stream<Path> walk = Files.walk(path)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException e) {
			throw new IllegalStateException("cannot wipe test save dir " + savePath, e);
		}
	}
}
