package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.command.MmtrCommandDispatcher;
import org.mtr.core.mmtr.crowd.MmtrCrowdSystem;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **车站人流系统**（{@code MmtrCrowdSystem} / {@code crowd …}）的口径钉死。
 *
 * <h3>为什么这些用例是"合同"而不是"覆盖率"</h3>
 * <p>这套系统的全部内容是**一个式子**和**四个不写**（关着不写 / 量化后才写 / 平滑不跳 / 事件要过期）。
 * 式子错了在画面上表现为"人多了少了"，看不出是哪一项错的 —— 所以每一项都在这里单独钉一次：
 * 曲线跟谁走、基准怎么乘、班次因子怎么归一、冲量什么时候来、事件什么时候消失。</p>
 *
 * <h3>时间怎么控制</h3>
 * <p>{@code sim.setGameTime(gameMillis, gameMillisPerDay, true)} 直接摆好"游戏内几点"，
 * 于是"早高峰 95% / 半夜 1%"是可断言的常数，而不是靠等。</p>
 */
public final class MmtrCrowdSystemTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final Path SAVE_ROOT = Paths.get("build/mmtr-crowd-system-tests");
	private static final long MILLIS_PER_GAME_DAY = 24L * 3600L * 1000L;
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	// ------------------------------------------------------------------ 夹具

	/**
	 * 两个车站、各一条站台轨（不连起来、也不跑车）—— 用来钉算式与落盘。
	 *
	 * <p>存档目录每个夹具一份并**开局删掉旧的 {@code mmtr-crowd-system.json}**：否则上一次跑留下的
	 * "系统开着 + 基准 30" 会被这一次读进来，用例之间互相污染（测试工作目录是跨次复用的）。</p>
	 */
	private static final class TwoStations {

		final Simulator sim;
		final Station alpha;
		final Station beta;
		final Platform alpha1;
		final Platform beta1;

		TwoStations(String name) {
			final Path root = SAVE_ROOT.resolve(name);
			deleteConfig(root);
			sim = new Simulator("test", new String[]{"test"}, root, false);
			alpha = new Station(sim);
			alpha.setName("Alpha");
			alpha.setCorners(new Position(-5, -5, -5), new Position(65, 5, 5));
			beta = new Station(sim);
			beta.setName("Beta");
			beta.setCorners(new Position(-5, -5, 95), new Position(65, 5, 105));
			sim.stations.add(alpha);
			sim.stations.add(beta);

			alpha1 = addPlatform(0);
			beta1 = addPlatform(100);
			sim.sync();
			assertNotNull(sim.platformIdMap.get(alpha1.getId()), "夹具：alpha1 必须在 platformIdMap 里");
			assertNotNull(sim.platformIdMap.get(beta1.getId()), "夹具：beta1 必须在 platformIdMap 里");

			// 早上 8 点（曲线 = 95%），后面每一条用例要换点就自己再设一次
			setHour(8);
		}

		private Platform addPlatform(int z) {
			sim.rails.add(Rail.newPlatformRail(new Position(0, 0, z), Angle.fromAngle(0), new Position(60, 0, z), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN));
			final Platform platform = new Platform(new Position(0, 0, z), new Position(60, 0, z), TransportMode.TRAIN, sim);
			sim.platforms.add(platform);
			return platform;
		}

		/** 把游戏内时刻摆到 {@code hour} 点整。 */
		void setHour(long hour) {
			sim.setGameTime(hour * 3600_000L, MILLIS_PER_GAME_DAY, true);
		}

		MmtrCrowdSystem system() {
			return sim.mmtrCrowdSystem;
		}

		long levelOf(Platform platform) {
			return platform.getCrowdLevel();
		}
	}

	/**
	 * 一列车 + 两个车站各一条站台轨 —— 用来钉"车停在站台上 ⇒ 冲量 + 这个站计一次班次"。
	 *
	 * <p>车不需要真的开过去：到站/出站那两条标记（{@code markStationArrival/Departure}）就是
	 * 引擎自己的事实（谁标的由 {@code MmtrStationSubTaskTests} 钉），这里只需要把它们摆出来。</p>
	 */
	private static final class VehicleNet {

		final Simulator sim;
		final Station alpha;
		final Station beta;
		final Depot depot;
		final Siding siding;
		final Rail platformRailA;
		final Rail platformRailB;
		final Platform platformA;
		final Platform platformB;

		VehicleNet() {
			final Path root = SAVE_ROOT.resolve("vehicles");
			deleteConfig(root);
			sim = new Simulator("test", new String[]{"test"}, root, false);
			depot = new Depot(TransportMode.TRAIN, sim);

			final Position yardBack = new Position(-32, 0, 0);
			final Position yardMouth = new Position(-20, 0, 0);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			final Rail sidingRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Rail approach = through(yardMouth, new Position(0, 0, 0));
			platformRailA = Rail.newPlatformRail(new Position(0, 0, 0), Angle.fromAngle(0), new Position(60, 0, 0), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			platformRailB = Rail.newPlatformRail(new Position(0, 0, 100), Angle.fromAngle(0), new Position(60, 0, 100), Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			platformA = new Platform(new Position(0, 0, 0), new Position(60, 0, 0), TransportMode.TRAIN, sim);
			platformB = new Platform(new Position(0, 0, 100), new Position(60, 0, 100), TransportMode.TRAIN, sim);

			alpha = new Station(sim);
			alpha.setName("Alpha");
			alpha.setCorners(new Position(-5, -5, -5), new Position(65, 5, 5));
			beta = new Station(sim);
			beta.setName("Beta");
			beta.setCorners(new Position(-5, -5, 95), new Position(65, 5, 105));
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));

			sim.rails.add(sidingRail);
			sim.rails.add(approach);
			sim.rails.add(platformRailA);
			sim.rails.add(platformRailB);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			sim.stations.add(alpha);
			sim.stations.add(beta);
			sim.platforms.add(platformA);
			sim.platforms.add(platformB);

			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			siding.tick();
			assertNotNull(sim.platformIdMap.get(platformA.getId()), "夹具：platformA 必须在 platformIdMap 里");
			assertNotNull(sim.platformIdMap.get(platformB.getId()), "夹具：platformB 必须在 platformIdMap 里");
			sim.setGameTime(3 * 3600_000L, MILLIS_PER_GAME_DAY, true);
		}

		/** 在车场那股道上生成一台车（走的是自动车的同一条缝）。 */
		Vehicle spawn() {
			final org.mtr.core.mmtr.segment.MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, null, null);
			assertNotNull(walker, "夹具：车场 walker 要能解析出来");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "夹具：自动车缝要能生成车");
			return vehicle;
		}

		/** 给这台车挂一条"目标 = 某站台"的任务，并按需要标上进站/出站。 */
		MmtrMission missionTo(Vehicle vehicle, Platform platform) {
			final MmtrMission mission = new MmtrMission(vehicle.getId(), MmtrMission.Kind.PASSENGER, siding.getId(), platform.getId(), 0L);
			assertTrue(vehicle.setMmtrMission(mission), "夹具：任务要能挂上车");
			return mission;
		}

		MmtrCrowdSystem system() {
			return sim.mmtrCrowdSystem;
		}
	}

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 存档里的配置在 {@code <savePath>/<dimension>/mmtr-crowd-system.json}（Simulator 的分维度目录）。 */
	private static Path configPath(Path root) {
		return root.resolve("test").resolve("mmtr-crowd-system.json");
	}

	private static void deleteConfig(Path root) {
		try {
			Files.deleteIfExists(configPath(root));
		} catch (Exception ignored) {
			// 删不掉也不该让用例红：后面每条用例自己会设成它需要的状态
		}
	}

	// ------------------------------------------------------------------ 默认与开关

	/** **默认关着，而且关着时一行都不写** —— 手填的客量不该被一个"新系统"悄悄接管。 */
	@Test
	public void disabledByDefaultWritesNothing() {
		final TwoStations n = new TwoStations("disabled");
		assertFalse(n.system().isEnabled(), "默认必须是关的");
		n.system().runNow(n.sim, true);
		assertEquals(0, n.levelOf(n.alpha1), "关着时站台客量原样（0）");
		assertEquals(0, n.levelOf(n.beta1), "关着时站台客量原样（0）");
		// 但"如果打开会是多少"要算得出来（crowd status 在关着时也报）
		assertEquals(95, n.system().decide(n.sim, n.alpha1).target(), "8 点 ⇒ 通勤曲线 95%");
	}

	/** 打开 ⇒ 立刻落到当前曲线的值（不是从 0 慢慢涨），而且**量化档**生效。 */
	@Test
	public void enabledSnapsToTheCurveValue() {
		final TwoStations n = new TwoStations("enabled");
		n.system().setStep(1);
		n.system().setEnabled(true);
		n.system().runNow(n.sim, true);
		assertEquals(95, n.levelOf(n.alpha1), "8 点 ⇒ 95%");
		assertEquals(95, n.levelOf(n.beta1), "两条站台同一个站/同一档 ⇒ 同值");
	}

	/** 曲线跟着**游戏内小时**走：半夜 3 点几乎没人，早高峰 8 点最挤。 */
	@Test
	public void curveFollowsTheWorldClock() {
		final TwoStations n = new TwoStations("clock");
		n.system().setStep(1);
		n.system().setEnabled(true);
		n.system().runNow(n.sim, true);
		assertEquals(95, n.levelOf(n.alpha1), "8 点 = 95%");

		n.setHour(3);
		n.system().runNow(n.sim, true);
		assertEquals(1, n.levelOf(n.alpha1), "3 点 = 1%");

		n.setHour(18);
		n.system().runNow(n.sim, true);
		assertEquals(95, n.levelOf(n.alpha1), "18 点晚高峰 = 95%");
	}

	/** 基准**乘**在曲线上；站台覆盖压过车站覆盖。 */
	@Test
	public void baseScalesTheCurveAndPlatformBeatsStation() {
		final TwoStations n = new TwoStations("base");
		n.system().setStep(1);
		n.system().setStationBase(n.alpha.getId(), 50);
		assertEquals(48, n.system().decide(n.sim, n.alpha1).target(), "95% × 50% = 47.5 ⇒ 48");
		assertEquals(95, n.system().decide(n.sim, n.beta1).target(), "没设的车站不受影响");

		n.system().setPlatformBase(n.alpha1.getId(), 100);
		assertEquals(95, n.system().decide(n.sim, n.alpha1).target(), "站台覆盖压过车站基准");
		assertEquals(95, n.system().decide(n.sim, n.beta1).target(), "Beta 没被覆盖 ⇒ 仍是全局默认 100%");
	}

	/** 量化：写进字段的永远是 step 的整数倍（防"1% 抖动"把客户端推爆）。 */
	@Test
	public void quantizeRoundsToTheStep() {
		final TwoStations n = new TwoStations("quantize");
		final MmtrCrowdSystem system = n.system();
		system.setStep(2);
		assertEquals(0, system.quantize(0.9), "0.9 ⇒ 0");
		assertEquals(2, system.quantize(1.0), "1.0 ⇒ 2（四舍五入到偶数档）");
		assertEquals(96, system.quantize(95.0), "95 ⇒ 96（最近的偶数档）");
		assertEquals(100, system.quantize(99.4), "99.4 ⇒ 100");
		system.setStep(5);
		assertEquals(50, system.quantize(47.5), "47.5 ⇒ 50（step 5）");
		assertEquals(45, system.quantize(44.0), "44 ⇒ 45（step 5）");
	}

	/** 平滑：一阶低通 ⇒ 换档之后**不是立刻**到位，而是几十秒里渐变过去。 */
	@Test
	public void smoothingMovesGraduallyAndConverges() {
		final TwoStations n = new TwoStations("smooth");
		final MmtrCrowdSystem system = n.system();
		system.setStep(1);
		system.setEnabled(true);
		n.setHour(8);
		system.runNow(n.sim, true);
		assertEquals(95, n.levelOf(n.alpha1), "第一次直接落到目标");

		n.setHour(3); // 目标从 95 掉到 1
		system.runNow(n.sim, false);
		final long afterOneStep = n.levelOf(n.alpha1);
		assertTrue(afterOneStep > 80, "一步只走 α = 5s/90s ≈ 5.6%，还该在 80 以上，实际 " + afterOneStep);
		assertTrue(afterOneStep < 95, "但必须**动了**，实际 " + afterOneStep);

		for (int i = 0; i < 400; i++) {
			system.runNow(n.sim, false);
		}
		assertTrue(n.levelOf(n.alpha1) <= 2, "足够多拍之后要收敛到目标（3 点 = 1%），实际 " + n.levelOf(n.alpha1));
	}

	// ------------------------------------------------------------------ 班次与冲量

	/** **有车停在站台上 ⇒ 那条站台有冲量、那个车站计一次班次**；车走了冲量归零。 */
	@Test
	public void dwellingTrainSurgesItsPlatformAndCountsAnArrival() {
		final VehicleNet n = new VehicleNet();
		final MmtrCrowdSystem system = n.system();
		system.setStep(1);
		assertEquals(0, system.surgeOf(n.platformA.getId()), "还没车 ⇒ 没冲量");

		final Vehicle vehicle = n.spawn();
		final MmtrMission mission = n.missionTo(vehicle, n.platformA);
		assertTrue(mission.markStationArrival(1_000), "标上进站（这一步的作业窗开始）");
		system.runNow(n.sim, true);

		assertEquals(45, system.surgeOf(n.platformA.getId()), "停站冲量 = 45%");
		assertEquals(0, system.surgeOf(n.platformB.getId()), "另一条站台不受影响");
		assertEquals(46, system.decide(n.sim, n.platformA).target(), "3 点曲线 1% + 冲量 45 = 46");
		assertTrue(system.rateOf(n.platformA) > 0.5, "到站计数要记上，实际 " + system.rateOf(n.platformA));
		assertEquals(0.0, system.rateOf(n.platformB), 1e-9, "没车的站台不该被记班次");

		assertTrue(mission.markStationDeparture(2_000), "出站");
		system.runNow(n.sim, true);
		assertEquals(0, system.surgeOf(n.platformA.getId()), "车走了冲量归零（人靠 τ 散去）");
	}

	/**
	 * **车进站那一冲走得比早晚高峰快**（上冲快、回落慢）。
	 *
	 * <p>为什么这条是实证来的：实机第一次抓到冲量时（notes/425）"冲量 45、目标 100，站台上却只从
	 * 22 爬到 51" —— 站停只有二十几秒，τ=90 s 在那个窗口里只走 1/5。所以冲量上冲改用
	 * `surgeTauSeconds`（默认 20 s），回落仍走 τ。同一步（5 秒）里比"抬曲线"与"车进站"两条路走多远。</p>
	 */
	@Test
	public void surgeFillsFasterThanTheCurveDoes() {
		final VehicleNet n = new VehicleNet();
		final MmtrCrowdSystem system = n.system();
		system.setStep(1);
		system.setEnabled(true);
		system.setTauSeconds(90);
		system.setSurgeTauSeconds(20);
		system.runNow(n.sim, true);
		final long base = n.platformA.getCrowdLevel();
		assertEquals(1, base, "3 点 = 1%");

		// 慢的那条：把曲线抬到 18 点（95%），走一拍
		n.sim.setGameTime(18 * 3600_000L, MILLIS_PER_GAME_DAY, true);
		system.runNow(n.sim, false);
		final long slowStep = n.platformA.getCrowdLevel() - base;

		// 快的那条：回到 3 点，把车摆成"停站中"（冲量 45 ⇒ 目标 46），同样走一拍
		n.sim.setGameTime(3 * 3600_000L, MILLIS_PER_GAME_DAY, true);
		system.runNow(n.sim, true);
		final Vehicle vehicle = n.spawn();
		final MmtrMission mission = n.missionTo(vehicle, n.platformA);
		assertTrue(mission.markStationArrival(1_000), "标上进站");
		system.runNow(n.sim, false);
		final long fastStep = n.platformA.getCrowdLevel() - 1;

		assertTrue(fastStep > slowStep, "冲量那一拍要走得更远：车进站 " + fastStep + " vs 抬曲线 " + slowStep);
		assertEquals(11, fastStep, "α = 5s/20s = 25% ⇒ 1 + 45 × 0.25 ≈ 12 ⇒ 走 11");
		assertEquals(5, slowStep, "α = 5s/90s = 5.6% ⇒ 1 + 94 × 0.056 ≈ 6 ⇒ 走 5");
	}

	/** 班次因子按**最忙的那个站**归一：有车的站 1.00，没车的站 1 − boost。 */
	@Test
	public void serviceFactorRanksStationsAgainstTheBusiest() {
		final VehicleNet n = new VehicleNet();
		final MmtrCrowdSystem system = n.system();
		system.setStep(1);
		final Vehicle vehicle = n.spawn();
		final MmtrMission mission = n.missionTo(vehicle, n.platformA);
		mission.markStationArrival(1_000);
		system.runNow(n.sim, true);

		assertEquals(1.0, system.decide(n.sim, n.platformA).serviceFactor(), 1e-6, "最忙的车站 = 1.00");
		assertEquals(1.0 - system.getServiceBoost(), system.decide(n.sim, n.platformB).serviceFactor(), 1e-6,
			"没有车的车站 = 1 − 班次强度（默认 0.65）");
		assertEquals(1, system.decide(n.sim, n.platformB).target(), "3 点曲线 1% × 0.65 ⇒ 0.65 ⇒ 1");

		system.setServiceBoost(0);
		assertEquals(1.0, system.decide(n.sim, n.platformB).serviceFactor(), 1e-6, "强度 0 ⇒ 完全不看班次");
	}

	// ------------------------------------------------------------------ 事件

	/** 事件：加法/乘法都作用到算式上，**到点自己过期**。 */
	@Test
	public void eventsAdjustTargetsAndExpire() {
		final TwoStations n = new TwoStations("events");
		final MmtrCrowdSystem system = n.system();
		system.setStep(1);
		n.setHour(3); // 曲线 1%
		assertEquals(1, system.decide(n.sim, n.alpha1).target(), "没有事件时 = 曲线");

		system.addEvent(n.alpha.getId(), "Alpha", 40, 1.0, n.sim.getCurrentMillis(), 10 * 60_000L);
		assertEquals(41, system.decide(n.sim, n.alpha1).target(), "Alpha +40 ⇒ 41");
		assertEquals(1, system.decide(n.sim, n.beta1).target(), "只作用到指定车站");

		system.addEvent(0, "全图", 0, 3.0, n.sim.getCurrentMillis(), 10 * 60_000L);
		assertEquals(3, system.decide(n.sim, n.beta1).target(), "全图 ×3 ⇒ 曲线 1 × 3 = 3");
		assertEquals(43, system.decide(n.sim, n.alpha1).target(), "加法在乘法之后 ⇒ 1 × 3 + 40 = 43");

		// 时间往前走 11 分钟：两条事件都该过期
		n.sim.step(11 * 60_000L);
		system.runNow(n.sim, false);
		assertTrue(system.eventsView().isEmpty(), "过期的事件不能留着");
		assertEquals(1, system.decide(n.sim, n.alpha1).target(), "回到曲线值");
	}

	/** 事件能被点名清掉（id），也能一把全清。 */
	@Test
	public void eventsCanBeClearedByIdOrAll() {
		final TwoStations n = new TwoStations("event-clear");
		final MmtrCrowdSystem system = n.system();
		final long first = system.addEvent(n.alpha.getId(), "Alpha", 20, 1.0, 0, 600_000);
		system.addEvent(0, "全图", 10, 1.0, 0, 600_000);
		assertEquals(2, system.eventsView().size());
		assertEquals(1, system.clearEvents(first), "按 id 清一条");
		assertEquals(1, system.eventsView().size());
		assertEquals(1, system.clearEvents(0), "clear 0 = 全清");
		assertTrue(system.eventsView().isEmpty());
	}

	// ------------------------------------------------------------------ 落盘

	/** 配置整个存回来：开关、时钟、曲线、基准、事件、参数。 */
	@Test
	public void configSurvivesSaveAndReload() {
		final TwoStations n = new TwoStations("persist");
		final MmtrCrowdSystem system = n.system();
		system.setEnabled(true);
		system.setWorldClock(false);
		system.setPeriodMillis(2_000);
		system.setTauSeconds(30);
		system.setSurgeTauSeconds(15);
		system.setStep(4);
		system.setServiceBoost(0.5);
		system.setDwellSurge(60);
		system.setDefaultBase(80);
		system.applyProfile("夜间", false);
		system.setStationBase(n.alpha.getId(), 55);
		system.setPlatformBase(n.alpha1.getId(), 33);
		system.addEvent(n.beta.getId(), "Beta", -15, 1.5, 10_000, 600_000);
		assertTrue(system.save(), "要能写下去");

		final MmtrCrowdSystem reloaded = MmtrCrowdSystem.load(configPath(SAVE_ROOT.resolve("persist")));
		assertTrue(reloaded.isEnabled(), "开关要读回来");
		assertFalse(reloaded.isWorldClock(), "时钟来源要读回来");
		assertEquals(2_000, reloaded.getPeriodMillis());
		assertEquals(30.0, reloaded.getTauSeconds(), 1e-9);
		assertEquals(15.0, reloaded.getSurgeTauSeconds(), 1e-9);
		assertEquals(4, reloaded.getStep());
		assertEquals(0.5, reloaded.getServiceBoost(), 1e-9);
		assertEquals(60, reloaded.getDwellSurge());
		assertEquals(80, reloaded.getDefaultBase());
		assertEquals("夜间", reloaded.getProfileName());
		assertArrayEquals(MmtrCrowdSystem.CURVE_NIGHT, reloaded.getCurve(), "曲线整条要一样");
		assertEquals(55, reloaded.stationBaseView().get(n.alpha.getId()).longValue());
		assertEquals(33, reloaded.platformBaseView().get(n.alpha1.getId()).longValue());
		assertEquals(1, reloaded.eventsView().size(), "事件要读回来");
		final MmtrCrowdSystem.Event event = reloaded.eventsView().get(0);
		assertEquals(n.beta.getId(), event.stationId);
		assertEquals(-15, event.add);
		assertEquals(1.5, event.mul, 1e-9);
		assertEquals(10_000 + 600_000, event.untilMillis);
	}

	/** 配置文件不存在 = 默认（关着、通勤曲线），不该抛。 */
	@Test
	public void missingConfigLoadsDefaults() {
		final MmtrCrowdSystem system = MmtrCrowdSystem.load(SAVE_ROOT.resolve("never-written").resolve("mmtr-crowd-system.json"));
		assertFalse(system.isEnabled());
		assertTrue(system.isWorldClock());
		assertEquals("通勤", system.getProfileName());
		assertArrayEquals(MmtrCrowdSystem.CURVE_COMMUTE, system.getCurve());
	}

	// ------------------------------------------------------------------ 指令面

	/** 指令面走一遍：status / on / base / curve / event / why / off，并对上层算出的值负责。 */
	@Test
	public void commandsDriveTheSystem() {
		final TwoStations n = new TwoStations("commands");
		assertTrue(text(MmtrCommandDispatcher.execute(n.sim, "crowd status")).contains("车站人流系统"), "status 要能读");
		n.system().setStep(1);
		assertTrue(mmtr(n.sim, "crowd on").ok, "crowd on");
		assertTrue(n.system().isEnabled(), "on 之后系统在管");
		assertEquals(MmtrCrowdSystem.CURVE_COMMUTE[8], n.levelOf(n.alpha1), "on 的那一下立刻落到当前曲线（8 点 95%）");

		assertTrue(mmtr(n.sim, "crowd base Alpha 50").ok, "crowd base <站名> <n>");
		assertEquals(50, n.system().stationBaseView().get(n.alpha.getId()).longValue(), "按站名记到车站基准");
		assertEquals(48, n.system().decide(n.sim, n.alpha1).target(), "95% × 50% = 47.5 ⇒ 48");
		assertTrue(n.levelOf(n.alpha1) < 95, "字段要开始往下走（τ 平滑，不是一步到位），实际 " + n.levelOf(n.alpha1));

		assertTrue(mmtr(n.sim, "crowd curve 8 100").ok, "改一档曲线");
		assertEquals(100, n.system().curveAt(8));

		assertTrue(mmtr(n.sim, "crowd event add +40 10 --station=Alpha").ok, "加事件");
		assertEquals(1, n.system().eventsView().size());
		assertTrue(mmtr(n.sim, "crowd event list").ok, "列事件");
		assertTrue(mmtr(n.sim, "crowd event clear all").ok, "清事件");
		assertTrue(n.system().eventsView().isEmpty());

		assertTrue(mmtr(n.sim, "crowd why " + n.alpha1.getHexId()).ok, "crowd why <站台hex>");
		assertTrue(mmtr(n.sim, "crowd tune --tau=0 --step=5").ok, "调参");
		assertEquals(0, n.system().getTauSeconds(), "τ=0 = 不平滑");
		assertEquals(5, n.system().getStep());

		// **系统开着时 platform set/station/all 改的是系统的基准**，不是跟系统抢字段
		assertTrue(mmtr(n.sim, "platform station Alpha 30").ok, "platform station 在自动模式下要能走通");
		assertEquals(30, n.system().stationBaseView().get(n.alpha.getId()).longValue(), "记成车站基准");

		assertTrue(mmtr(n.sim, "crowd off").ok, "crowd off");
		assertFalse(n.system().isEnabled(), "off 之后不再接管");

		// 关掉之后 platform set 回到原路子：直接写字段
		assertTrue(mmtr(n.sim, "platform set " + n.beta1.getHexId() + " 77").ok);
		assertEquals(77, n.levelOf(n.beta1), "系统关着 ⇒ 手填直接进字段");
	}

	/** 错的参数要被挡住（而不是静默当成 0 或默认值）。 */
	@Test
	public void badInputIsRejected() {
		final TwoStations n = new TwoStations("bad-input");
		assertFalse(mmtr(n.sim, "crowd clock yesterday").ok, "时钟只认 world/real");
		assertFalse(mmtr(n.sim, "crowd curve 99 50").ok, "小时必须在 0–23");
		assertFalse(mmtr(n.sim, "crowd curve 1,2,3").ok, "整条曲线要 24 个数");
		assertFalse(mmtr(n.sim, "crowd base 不存在的站 50").ok, "找不到车站要报错");
		assertFalse(mmtr(n.sim, "crowd event add +40 0").ok, "时长要正数");
		assertFalse(mmtr(n.sim, "crowd event add 0 10").ok, "加法事件不能是 0");
		assertFalse(mmtr(n.sim, "crowd nonsense").ok, "不认识的动作要给出用法");
		assertFalse(mmtr(n.sim, "crowd now").ok, "系统关着时 now 不应该假装写了");
	}

	// ------------------------------------------------------------------ 助手

	private static MmtrCommandDispatcher.Result mmtr(Simulator simulator, String command) {
		return MmtrCommandDispatcher.execute(simulator, command);
	}

	private static String text(MmtrCommandDispatcher.Result result) {
		return String.join("\n", result.lines);
	}

	private static void assertArrayEquals(long[] expected, long[] actual) {
		assertArrayEquals(expected, actual, "曲线/数组要一致");
	}

	private static void assertArrayEquals(long[] expected, long[] actual, String message) {
		assertEquals(expected.length, actual.length, message);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], actual[i], message + "（第 " + i + " 档）");
		}
	}
}
