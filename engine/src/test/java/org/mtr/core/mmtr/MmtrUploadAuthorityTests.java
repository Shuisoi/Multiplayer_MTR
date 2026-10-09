package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Siding;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * notes/409 §4：**上行的引擎侧** —— 收一条客户端的位置帧之后会发生什么。
 *
 * <h2>这个类要守住的三件事</h2>
 * <ol>
 *   <li><b>门是硬的</b>：开关关着、发件人不是这辆车的驾驶权持有人、第一帧离引擎当前值太远、
 *       序号不比已接受的大、位移不可能、倒退 —— 每一条都得**被拒**，而且拒绝之后
 *       {@code mmtrUploadAuthorityHeld()} 必须是假（权威在服务端）。</li>
 *   <li><b>收下之后引擎不再采纳自己的位移</b>（§4.2 第 4 条）：这是"上行"这个词唯一的意义 ——
 *       实测断言是"车按**上传值**走，而不是按引擎自己那一拍算出来的位移（这里是 0）走"。</li>
 *   <li><b>夺回路径存在</b>（§4.4）：不可信的帧、走廊断了、保护制动，都会把权威交回服务端，
 *       而且交回之后有一个**隔离期**（否则"交回 / 下一帧又接管"会每帧来回刷）。</li>
 * </ol>
 *
 * <h2>为什么用 1 秒一步的 {@code simulateVehicles}</h2>
 * <p>全仓的 motion 测试都是这个节拍（{@code MmtrMotionMissionTests} 等），所以走廊长度在测试里显式
 * 调长（{@code -Dmmtr.upload.staleMillis=5000}）—— 否则 1 秒一步本身就会超出默认的 500 ms 走廊，
 * 把"引擎采纳上传值"这件事与"走廊断了"混在一起。走廊那一支有它自己的用例（用默认长度）。</p>
 */
public final class MmtrUploadAuthorityTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final UUID DRIVER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,"
		+ "\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	@AfterEach
	public void clearFlags() {
		System.clearProperty("mmtr.upload");
		System.clearProperty("mmtr.upload.staleMillis");
	}

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/**
	 * 司机上车 + 一条操纵包（照 {@code MmtrAwsWarningTests} / {@code MmtrManualPriorityTests} 的口径：
	 * ride 包 {@code isDriver} 为真 + {@link MmtrDriveControl}），把 {@code reverser} 一路送到
	 * {@code Vehicle.mmtrActiveControl} —— 倒退界那条判据读的就是它。
	 *
	 * <p>本类在 {@code org.mtr.core.mmtr} 包下，而 {@code Vehicle.updateRidingEntities} 是**包内可见**的，
	 * 所以骑乘记录从 {@link Siding#updateVehicleRidingEntities}（public，最终落到同一个方法）走。
	 * 少了这一条，{@code canTakeMmtrControl} 会以"请求者不在司机位上"拒掉整条操纵包，
	 * reverser 就停在缺省值上 —— 那样用例会绿得毫无意义（判据根本没被测到）。</p>
	 */
	private static void boardDriver(Rig rig, int reverser) {
		final ObjectArrayList<VehicleRidingEntity> riders = new ObjectArrayList<>();
		riders.add(new VehicleRidingEntity(DRIVER, 0, 0, 0, 0, false, true, true, false, false, false, false));
		rig.siding.updateVehicleRidingEntities(rig.vehicle.getId(), riders);
		new MmtrDriveControl(rig.vehicle.getId(), new ControlState().setThrottleNotch(1).setReverser(reverser), DRIVER).apply(rig.sim);
		assertTrue(rig.vehicle.isMmtrManualOverride(), "操纵包必须被收下（reverser=" + reverser + "），否则 mmtrActiveControl 还是空的");
	}

	/** 一条进站股道 + 一条正线（够走 300 m）—— 与 {@code MmtrInterlockReportTests} 同一套搭建手法。 */
	private static final class Rig {

		final Simulator sim;
		final Siding siding;
		final Vehicle vehicle;
		final double startM;

		Rig(String saveName, long staleMillis) {
			System.setProperty("mmtr.upload", "true");
			System.setProperty("mmtr.upload.staleMillis", Long.toString(staleMillis));
			sim = new Simulator("test", new String[]{"test"}, Paths.get("build/" + saveName), false);
			final Position yardBack = new Position(-32, 0, 0);
			final Position mouth = new Position(-20, 0, 0);
			final Rail yardRail = Rail.newSidingRail(yardBack, Angle.fromAngle(0), mouth, Angle.fromAngle(0),
				Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			final Rail main = through(mouth, new Position(300, 0, 0));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(yardBack, mouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Yard");
			depot.setCorners(new Position(-40, -5, -5), new Position(-10, 5, 5));
			sim.rails.add(yardRail);
			sim.rails.add(main);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			siding.tick();
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, new BranchStore(), null);
			assertNotNull(walker, "yard walker must resolve");
			vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion vehicle must spawn");
			startM = walker.distanceM();
		}

		/** 引擎此刻"在哪里"（= 上行帧要比对的那个参照）。 */
		double engineM() {
			final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
			return walker == null ? vehicle.getRailProgress() : walker.distanceM();
		}

		/** 一条上行帧（{@code now} 取模拟时钟，与 tick 里的看门狗同一把尺子）。 */
		String upload(double railProgressM, double speedMilli, int sequence, boolean holder) {
			return vehicle.mmtrAcceptUploadedMotion(DRIVER, "阿甲", railProgressM, speedMilli, sequence, sim.getCurrentMillis(), holder);
		}

		void step() {
			/*
			 * ★ 用 {@code Simulator.step}（**确定性推进**，notes/235 那个 seam）而不是
			 * {@code siding.simulateVehicles}：后者只推车辆、**不动模拟时钟**，而这一片的每一条判据
			 * （走廊长度、两次上传之间按速度推算）都是时间的函数 —— 第一版就是拿它当拍子，
			 * 于是"走廊断了"与"按速度推算"两条在单测里永远不成立，测试反而绿不了。
			 */
			sim.step(1000);
		}
	}

	@Test
	public void theFlagOffMeansNothingIsAccepted() {
		final Rig rig = new Rig("mmtr-upload-flag-off", 5000);
		System.setProperty("mmtr.upload", "false");
		assertNotNull(rig.upload(rig.engineM(), 0, 1, true), "开关关着 ⇒ 拒（行为与今天逐位一致）");
		assertFalse(rig.vehicle.mmtrUploadAuthorityHeld(), "关着的时候权威只在服务端");
	}

	@Test
	public void onlyTheDrivingRightsHolderMayUpload() {
		final Rig rig = new Rig("mmtr-upload-holder", 5000);
		assertNotNull(rig.upload(rig.engineM(), 0, 1, false), "不是驾驶权持有人 ⇒ 拒");
		assertFalse(rig.vehicle.mmtrUploadAuthorityHeld(), "被拒之后权威仍在服务端");
		assertNull(rig.upload(rig.engineM() + 2, 0, 1, true), "是持有人 ⇒ 收");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "收下之后位置权威在客户端");
	}

	@Test
	public void theFirstFrameMustBeReconciledWithTheEngine() {
		final Rig rig = new Rig("mmtr-upload-first-frame", 5000);
		assertNotNull(rig.upload(rig.engineM() + 100, 0, 1, true), "第一帧差 100 m ⇒ 拒（客户端还没对好账）");
		assertFalse(rig.vehicle.mmtrUploadAuthorityHeld(), "没对好账就不交权威");
		assertNull(rig.upload(rig.engineM() + 5, 0, 2, true), "差 5 m ⇒ 收");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "这一帧之后权威在客户端");
	}

	@Test
	public void theEngineStopsAdoptingItsOwnDistanceOnceTheUploadHolds() {
		final Rig rig = new Rig("mmtr-upload-adopt", 5000);
		/*
		 * 引擎这一拍自己算出来的位移是 0（没有手柄输入、没有任务、没有停车点），所以"车动了"
		 * 只可能来自上传值 —— 这正是这条断言的证伪力所在。
		 *
		 * 步长 4 m/秒：位移合理性的上界在"速度 0、Δt = 1 s"时是 5 + 1 = 6 m
		 * （{@code 余量 + ½·a·Δt²}），所以 4 m 是合法的一条帧 —— 这条判据本身在别的用例里守。
		 */
		final double first = rig.startM + 4;
		assertNull(rig.upload(first, 0, 1, true), "第一帧（差 4 m < 30 m）");
		rig.step();
		assertEquals(first, rig.vehicle.getRailProgress(), 1e-6, "位置按上传值走（引擎自己的位移是 0）");

		final double second = first + 4;
		assertNull(rig.upload(second, 0, 2, true), "第二帧（再 4 m，没有超过合理位移）");
		rig.step();
		assertEquals(second, rig.vehicle.getRailProgress(), 1e-6, "继续按上传值走");
	}

	@Test
	public void betweenTwoUploadsTheUploadedSpeedCarriesTheTrain() {
		final Rig rig = new Rig("mmtr-upload-speed", 5000);
		final double first = rig.startM + 10;
		assertNull(rig.upload(first, 0.02, 1, true), "第一帧：位置 +10 m、速度 0.02 m/ms（72 km/h）");
		rig.step();
		/*
		 * 两次上传之间按**上传速度**推算（与客户端自己那一份同一口径）：1 秒一步 ⇒ 再多走 20 m。
		 * 这条断言把"引擎没有第二个积分器"钉死 —— 多走的距离只可能来自上传的速度。
		 */
		assertEquals(first + 20, rig.vehicle.getRailProgress(), 0.05, "两次上传之间按上传速度推算");
		assertEquals(0.02, rig.vehicle.getSpeed(), 1e-9, "速度也就是上传的那个（引擎自己的动力学不采纳）");
	}

	@Test
	public void anOldSequenceNumberIsDroppedWithoutLosingAuthority() {
		final Rig rig = new Rig("mmtr-upload-sequence", 5000);
		assertNull(rig.upload(rig.engineM() + 2, 0, 7, true), "第一帧（序号 7）");
		assertNotNull(rig.upload(rig.engineM() + 2.5, 0, 7, true), "同一个序号再来一次 ⇒ 旧帧，丢弃");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "旧帧只是乱序/重放 —— 不该把权威交回（§4.2 第 2 条）");
		assertNull(rig.upload(rig.engineM() + 2.5, 0, 8, true), "序号更大 ⇒ 收");
	}

	@Test
	public void anImpossibleJumpIsDroppedAndRecallsAuthority() {
		final Rig rig = new Rig("mmtr-upload-jump", 5000);
		assertNull(rig.upload(rig.startM + 10, 0, 1, true), "第一帧");
		assertNotNull(rig.upload(rig.startM + 400, 0, 2, true), "一步 390 m ⇒ 位移不合法，丢弃");
		assertFalse(rig.vehicle.mmtrUploadAuthorityHeld(), "不可信 ⇒ 权威交回服务端（§4.4）");
		assertNotNull(rig.upload(rig.startM + 401, 0, 3, true), "刚交回 ⇒ 隔离期挡住新的第一帧");
	}

	@Test
	public void aReversedFrameIsDroppedAndRecallsAuthority() {
		final Rig rig = new Rig("mmtr-upload-reverse", 5000);
		assertNull(rig.upload(rig.startM + 10, 0, 1, true), "第一帧");
		assertNotNull(rig.upload(rig.startM + 5, 0, 2, true), "倒退 5 m ⇒ 丢弃（换端/连挂不该在本地权威下发生）");
		assertFalse(rig.vehicle.mmtrUploadAuthorityHeld(), "倒退之后权威交回服务端");
	}

	@Test
	public void aSilentCorridorHandsAuthorityBack() {
		/*
		 * 走廊用**默认长度**（500 ms）：1 秒一步 ⇒ 一步就断。这一条就是"客户端卡住了/掉线了，
		 * 引擎不能保持一辆不动的车"（那是最难查的形状：没人拦它，它却不动）。
		 */
		final Rig rig = new Rig("mmtr-upload-stale", 500);
		assertNull(rig.upload(rig.startM + 2, 0, 1, true), "第一帧");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "收下之后权威在客户端");
		rig.step();
		rig.step();
		assertFalse(rig.vehicle.mmtrUploadAuthorityHeld(), "走廊断了 ⇒ 权威交回服务端");
	}

	@Test
	public void theDrivingRightsHandoverTakesAuthorityBack() {
		final Rig rig = new Rig("mmtr-upload-handover", 5000);
		assertNull(rig.upload(rig.startM + 2, 0, 1, true), "第一帧");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "收下之后权威在客户端");
		// 驾驶权交接给**别人** ⇒ 立刻交回（旧持有人剩下的帧会被"认人"挡住）。
		rig.vehicle.mmtrRecallUploadAuthorityForNewHolder(OTHER, "驾驶权交接给 " + OTHER);
		assertFalse(rig.vehicle.mmtrUploadAuthorityHeld(), "换人 ⇒ 权威交回服务端");
	}

	@Test
	public void theSameDriversRepeatedTakeoverDoesNotBreakHisUpload() {
		final Rig rig = new Rig("mmtr-upload-same-holder", 5000);
		assertNull(rig.upload(rig.startM + 2, 0, 1, true), "第一帧");
		rig.vehicle.mmtrRecallUploadAuthorityForNewHolder(DRIVER, "重复接管（还是同一个人）");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "同一个人重复接管 ⇒ 不动他的上行（免得白打断 3 秒）");
	}

	/**
	 * 位移基准是**上一条被接受的帧**，不是引擎当前值（2026-10-09 实机 `交回 67 次`）。
	 *
	 * <h2>为什么这条断言存在</h2>
	 * <p>持有上行权威期间，引擎按住车的那几拍**不采纳**上传位移（§4.2 第 4 条 / §4.6 第 3 条
	 * "只按住、不交回"），于是引擎位置**一直落在客户端后面**。旧口径拿引擎当前值当位移基准，
	 * 这个落差就一直挂着：界里那 5 m 固定余量被耗光以后，**每一条帧**都判"不可信" ⇒ 3 秒一次收权、
	 * 循环往复（实机读数逐字：`位移 5 m > 这一拍的最大可能位移 5 m（Δt=108 ms）`），
	 * 司机眼里就是"一直往前开、又被服务端纠正回去"。</p>
	 *
	 * <h2>这条用例的证伪形状</h2>
	 * <p>先让引擎**采纳**第一帧（于是它与上一条被接受的帧对齐），再让客户端在**两次 tick 之间**
	 * 多走 5 m（引擎不采纳 ⇒ 落后 5 m —— 恰好是余量的全部），最后发一条**相对上一条被接受的帧
	 * 只前进 4 m** 的帧：它距引擎当前值 9 m（> 5 m 余量）⇒ 旧口径必定判不可信 ⇒ 收权；
	 * 新口径 ⇒ 收，而且权威不动。</p>
	 */
	@Test
	public void aForwardStepPastTheEngineWhileHeldIsNotTreatedAsImpossible() {
		final Rig rig = new Rig("mmtr-upload-held-forward", 5000);
		final double first = rig.startM + 4;
		assertNull(rig.upload(first, 0, 1, true), "第一帧（差 4 m < 30 m 的交接窗口）：先让权威被接受");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "收下之后位置权威在客户端");
		rig.step();
		/*
		 * 这一拍引擎**采纳了**上传值 ⇒ 引擎恰好落在上一条被接受的帧上，落差 0。
		 * 先有这个"对齐"，后面那句"引擎落后"才是被造出来的、不是初始状态里的。
		 */
		assertEquals(first, rig.engineM(), 1e-6, "引擎采纳了第一帧 ⇒ 与上一条被接受的帧对齐（落差 0）");

		/*
		 * 客户端再前进 5 m，而这两条帧之间**没有 tick** ⇒ 引擎这一拍不采纳上传位移：它仍停在 first 上。
		 * 落差 = 5 m = 界里余量的全部（这一帧本身旧口径也收：Δt = 1000 ms ⇒ 界 = 5 + 1 = 6 m > 5 m，
		 * 所以落差是从一条**合法帧**里长出来的，不是靠帧本身的非法位移堆出来的）。
		 */
		final double second = first + 5;
		assertNull(rig.upload(second, 0, 2, true), "第二帧（客户端 +5 m；Δt = 1000 ms ⇒ 界 6 m）");
		assertEquals(first, rig.engineM(), 1e-6, "中间没有 tick ⇒ 引擎没采纳这一帧，位置仍在 first 上（落后客户端 5 m）");

		final double target = second + 4;
		assertTrue(target - rig.engineM() > 5.0, "这一帧距引擎当前值 " + Math.round(target - rig.engineM())
			+ " m > 5 m 余量 —— 旧口径（基准 = 引擎当前值）必定判它不可信");
		assertNull(rig.upload(target, 0, 3, true), "跟**上一条被接受的帧**比只前进 4 m（Δt = 1 ms ⇒ 界 5.000001 m）⇒ 收");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "位移基准改成上一条被接受的帧 ⇒ 没有发生收权");
		/*
		 * "没有收权"的第二个证据（{@code mmtrUploadRecalls} 是私有的，读不到）：收权会带 3 秒隔离期，
		 * 下一条帧就会以"刚刚把权威交回服务端"被拒。它被当成**持有期间**的帧收下 ⇒ 收权根本没发生。
		 */
		assertNull(rig.upload(target + 1, 0, 4, true), "下一条帧仍按'持有期间'收下（不是'新的第一帧'）⇒ 权威确实没被交回过");
	}

	/**
	 * 倒退界看**司机意图**（2026-10-09 实机）。
	 *
	 * <h2>为什么这条断言存在</h2>
	 * <p>司机把换向器挂到反方向、正在**倒车**时，倒退是**合法动作**（典型现场：车头压在一个没开通的
	 * 岔口上，要倒出去）。旧口径一律按常量 {@code MMTR_UPLOAD_REVERSE_M}（1 m）判，于是倒车被当成
	 * "上行不可信"：实机读数 `倒退最大 3.359m/界 1.0m`、`交回 67 次` —— 司机的油门往前被红灯按住、
	 * 往后被这条判据收权，**前后都动不了**。新口径：挂了反方向就把倒退界放宽成与前进同一条
	 * {@code maxMoveM}；手柄回到正方向以后必须立刻回到 1 m 的旧口径。</p>
	 */
	@Test
	public void aReverseStepIsAcceptedWhileTheDriverHoldsTheReverserBack() {
		final Rig rig = new Rig("mmtr-upload-driver-reverse", 5000);
		final double first = rig.startM + 4;
		assertNull(rig.upload(first, 0, 1, true), "第一帧：先让权威被接受");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "收下之后位置权威在客户端");

		// 司机上车并把换向器挂到反方向（ride 包 isDriver + MmtrDriveControl，与 MmtrAwsWarningTests 同一口径）。
		boardDriver(rig, -1);

		final double backing = first - 3;
		assertNull(rig.upload(backing, 0, 2, true), "司机挂着反方向倒退 3 m ⇒ 收（倒退界放宽成这一拍的最大可能位移 5.000001 m）");
		assertTrue(rig.vehicle.mmtrUploadAuthorityHeld(), "倒车是司机的意图，不是'上行不可信' ⇒ 权威不动");

		// 换回正方向：倒退界回到常量 1 m，同一条"倒退 3 m"的帧必须被拒并把权威交回服务端（§4.4）。
		boardDriver(rig, 1);
		final String refusal = rig.upload(backing - 3, 0, 3, true);
		assertNotNull(refusal, "正方向下的倒退 3 m ⇒ 拒（倒退界回到 1 m）");
		assertTrue(refusal.contains("倒退"), "拒绝理由必须点名是倒退界（现场靠它区分'司机在换端/连挂'与'客户端算飞了'），实得：" + refusal);
		assertFalse(rig.vehicle.mmtrUploadAuthorityHeld(), "上行不可信 ⇒ 权威交回服务端（§4.4）");
	}
}
