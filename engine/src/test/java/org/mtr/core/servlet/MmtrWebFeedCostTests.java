package org.mtr.core.servlet;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **量一量网页每一路接口现在多贵**（notes/172）。
 *
 * <h2>为什么要有这条用例</h2>
 * <p>"网页刷新会阻碍客户端动作"这件事此前只有浏览器侧的数字（单次响应 45–112 ms，服务端日志
 * `Can't keep up!`），那个数字里混着"等下一次 tick"的时间，**分不清"算得慢"与"排队等"**。
 * 这一条把两者分开：它只量**构建一份 JSON 本身**要多少毫秒，并且冷启动（JIT 还没编译到这条路径）
 * 与稳定态分开记 —— 只读接口的代码平时根本不执行，冷热差一个数量级是常态。</p>
 *
 * <p>它不判死活（性能数字会随机器与 JIT 抖动），只**打印**；世界存档不在时整条跳过。
 * 表的用法是：哪一路最贵、贵在什么规模（rails/lamps/points 一起打出来），
 * 于是"要不要把它再拆薄一点"是有数字的，而不是猜的。</p>
 */
public final class MmtrWebFeedCostTests {

	/** 引擎工程目录（Gradle 跑测试时的当前目录）下的真实 dev 世界：运行中那份最接近实机。 */
	private static final Path LIVE_WORLD = Paths.get("../game/fabric/run/world/mtr");
	/** 上一份存档（更小，49 轨）：运行中那份读不到时的退化目标。 */
	private static final Path SAVED_WORLD = Paths.get("../game/fabric/run/saves/新的世界/mtr");
	private static final String DIMENSION = "minecraft/overworld";

	private static final int WARMUP_ROUNDS = 3;
	private static final int TIMED_ROUNDS = 5;

	@Test
	public void printWhatEachConsoleEndpointCosts() {
		final Path root = Files.isDirectory(LIVE_WORLD) ? LIVE_WORLD : Files.isDirectory(SAVED_WORLD) ? SAVED_WORLD : null;
		Assumptions.assumeTrue(root != null, "dev world save not present - skipping");

		final Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, root, false);
		assertTrue(simulator.rails.size() > 0, "dev world should have rails");
		assertFalse(SystemMapServlet.FEEDS.isEmpty(), "快照表不该是空的");

		System.out.println("[WEB-COST] world=" + root.toAbsolutePath().getFileName() + " rails=" + simulator.rails.size()
			+ " lamps=" + simulator.mmtrSignals.signals.size() + " platforms=" + simulator.platforms.size()
			+ " depots=" + simulator.depots.size() + " sidings=" + simulator.sidings.size());

