package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.physics.TrainPhysics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * notes/243 S1：**编组的挂车质量必须真的进物理**。
 *
 * <p>现场口径（用户 2026-09-23 给的纵向动力学规格 §1）：整备质量、回转质量折算 `m(1+ρ)` 都要按
 * **编组**算 —— 一台机车拖两节挂车时，加速与制动都该按 162 t 而不是机车的 82 t 走。
 * 这一版把"整列折成一份等效车底"（`MmtrComposition.toConsistType`），本类钉住它的求和规则。</p>
 */
public final class MmtrConsistInertiaTests {

	private static final String JSON = "{"
		+ "\"consistTypes\":["
		+ "  {\"id\":\"loco\",\"name\":\"机车\",\"controlMode\":\"THREE_HANDLE\",\"powerNotches\":97,\"brakeNotches\":11,"
		+ "   \"maxSpeedKmh\":160,\"massKg\":82000,\"rotatingMassFactor\":1.06,"
		+ "   \"maxTractiveEffortN\":200000,\"maxPowerW\":1900000,"
		+ "   \"serviceBrakeForceN\":90000,\"emergencyBrakeForceN\":150000,"
		+ "   \"resistanceAN\":1500,\"resistanceBN\":20,\"resistanceCN\":3.0,"
		+ "   \"rheostaticBrakeForceN\":70000,\"rheostaticFadeKmh\":10},"
		+ "  {\"id\":\"wagon\",\"name\":\"挂车\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "   \"maxSpeedKmh\":100,\"massKg\":40000,\"rotatingMassFactor\":1.06,"
		+ "   \"maxTractiveEffortN\":0,\"maxPowerW\":0,"
		+ "   \"serviceBrakeForceN\":30000,\"emergencyBrakeForceN\":45000,"
		+ "   \"resistanceAN\":700,\"resistanceBN\":10,\"resistanceCN\":1.5}"
		+ "]}";

	/** 机车（动力）+ 2 节挂车（无动力）—— BR101 + p1 + p1 的形状。 */
	private static MmtrComposition consist() {
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(JSON);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("loco_car", 2, 1, 10, 0, 1, 0.1, 0.1, true, "loco"));
		cars.add(new VehicleCar("wagon_a", 2, 1, 10, 0, 1, 0.1, 0.1, false, "wagon"));
		cars.add(new VehicleCar("wagon_b", 2, 1, 10, 0, 1, 0.1, 0.1, false, "wagon"));
		final MmtrComposition composition = MmtrComposition.fromVehicleCars(cars, registry, registry.get("wagon"));
		assertNotNull(composition, "三节车列应当能组成编组");
		return composition;
	}

	@Test
	public void theConsistAggregatesMassTractionBrakesAndResistance() {
		final ConsistType consist = consist().toConsistType("consist:test");
		assertEquals(162_000, consist.getMassKg(), 1e-6, "质量 = 机车 + 2 挂车");
		assertEquals(200_000, consist.getTraction().getMaxTractiveEffortN(), 1e-6, "牵引只来自动力车");
		assertEquals(1_900_000, consist.getTraction().getMaxPowerW(), 1e-6);
		assertEquals(90_000 + 30_000 + 30_000, consist.getBrake().getServiceForceN(), 1e-6, "制动力按车求和");
		assertEquals(1500 + 700 + 700, consist.getResistance().getAN(), 1e-6, "阻力按车求和");
		assertEquals(ConsistType.ControlMode.THREE_HANDLE, consist.getControlMode(), "操纵语义沿用说话那节车");
		assertNotNull(consist.getHandles(), "三手柄规格也要跟着说话那节车");
		assertEquals(160, consist.getMaxSpeedKmh(), 1e-9, "限速仍取说话那节车");
	}

	@Test
	public void deadTrailingMassReducesTheAccelerationTowardsTheMassRatio() {
		final ConsistType loco = ConsistTypeRegistry.parse(JSON).get("loco");
		final ConsistType consist = consist().toConsistType("consist:test");
		final double singleAcceleration = loco.getPhysics().tractionAccelerationMps2(1, 0);
		final double consistAcceleration = consist.getPhysics().tractionAccelerationMps2(1, 0);
		/*
		 * 惯性比 = 82/162 ≈ 0.506；实际比值会比它**再低一点**，因为 `tractionAccelerationMps2` 已经
		 * 扣掉了运行阻力，而挂车只加阻力、不加牵引。所以用带宽断言（0.40…0.55），而不是把"刚好等于
		 * 质量比"写成等式 —— 那会是一条假的精确判据。
		 */
		final double ratio = consistAcceleration / singleAcceleration;
		assertTrue(ratio > 0.40 && ratio < 0.55,
			"同样满牵引下，三节编组的加速度应当落在单机的 40%…55%（≈82/162），实际 " + ratio);
		assertTrue(consistAcceleration < singleAcceleration * 0.55, "挂车质量必须真的拖慢起步");
	}

	@Test
	public void anUndeclaredPoweredConsistStillHasTraction() {
		// 用例里用 VehicleCar 拼的车列基本不写 powered：不能因此把整列变成"没有牵引的死车"
		final ConsistTypeRegistry registry = ConsistTypeRegistry.parse(JSON);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("lead_car", 2, 1, 10, 0, 1, 0.1, 0.1, true, "loco"));
		cars.add(new VehicleCar("trail_car", 2, 1, 10, 0, 1, 0.1, 0.1, false, "wagon"));
		final MmtrComposition composition = MmtrComposition.fromVehicleCars(cars, registry, registry.get("wagon"));
		assertNotNull(composition);
		final TrainPhysics physics = composition.toConsistType("consist:undeclared").getPhysics();
		assertTrue(physics.getTraction().getMaxTractiveEffortN() > 0, "没声明动力车时按'说话那节车带动力'处理");
		assertEquals(82_000 + 40_000, physics.getMassKg(), 1e-6);
	}
}
