package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.brake.BrakeCommand;
import org.mtr.core.mmtr.brake.BrakeModel;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coupling/uncoupling groundwork: composition list ops + mass-weighted aggregation + per-car air.
 *
 * <p>notes/376：编组自己那套"归一化逐车气路"（{@code aggregate} / {@code stepAir} /
 * {@code resetAirState} / {@code encodeAirStates} / {@code Unit.getPipePressure()}）已整段删除。
 * 逐车气路只有一条路：{@link MmtrComposition#brakeCars()} → {@code BrakeModel.setCars()}，
 * 操纵方式折成 {@link BrakeCommand}；编组级的力则走 {@code controller.compute(control, toConsistType(...))}。
 * 这里钉的性质（充风、前车先建、有界、紧急优先、状态串往返、解挂各半独立）一条都没少，只是换了入口。</p>
 */
public final class MmtrCompositionTests {

	/** 一个"质量单位"= 60 t（λ=1）。用例里的数字仍是"加速度量级"，这里换算成力 ⇒ 聚合结果逐点不变。 */
	private static final double UNIT_MASS_KG = 60_000;

	private static final long DT_MS = 50;
	private static final double DT_SECONDS = DT_MS / 1000.0;

	private static ConsistType type(String id, double maxKmh, double tractionAccelerationMps2, double serviceDecelerationMps2, double massUnits) {
		final double massKg = UNIT_MASS_KG * massUnits;
		final double effortN = massKg * tractionAccelerationMps2;
		return new ConsistType(id, "", ConsistType.ControlMode.NOTCHED, 7, 8, maxKmh,
			massKg, 1.0, effortN, effortN * maxKmh / 3.6,
			massKg * serviceDecelerationMps2, massKg * 1.5,
			0, 0, 0, 0.37, false, 0, null, ConsistType.DEFAULT_BRAKES, null, 0, null, false);
	}

	private static ConsistType wagon(String id, double massUnits) {
		final double massKg = UNIT_MASS_KG * massUnits;
		return new ConsistType(id, "", ConsistType.ControlMode.NOTCHED, 7, 8, 100,
			massKg, 1.0, 0, 0,
			massKg * 0.5, massKg * 1.2,
			massKg * 0.05, 0, 0, 0.37, false, 0, null, ConsistType.DEFAULT_BRAKES, null, 0, null, false);
	}

	private static MmtrComposition freightTrain(int wagons) {
		final ConsistType loco = type("loco", 100, 0.3, 0.7, 2);
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", loco));
		for (int i = 0; i < wagons; i++) {
			train.couple(new MmtrComposition.Unit("w" + (i + 1), wagon("w" + (i + 1), 1), false));
		}
		return train;
	}

	/** 编组级等效车底（操纵语义沿用说话那节车，物理量按车求和）—— 控制器的入口（notes/243 S1）。 */
	private static ConsistType consist(MmtrComposition train) {
		return train.toConsistType("consist:test");
	}

	/** 编组 → 制动模型：{@code brakeCars()} 就是连挂形式的适配口（notes/270/376）。 */
	private static BrakeModel brakeModel(MmtrComposition train) {
		final BrakeModel model = new BrakeModel("用例");
		model.setCars(train.brakeCars());
		return model;
	}

	/**
	 * 编组 → 有级控制器：**与 {@code Vehicle} 的接线同一个写法**（控制器自己持模型，
	 * {@code getBrakeModel().setCars(composition.brakeCars())}）。
	 */
	private static NotchedDriveController controller(MmtrComposition train) {
		final NotchedDriveController controller = new NotchedDriveController();
		controller.getBrakeModel().setCars(train.brakeCars());
		return controller;
	}

	/** 跑 {@code seconds} 秒并返回最后一拍（控制器是有状态的；旧的 {@code aggregate} 是纯函数）。 */
	private static DriveOutput run(NotchedDriveController controller, ConsistType consist, ControlState control, double speedMps, double seconds) {
		DriveOutput out = null;
		for (int i = 0; i < Math.round(seconds * 1000 / DT_MS); i++) {
			out = controller.compute(control, consist, speedMps, DT_MS);
		}
		return out;
	}

	@Test
	public void coupleUncoupleAndSplit() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", type("loco", 100, 0.3, 0.7, 2)));
		train.couple(new MmtrComposition.Unit("w1", wagon("w1", 1)));
		train.couple(new MmtrComposition.Unit("w2", wagon("w2", 1), false));
		assertEquals(3, train.size());
		assertEquals(4 * UNIT_MASS_KG, train.totalEffectiveMassKg(), 1e-9);
		assertFalse(train.unit(2).isPowered(), "w2 is a dead trailer");

		final MmtrComposition tail = train.splitAfter(0);
		assertEquals(1, train.size());
		assertEquals(2, tail.size());
		assertEquals("w1", tail.unit(0).getId());
		assertEquals("w2", tail.unit(1).getId());

		train.couple(tail);
		assertEquals(3, train.size());
		assertEquals("w2", train.uncoupleLast().getId());
		assertEquals(2, train.size());
		assertThrows(IllegalStateException.class, () -> new MmtrComposition().uncoupleLast(), "empty composition cannot uncouple");
		assertThrows(IndexOutOfBoundsException.class, () -> train.splitAfter(5), "bad split index must throw");
	}

	@Test
	public void deadTrailingMassReducesTraction() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", type("loco", 100, 0.3, 0.7, 2)));
		train.couple(new MmtrComposition.Unit("w1", wagon("w1", 1), false));
		final DriveOutput out = controller(train).compute(ControlState.zero().setThrottleNotch(7), consist(train), 0, DT_MS);
		/*
		 * 0.1833（旧口径是 0.2）= 机车牵引 0.3 × 2 个单位质量 / 3 个单位质量，**再减去整列车的运行阻力**
		 * （挂车也产生阻力）。旧代码只减"动力车自己那份"阻力，量纲上把挂车的阻力漏掉了 —— 力模型下它是
		 * 整车的一项（notes/235）。notes/376 之后这条走"等效车底 + 控制器"，数值逐位不变。
		 */
		assertEquals(0.18333333333333335, out.getAccelerationMetersPerSecondSquared(), 1e-9, "2/3 of the standalone accel over 3 total mass, minus the whole train's resistance");
	}

	@Test
	public void twoPoweredUnitsKeepFullTraction() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("e1", type("e1", 100, 0.5, 0.9, 1)));
		train.couple(new MmtrComposition.Unit("e2", type("e2", 100, 0.5, 0.9, 1)));
		final DriveOutput out = controller(train).compute(ControlState.zero().setThrottleNotch(7), consist(train), 0, DT_MS);
		assertEquals(0.5, out.getAccelerationMetersPerSecondSquared(), 1e-9);
	}

	/** 制动 / 紧急 / 惰行三支的符号与量级（控制器有状态 ⇒ 各用一台控制器跑到稳态再读）。 */
	@Test
	public void brakingAndCoastAreMassWeightedAndNeverPositive() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("loco", type("loco", 100, 0.3, 0.7, 2)));
		train.couple(new MmtrComposition.Unit("w1", wagon("w1", 1), false));
		final ConsistType consist = consist(train);
		// 8 档 = 全常用（0.9）：机车 84 kN + 挂车 30 kN，缸压 3.8 bar、闸片 κ(36 km/h) ⇒ 约 −0.65 m/s²
		final DriveOutput brake = run(controller(train), consist, ControlState.zero().setBrakeNotch(8), 10, 10);
		assertTrue(brake.getAccelerationMetersPerSecondSquared() < -0.5, "braking must decelerate, got " + brake.getAccelerationMetersPerSecondSquared());
		assertTrue(brake.isBrakeLamp());
		final DriveOutput emergency = run(controller(train), consist, ControlState.zero().setEmergency(true), 10, 5);
		assertTrue(emergency.getAccelerationMetersPerSecondSquared() < brake.getAccelerationMetersPerSecondSquared(), "emergency brakes harder than service");
		final DriveOutput coast = run(controller(train), consist, ControlState.zero(), 20, 10);
		assertTrue(coast.getAccelerationMetersPerSecondSquared() < 0, "coast decays with resistance");
	}

	@Test
	public void resistanceWeightedByMass() {
		final MmtrComposition train = new MmtrComposition(new MmtrComposition.Unit("w", wagon("w", 3)));
		assertEquals(0.05, train.resistance(0), 1e-9);
	}

	/** 新挂上来的空管车厢必须**通过列车管**自己充上风，而且充风期间不许出缸压。 */
	@Test
	public void emptyPipeChargesFromFrontAfterCoupling() {
		final MmtrComposition train = freightTrain(1);
		final ConsistType consist = consist(train);
		final BrakeModel model = brakeModel(train);
		// 连挂那一拍：车头满管、新车厢空管（系统还没建 ⇒ 状态串先存着，建起来的那一拍灌进去）
		model.applyState("1,0;0,0");
		assertEquals("1,0;0,0", model.encodeState(), "系统还没建 ⇒ 原样保留");
		for (int i = 0; i < 300; i++) {
			model.step(consist.getBrakes(), null, BrakeCommand.coast(), 0, 0, DT_SECONDS);
		}
		System.out.println(String.format("[TEST] 充风 15 s：车头管压 %.3f bar、车厢 %.3f bar（缸压 %.3f bar）",
			model.getPipeBar(0), model.getPipeBar(1), model.getCylinderBar(1)));
		assertTrue(model.getPipePressure() > 0.99, "loco pipe stays charged");
		assertTrue(model.getPipeBar(1) / consist.getBrakes().getChargedBar() > 0.9,
			"empty wagon pipe must charge through the train pipe, got " + model.getPipeBar(1) + " bar");
		assertEquals(0, model.getCylinderBar(1), 1e-9, "cylinder must stay released while pipe is charged（缸簧 0.30 bar 以下不出力）");
	}

	/** 前车先建：车头管压降在前、尾车滞后，于是车头缸压先起来。 */
	@Test
	public void serviceBrakePropagatesFrontToRear() {
		final MmtrComposition train = freightTrain(3);
		final ConsistType consist = consist(train);
		final BrakeModel model = brakeModel(train);
		for (int i = 0; i < 12; i++) {
			model.step(consist.getBrakes(), null, BrakeCommand.ofRatio(0.9, 0, false), 10, 0, DT_SECONDS);
		}
		final double frontPipe = model.getPipeBar(0);
		final double rearPipe = model.getPipeBar(3);
		assertTrue(frontPipe < 0.995 * consist.getBrakes().getChargedBar(), "front pipe should have dropped, got " + frontPipe);
		assertTrue(frontPipe < rearPipe, "front pipe must drop ahead of the rear (gradient), front=" + frontPipe + " rear=" + rearPipe);
		assertTrue(model.getCylinderBar(0) >= model.getCylinderBar(3) - 1e-9, "front cylinder responds first");
	}

	@Test
	public void repeatedAirBrakingIsMonotonicAndStable() {
		final MmtrComposition train = freightTrain(4);
		final ConsistType consist = consist(train);
		final NotchedDriveController controller = controller(train);
		final ControlState apply = ControlState.zero().setBrakeNotch(8);
		double speed = 6;
		for (int i = 0; i < 600; i++) {
			final DriveOutput out = controller.compute(apply, consist, speed, DT_MS);
			assertTrue(Double.isFinite(out.getAccelerationMetersPerSecondSquared()), "output must be finite");
			speed = Math.max(0, speed + out.getAccelerationMetersPerSecondSquared() * 0.05);
			assertTrue(speed <= 6.0 + 1e-9, "speed must never exceed the starting speed while braking");
			for (int u = 0; u < train.size(); u++) {
				final double pipeBar = controller.getBrakeModel().getPipeBar(u);
				final double cylinderBar = controller.getBrakeModel().getCylinderBar(u);
				assertTrue(pipeBar >= 0 && pipeBar <= consist.getBrakes().getChargedBar(), "pipe bounded, car " + u + " = " + pipeBar);
				assertTrue(cylinderBar >= 0 && cylinderBar <= consist.getBrakes().getCylinderEmergencyBar(), "cylinder bounded, car " + u + " = " + cylinderBar);
			}
		}
		System.out.println(String.format("[TEST] 30 s 全常用（4 节货车）：6 m/s → %.4f m/s（车头管压 %.2f bar、缸压 %.2f bar）",
			speed, controller.getBrakeModel().getPipeBar(), controller.getBrakeModel().getCylinderBar()));
		assertTrue(speed < 0.5, "30 s of full service air brake must stop the freight, got " + speed);
	}

	@Test
	public void airModelIsDeterministic() {
		final ControlState apply = ControlState.zero().setBrakeNotch(8);
		final MmtrComposition a = freightTrain(2);
		final MmtrComposition b = freightTrain(2);
		final ConsistType consist = consist(a);
		final NotchedDriveController controllerA = controller(a);
		final NotchedDriveController controllerB = controller(b);
		double speedA = 15;
		double speedB = 15;
		for (int i = 0; i < 100; i++) {
			speedA += controllerA.compute(apply, consist, speedA, DT_MS).getAccelerationMetersPerSecondSquared() * 0.05;
			speedB += controllerB.compute(apply, consist, speedB, DT_MS).getAccelerationMetersPerSecondSquared() * 0.05;
		}
		assertEquals(speedA, speedB, 0, "identical runs must be identical");
		for (int u = 0; u < a.size(); u++) {
			assertEquals(controllerA.getBrakeModel().getPipeBar(u), controllerB.getBrakeModel().getPipeBar(u), 0, "第 " + u + " 节管压");
			assertEquals(controllerA.getBrakeModel().getCylinderBar(u), controllerB.getBrakeModel().getCylinderBar(u), 0, "第 " + u + " 节缸压");
		}
	}

	@Test
	public void emergencyStopsFasterThanServiceBrake() {
		final MmtrComposition trainA = freightTrain(3);
		final MmtrComposition trainB = freightTrain(3);
		final ConsistType consist = consist(trainA);
		final NotchedDriveController service = controller(trainA);
		final NotchedDriveController emergency = controller(trainB);
		double serviceSpeed = 25;
		double emergencySpeed = 25;
		for (int i = 0; i < 300; i++) {
			serviceSpeed += service.compute(ControlState.zero().setBrakeNotch(8), consist, serviceSpeed, DT_MS).getAccelerationMetersPerSecondSquared() * 0.05;
			emergencySpeed += emergency.compute(ControlState.zero().setEmergency(true), consist, emergencySpeed, DT_MS).getAccelerationMetersPerSecondSquared() * 0.05;
		}
		System.out.println(String.format("[TEST] 15 s：常用 25 → %.2f m/s、紧急 25 → %.2f m/s", serviceSpeed, emergencySpeed));
		assertTrue(emergencySpeed < serviceSpeed, "emergency must stop faster than full service (em=" + emergencySpeed + " svc=" + serviceSpeed + ")");
	}

	/**
	 * 解挂：状态按车序切分 —— 头车那一半回到"满管、无缸压"（新造模型），尾车那一半带着**自己那一份**
	 * 管压/缸压走（连挂接口 {@code encodeState}/{@code applyState} 同格式，notes/270/376）。
	 */
	@Test
	public void uncoupleKeepsIndependentAirState() {
		final MmtrComposition train = freightTrain(2);
		final ConsistType consist = consist(train);
		final BrakeCommand apply = BrakeCommand.ofRatio(0.9, 0, false);
		final BrakeModel model = brakeModel(train);
		for (int i = 0; i < 300; i++) {
			model.step(consist.getBrakes(), null, apply, 10, 0, DT_SECONDS);
		}
		final double headPipeBar = model.getPipeBar(0);
		final double tailPipeBar = model.getPipeBar(1);
		final double tailCylinderBar = model.getCylinderBar(1);
		assertTrue(tailCylinderBar > 1.0, "尾车在解挂前压着闸（缸压 " + tailCylinderBar + " bar）");
		assertTrue(headPipeBar < consist.getBrakes().getChargedBar() - 1e-9, "解挂前车头管子已经降下来");

		final String[] cars = model.encodeState().split(";");
		assertEquals(3, cars.length, "一节车一段：" + model.encodeState());
		final MmtrComposition tail = train.splitAfter(0);
		assertEquals(1, train.size());
		assertEquals(2, tail.size());

		// 头车那一半：重新造模型 = 满管、无缸压（旧 resetAirState 的等价物）
		final BrakeModel headModel = brakeModel(train);
		headModel.step(consist.getBrakes(), null, BrakeCommand.coast(), 10, 0, DT_SECONDS);
		assertEquals(1.0, headModel.getPipePressure(), 1e-12);

		// 尾车那一半：把自己那两段状态搬进它的模型，随后原样恢复（管压 3.5 bar、缸压 3.8 bar）
		final BrakeModel tailModel = brakeModel(tail);
		tailModel.applyState(cars[1] + ";" + cars[2]);
		tailModel.step(consist.getBrakes(), null, apply, 10, 0, DT_SECONDS);
		assertEquals(tailPipeBar, tailModel.getPipeBar(0), 1e-9, "解挂的尾车保留自己的管压");
		assertEquals(tailCylinderBar, tailModel.getCylinderBar(0), 1e-9, "也保留自己的缸压");
	}

	/** 逐车状态串往返（格式与客户端的 {@code mmtrAirState} 同一套）；坏串忽略、超范围比例被夹住。 */
	@Test
	public void airStateEncodeDecodeRoundTrip() {
		final MmtrComposition source = freightTrain(2);
		final ConsistType consist = consist(source);
		final BrakeModel sourceModel = brakeModel(source);
		sourceModel.step(consist.getBrakes(), null, BrakeCommand.coast(), 0, 0, DT_SECONDS);
		sourceModel.applyState("0.4,0.6;0.9,0.1;0.2,0.8");
		final String encoded = sourceModel.encodeState();
		assertEquals(3, encoded.split(";").length, "一节车一段：" + encoded);

		final MmtrComposition copy = freightTrain(2);
		final BrakeModel copyModel = brakeModel(copy);
		copyModel.step(consist.getBrakes(), null, BrakeCommand.coast(), 0, 0, DT_SECONDS);
		copyModel.applyState(encoded);
		for (int i = 0; i < 3; i++) {
			assertEquals(sourceModel.getPipeBar(i), copyModel.getPipeBar(i), 1e-6, "第 " + i + " 节车的管压要跟着状态串走");
			assertEquals(sourceModel.getCylinderBar(i), copyModel.getCylinderBar(i), 1e-6, "缸压同理");
		}
		// 比例 → bar 的换算按**这一节车自己的口径**（管压比例 × 充风值、缸压比例 × 缸压上限）
		final PneumaticBrakeSpec air = consist.getBrakes();
		assertEquals(0.4 * air.getChargedBar(), sourceModel.getPipeBar(0), 1e-6);
		assertEquals(0.6 * air.getCylinderMaxBar(), sourceModel.getCylinderBar(0), 1e-6);
		assertEquals(0.9 * air.getChargedBar(), sourceModel.getPipeBar(1), 1e-6);

		copyModel.applyState("not-a-number;x");
		assertEquals(0.9 * air.getChargedBar(), copyModel.getPipeBar(1), 1e-6, "坏串被忽略：该车保持原状");
		// 超范围的比例夹在 [0, 充风值] / [0, 紧急限压] 内（归一化读数因此永不出 [0,1]）
		copyModel.applyState("5,-3");
		assertEquals(air.getChargedBar(), copyModel.getPipeBar(0), 1e-12);
		assertEquals(0, copyModel.getCylinderBar(0), 1e-12);
	}
}