		long coldTotal = 0;
		long warmTotal = 0;
		for (final Map.Entry<String, SystemMapServlet.FeedSpec> feed : SystemMapServlet.FEEDS.entrySet()) {
			final String endpoint = feed.getKey();
			final java.util.function.Function<Simulator, com.google.gson.JsonObject> builder = feed.getValue().builder();

			final long coldStart = System.nanoTime();
			final com.google.gson.JsonObject payload = builder.apply(simulator);
			final long coldMillis = (System.nanoTime() - coldStart) / 1_000_000L;

			long warmMax = 0;
			long warmSum = 0;
			int timed = 0;
			for (int round = 0; round < WARMUP_ROUNDS + TIMED_ROUNDS; round++) {
				final long start = System.nanoTime();
				builder.apply(simulator);
				final long millis = (System.nanoTime() - start) / 1_000_000L;
				if (round >= WARMUP_ROUNDS) {
					warmSum += millis;
					warmMax = Math.max(warmMax, millis);
					timed++;
				}
			}

			final int bytes = payload.toString().length();
			coldTotal += coldMillis;
			warmTotal += warmSum / Math.max(1, timed);
			System.out.println(String.format("[WEB-COST] %-22s cold=%4d ms  warmAvg=%4d ms  warmMax=%4d ms  json=%6d B   window=%d ms",
				endpoint, coldMillis, warmSum / Math.max(1, timed), warmMax, bytes, feed.getValue().maxAgeMillis()));
		}
		System.out.println("[WEB-COST] TOTAL cold=" + coldTotal + " ms  warm(one of each)=" + warmTotal + " ms"
			+ "  —— 每次刷新（4 路）在 tick 上大约就是 warm 里相应几路之和");
	}

	/**
	 * 把最贵那一路**拆开**：多少是"世界级"（跟车没关系）、多少是"每辆车"。
	 *
	 * <p>为什么要拆：`mmtr-trains` 一处 186 ms 就占掉三个多 tick，而"该拆薄哪一段"完全取决于这个比例 ——
	 * 世界级占多数 ⇒ 该做的是把同一份派生结论（全轨显示 / 区间视图）在一条请求里只算一次；
	 * 每辆车占多数 ⇒ 该做的是逐车那几段（许可、占用问三遍、弧长投影）。</p>
	 *
	 * <p>做法：同一份世界先量"带车"，再把车全部清掉（`clearVehicles` 只动内存，不写盘）量一次 ——
	 * 差值就是"每辆车"的部分，余数就是世界级的扫描。</p>
	 */
	@Test
	public void attributeTheCostOfTheHeaviestEndpoint() {
		final Path root = Files.isDirectory(LIVE_WORLD) ? LIVE_WORLD : Files.isDirectory(SAVED_WORLD) ? SAVED_WORLD : null;
		Assumptions.assumeTrue(root != null, "dev world save not present - skipping");

		final Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, root, false);
		final Function<Simulator, com.google.gson.JsonObject> trains = SystemMapServlet.FEEDS.get("mmtr-trains").builder();
		final Function<Simulator, com.google.gson.JsonObject> signals = SystemMapServlet.FEEDS.get("mmtr-signals").builder();

		final int[] vehicleCount = {0};
		final double[] authorityMillis = {0};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			vehicleCount[0]++;
			final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
			if (walker != null) {
				final long start = System.nanoTime();
				org.mtr.core.mmtr.signal.MmtrMovementAuthority.forVehicle(simulator, walker, vehicle.getId(), vehicle.getMmtrRoute());
				authorityMillis[0] += (System.nanoTime() - start) / 1_000_000.0;
			}
		}));

		final long withTrains = warmMillis(trains, simulator);
		final long signalsOnly = warmMillis(signals, simulator);
		/*
		 * 把"世界级"那 140 ms 再往下拆一层：全轨显示（aspectsForAllRails）与区间视图（sectionViews）
		 * 是两大嫌疑，两者都是"按世界规模扫一遍"的东西。
		 */
		final long aspectsOnly = warmMillis(world -> {
			final com.google.gson.JsonObject ignored = new com.google.gson.JsonObject();
			world.mmtrSignalAspectView().aspectsForAllRails().forEach((hex, aspect) -> ignored.addProperty(hex, aspect.name()));
			return ignored;
		}, simulator);
		final long sectionViewsOnly = warmMillis(world -> {
			final com.google.gson.JsonObject ignored = new com.google.gson.JsonObject();
			ignored.addProperty("count", world.mmtrSections.sectionViews(world.mmtrOccupancyTrees(), key -> false).size());
			return ignored;
		}, simulator);
		simulator.sidings.forEach(org.mtr.core.data.Siding::clearVehicles);
		final long withoutTrains = warmMillis(trains, simulator);

		final long perVehicle = vehicleCount[0] == 0 ? 0 : Math.max(0, withTrains - withoutTrains) / vehicleCount[0];
		System.out.println("[WEB-COST] mmtr-trains 拆解: vehicles=" + vehicleCount[0]
			+ " 带车=" + withTrains + " ms  清空车后=" + withoutTrains + " ms（世界级）"
			+ "  每辆车≈" + perVehicle + " ms"
			+ "  |  参考 mmtr-signals=" + signalsOnly + " ms"
			+ "  |  逐车 MmtrMovementAuthority 合计=" + Math.round(authorityMillis[0]) + " ms");
		System.out.println("[WEB-COST] 世界级两嫌疑: aspectsForAllRails=" + aspectsOnly + " ms  sectionViews=" + sectionViewsOnly + " ms");

		/*
		 * 再往里一层：`aspectsForAllRails` 是"每根轨 × 两个端点各走一次链深"，那链走行本身贵在哪？
		 * 同一个谓词换成恒 false（不查受限岔口）再走一遍，差值就是"查受限岔口"那一层的成本。
		 */
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		final long chainWithJunctions = warmMillis(world -> {
			final com.google.gson.JsonObject ignored = new com.google.gson.JsonObject();
			final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<String> restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(world, trees);
			ignored.addProperty("count", walkAllRailEnds(world, trees, restricted::contains));
			return ignored;
		}, simulator);
		final long chainWithoutJunctions = warmMillis(world -> {
			final com.google.gson.JsonObject ignored = new com.google.gson.JsonObject();
			ignored.addProperty("count", walkAllRailEnds(world, trees, key -> false));
			return ignored;
		}, simulator);
		System.out.println("[WEB-COST] 链走行本身: 318 次 chainDepth（带受限岔口判定）=" + chainWithJunctions
			+ " ms   （谓词恒 false）=" + chainWithoutJunctions + " ms");

		/*
		 * 再往里：一次 chainDepth 里只有四件事 —— refresh()（世界签名）、sectionProtecting、
		 * 每段的 isOccupied / boundaryNodeKeys（后者自己**又**调一次 refresh）、followings。
		 * 四次都按"318 个轨端"跑一遍，加起来该接近 chainDepth 的总耗时，多出来的那个就是主项。
		 */
		final long sectionsOfRailMillis = forAllRailEnds(simulator, (world, hex, end) -> world.mmtrSections.sectionsOfRail(hex).size());
		final long sectionProtectingMillis = forAllRailEnds(simulator, (world, hex, end) -> world.mmtrSections.sectionProtecting(hex, end) == null ? 0 : 1);
		final long boundaryNodeKeysMillis = forAllRailEnds(simulator, (world, hex, end) -> {
			final org.mtr.core.mmtr.signal.MmtrSectionService.Section section = world.mmtrSections.sectionProtecting(hex, end);
			return section == null ? 0 : world.mmtrSections.boundaryNodeKeys(section).size();
		});
		final long followingsMillis = forAllRailEnds(simulator, (world, hex, end) -> {
			final org.mtr.core.mmtr.signal.MmtrSectionService.Section section = world.mmtrSections.sectionProtecting(hex, end);
			return section == null ? 0 : world.mmtrSections.followings(section).size();
		});
		final long isOccupiedMillis = forAllRailEnds(simulator, (world, hex, end) -> {
			final org.mtr.core.mmtr.signal.MmtrSectionService.Section section = world.mmtrSections.sectionProtecting(hex, end);
			return section == null || !world.mmtrSections.isOccupied(section, trees, 0) ? 0 : 1;
		});
		System.out.println("[WEB-COST] 318 轨端各跑一次: sectionsOfRail=" + sectionsOfRailMillis
			+ " ms  sectionProtecting=" + sectionProtectingMillis
			+ " ms  boundaryNodeKeys=" + boundaryNodeKeysMillis
			+ " ms  followings=" + followingsMillis
			+ " ms  isOccupied=" + isOccupiedMillis + " ms");

		/*
		 * `mmtr-signals` 现在是活数据里最贵的一路（notes/172 在 A 之后重测）：它除了逐灯算显示，
		 * 还先算一次"哪些岔口净空守不住"（unclearedNodeKeys）—— 后者要逐节点查清限区，值得单独量。
		 */
		final long unclearedMillis = warmMillis(world -> {
			final com.google.gson.JsonObject ignored = new com.google.gson.JsonObject();
			ignored.addProperty("count", org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(world, world.mmtrOccupancyTrees()).size());
			return ignored;
		}, simulator);
		System.out.println("[WEB-COST] unclearedNodeKeys=" + unclearedMillis + " ms（mmtr-signals 每次都要先算一遍）");
	}

	/** 对一个"轨端"做一件事（返回一个数，免得 JIT 把整段消掉）。 */
	private interface RailEndVisitor {
		long visit(Simulator world, String railHex, Position end);
	}

	/** 对每根轨的两个端点各跑一次 {@code visitor}，返回总毫秒。 */
	private static long forAllRailEnds(Simulator world, RailEndVisitor visitor) {
		final long start = System.nanoTime();
		long sink = 0;
		for (final org.mtr.core.data.Rail rail : world.rails) {
			final String hex = org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(rail.getHexId());
			for (final Position end : rail.mmtrOrderedPositions()) {
				sink += visitor.visit(world, hex, end);
			}
		}
		return (System.nanoTime() - start) / 1_000_000L + (sink == Long.MIN_VALUE ? 1 : 0);
	}

	/** 对每根轨的两个端点各走一次链深（＝`aspectsForAllRails` 里真正在算的那件事）。 */
	private static long walkAllRailEnds(Simulator world, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		long depthSum = 0;
		for (final org.mtr.core.data.Rail rail : world.rails) {
			final String hex = org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(rail.getHexId());
			for (final Position end : rail.mmtrOrderedPositions()) {
				depthSum += world.mmtrSections.chainDepth(hex, end, trees, restrictedNodes, 3, 0);
			}
		}
		return depthSum;
	}

	/** 三次取最小的那个（最小比平均更接近"没有别的活儿在抢 CPU"的那一次）。 */
	private static long warmMillis(Function<Simulator, com.google.gson.JsonObject> builder, Simulator simulator) {
		long best = Long.MAX_VALUE;
		for (int round = 0; round < 3; round++) {
			final long start = System.nanoTime();
			builder.apply(simulator);
			best = Math.min(best, (System.nanoTime() - start) / 1_000_000L);
		}
		return best;
	}

	/** 与 `DevA2RealGraphTests` 同一份最小编组类型（现场是 6 台车，见下一条用例）。 */
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";

	/**
	 * **有车版**：用户现场是 6 台车，而 dev 存档在磁盘上那份**一辆车都没有** ——
	 * 所以"网页刷新到底多贵"必须按有车量一次。这里照 `DevA2RealGraphTests` 的做法在真实轨网上
	 * 铺 6 台车（每台写自己的占用足迹），再逐路量。
	 */
	@Test
	public void measureWithSixTrainsOnBoard() {
		final Path root = Files.isDirectory(LIVE_WORLD) ? LIVE_WORLD : Files.isDirectory(SAVED_WORLD) ? SAVED_WORLD : null;
		Assumptions.assumeTrue(root != null, "dev world save not present - skipping");

		final Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, root, false);
		simulator.mmtrConsistTypes = org.mtr.core.mmtr.ConsistTypeRegistry.parse(CONSIST_JSON);
		simulator.mmtrDefaultConsistTypeId = "emu";

		final int[] onDisk = {0};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> onDisk[0]++));

		final ObjectArrayList<org.mtr.core.data.VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new org.mtr.core.data.VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		final int[] spawned = {0};
		for (final org.mtr.core.data.Siding siding : simulator.sidings) {
			if (spawned[0] >= 6) {
				break;
			}
			siding.setVehicleCars(cars);
			final org.mtr.core.mmtr.segment.MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, simulator.mmtrPointBranches, null);
			if (walker != null && siding.spawnMmtrMotionVehicle(walker) != null) {
				spawned[0]++;
			}
		}
		Assumptions.assumeTrue(spawned[0] > 0, "no real siding could take a probe train - skipping");

		// 让每台车把自己的占用足迹写进占用树（现场是 ticks 跑着的世界）。
		for (int round = 0; round < 5; round++) {
			simulator.sidings.forEach(siding -> siding.simulateVehicles(1_000L, simulator.mmtrOccupancyTrees()));
		}

		final int[] footprints = {0};
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		if (trees != null) {
			trees.forEach(tree -> tree.forEach((position, inner) -> footprints[0] += inner.size()));
		}

		System.out.println("[WEB-COST-CARS] world=" + root.toAbsolutePath().getFileName()
			+ " 盘上有车=" + onDisk[0] + " 本次铺了=" + spawned[0] + " 占用足迹=" + footprints[0]);

		for (final Map.Entry<String, SystemMapServlet.FeedSpec> feed : SystemMapServlet.FEEDS.entrySet()) {
			final long millis = warmMillis(feed.getValue().builder(), simulator);
			System.out.println(String.format("[WEB-COST-CARS] %-22s warmMin=%4d ms", feed.getKey(), millis));
		}

		// 逐车那几段（有车时才存在）：许可对象是每车一次。
		final long[] authorityNanos = {0};
		final int[] walked = {0};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
			if (walker != null) {
				final long start = System.nanoTime();
				org.mtr.core.mmtr.signal.MmtrMovementAuthority.forVehicle(simulator, walker, vehicle.getId(), vehicle.getMmtrRoute());
				authorityNanos[0] += System.nanoTime() - start;
				walked[0]++;
			}
		}));
		System.out.println("[WEB-COST-CARS] 逐车 MmtrMovementAuthority: " + walked[0] + " 台合计 "
			+ (authorityNanos[0] / 1_000_000L) + " ms"
			+ "  |  aspectsForAllRails=" + warmMillis(world -> {
			final com.google.gson.JsonObject ignored = new com.google.gson.JsonObject();
			world.mmtrSignalAspectView().aspectsForAllRails().forEach((hex, aspect) -> ignored.addProperty(hex, aspect.name()));
			return ignored;
		}, simulator) + " ms");
	}

	/**
	 * **车辆同步那一份的字节数与 CPU**（不是网页，是 64 人真正的下行走量）。
	 *
	 * <p>要量的两件事：①一次 {@code VehicleExtraData.copy()} 多少钱（它现在是 **JSON 反射序列化 +
	 * 重新解析**，而且在"每辆车 × 每个可见客户端"的循环里被调用）；②一份 {@code VehicleUpdate}
	 * 序列化出来多少**字节**（上行下行都是 JSON 文本）。</p>
	 */
	@Test
	public void measureVehicleSyncPayload() {
		final Path root = Files.isDirectory(LIVE_WORLD) ? LIVE_WORLD : Files.isDirectory(SAVED_WORLD) ? SAVED_WORLD : null;
		Assumptions.assumeTrue(root != null, "dev world save not present - skipping");

		final Simulator simulator = new Simulator(DIMENSION, new String[]{DIMENSION}, root, false);
		simulator.mmtrConsistTypes = org.mtr.core.mmtr.ConsistTypeRegistry.parse(CONSIST_JSON);
		simulator.mmtrDefaultConsistTypeId = "emu";

		final ObjectArrayList<org.mtr.core.data.VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new org.mtr.core.data.VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
		final ObjectArrayList<org.mtr.core.data.Vehicle> vehicles = new ObjectArrayList<>();
		for (final org.mtr.core.data.Siding siding : simulator.sidings) {
			if (vehicles.size() >= 6) {
				break;
			}
			siding.setVehicleCars(cars);
			final org.mtr.core.mmtr.segment.MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, simulator.mmtrPointBranches, null);
			if (walker == null) {
				continue;
			}
			final org.mtr.core.data.Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			if (vehicle != null) {
				vehicles.add(vehicle);
			}
		}
		Assumptions.assumeTrue(!vehicles.isEmpty(), "no real siding could take a probe train - skipping");
		for (int round = 0; round < 5; round++) {
			simulator.sidings.forEach(siding -> siding.simulateVehicles(1_000L, simulator.mmtrOccupancyTrees()));
		}

		final org.mtr.core.data.Vehicle vehicle = vehicles.getFirst();

		// ① copy() 的单价（模拟"每辆车 × 每个可见客户端"里的那一次）
		for (int i = 0; i < 20; i++) {
			vehicle.vehicleExtraData.copy(0);
		}
		final long start = System.nanoTime();
		final int copyRounds = 200;
		for (int i = 0; i < copyRounds; i++) {
			vehicle.vehicleExtraData.copy(0);
		}
		final long copyMicros = (System.nanoTime() - start) / 1_000L / copyRounds;

		// ② 一份 VehicleUpdate 的字节（走的是包体那条路：JsonObject -> JSON 字符串）
		final org.mtr.core.operation.VehicleUpdate vehicleUpdate = new org.mtr.core.operation.VehicleUpdate(vehicle, vehicle.vehicleExtraData.copy(0));
		final String json = org.mtr.core.tool.Utilities.getJsonObjectFromData(vehicleUpdate).toString();

		final long serializeStart = System.nanoTime();
		final int serializeRounds = 100;
		for (int i = 0; i < serializeRounds; i++) {
			org.mtr.core.tool.Utilities.getJsonObjectFromData(vehicleUpdate).toString();
		}
		final long serializeMicros = (System.nanoTime() - serializeStart) / 1_000L / serializeRounds;

		int pathEntries = 0;
		int ridingEntries = 0;
		int carEntries = 0;
		try {
			final com.google.gson.JsonObject data = org.mtr.core.tool.Utilities.getJsonObjectFromData(vehicle.vehicleExtraData);
			pathEntries = data.has("path") ? data.getAsJsonArray("path").size() : 0;
			ridingEntries = data.has("ridingEntities") ? data.getAsJsonArray("ridingEntities").size() : 0;
			carEntries = data.has("vehicleCars") ? data.getAsJsonArray("vehicleCars").size() : 0;
		} catch (Exception e) {
			System.out.println("[VEH-SYNC] 读字段失败: " + e.getMessage());
		}

		System.out.println("[VEH-SYNC] VehicleExtraData.copy(0)=" + copyMicros + " µs/次（每车 × 每个可见客户端各一次）"
			+ "  |  VehicleUpdate 的 JSON=" + json.length() + " 字符（≈" + json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " B）"
			+ "  序列化=" + serializeMicros + " µs/次"
			+ "  |  path 条目=" + pathEntries + " ridingEntities=" + ridingEntries + " vehicleCars=" + carEntries);

		/*
		 * 稀疏补丁 vs 整份快照（notes/173）：一整份实测 3.5 KB，而"停车点变了"这一拍只需要几个字节。
		 * 这里量的是**真实车辆**上的同一次变化走两条路各要多少字符。
		 */
		final com.google.gson.JsonObject full = new com.google.gson.JsonObject();
		full.add("vehicle", org.mtr.core.tool.Utilities.getJsonObjectFromData(vehicle));
		full.add("data", org.mtr.core.tool.Utilities.getJsonObjectFromData(vehicle.vehicleExtraData));
		final com.google.gson.JsonObject changed = full.deepCopy();
		changed.getAsJsonObject("data").addProperty("stoppingPoint", 1234.5);
		final com.google.gson.JsonObject patch = org.mtr.core.operation.VehicleSyncPatch.patchOf(full, changed);
		final String patchJson = patch == null ? "(null: 需要整份)" : patch.toString();
		System.out.println("[VEH-SYNC] 同一次「停车点变了」: 整份快照=" + full.toString().length() + " 字符  vs  稀疏补丁="
			+ patchJson.length() + " 字符  |  补丁内容=" + patchJson);
	}
}
