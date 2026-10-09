package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleCar;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2: the per-car composition is now built from MTR's runtime car list, so each car keeps its own
 * powered flag and its own ConsistType. The acceptance is that a locomotive hauling wagons adds
 * mass and brake-pipe volume without adding traction, and that an unresolvable type degrades to
 * the old "no composition" path instead of throwing.
 */
public final class MmtrCompositionFromCarsTests {

	private static VehicleCar car(String vehicleId, boolean powered, String consistTypeId) {
		return new VehicleCar(vehicleId, 10, 2, 100, 0, 5, 0.5, 0.5, powered, consistTypeId);
	}

	private static ConsistTypeRegistry registry() {
		return ConsistTypeRegistry.parse("{\"consistTypes\":["
			+ "{\"id\":\"loco\",\"massKg\":120000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":84000,\"emergencyBrakeForceN\":126000},"
			+ "{\"id\":\"wagon\",\"massKg\":60000,\"maxTractiveEffortN\":0,\"serviceBrakeForceN\":30000,\"emergencyBrakeForceN\":45000}]}");
	}

	@Test
	public void hauledWagonsAddMassButNoTraction() {
		final ConsistTypeRegistry registry = registry();
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("loco", true, "loco"), car("w1", false, "wagon"), car("w2", false, "wagon")),
			registry,
			registry.get("loco"));
		assertNotNull(train);
		assertEquals(3, train.size());
		assertTrue(train.unit(0).isPowered(), "the locomotive pulls");
		assertFalse(train.unit(1).isPowered(), "a hauled wagon contributes no traction");
		assertFalse(train.unit(2).isPowered());
		assertEquals(240_000, train.totalEffectiveMassKg(), 1e-9, "loco 120 t + two wagons of 60 t（λ=1）");
		// notes/376：编组级的力走"等效车底 + 控制器"（旧的 MmtrComposition.aggregate 已删除）。
		final DriveOutput out = new NotchedDriveController()
			.compute(ControlState.zero().setThrottleNotch(7), train.toConsistType("consist:test"), 0, 50);
		assertEquals(0.15, out.getAccelerationMetersPerSecondSquared(), 1e-9, "0.3 accel from the loco (mass 2 of 4) spread over the whole train");
	}

	@Test
	public void perCarConsistTypeOverridesTheConsistDefault() {
		final ConsistTypeRegistry registry = registry();
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("loco", true, ""), car("w1", false, "wagon")),
			registry,
			registry.get("loco"));
		assertNotNull(train);
		assertEquals("loco", train.unit(0).getType().getId(), "an empty per-car id falls back to the consist default");
		assertEquals("wagon", train.unit(1).getType().getId(), "a per-car id wins");
	}

	@Test
	public void unresolvableTypesReturnNoComposition() {
		final ConsistTypeRegistry registry = registry();
		assertNull(MmtrComposition.fromVehicleCars(new ObjectArrayList<>(), registry, registry.get("loco")), "no cars, no composition");
		assertNull(MmtrComposition.fromVehicleCars(List.of(car("loco", true, "")), null, null), "no fallback type means no composition");
		assertNotNull(
			MmtrComposition.fromVehicleCars(List.of(car("loco", true, "not-in-registry")), registry, registry.get("loco")),
			"an unknown per-car id falls back to the consist default instead of failing");
	}

	@Test
	public void theBuiltFreightTrainBrakesWithEveryCarsOwnCylinder() {
		final ConsistTypeRegistry registry = registry();
		final MmtrComposition train = MmtrComposition.fromVehicleCars(
			List.of(car("loco", true, "loco"), car("w1", false, "wagon"), car("w2", false, "wagon")),
			registry,
			registry.get("loco"));
		assertNotNull(train);
		final ConsistType consist = train.toConsistType("consist:test");
		// notes/376：逐车气路走 BrakeModel，控制器自己持模型（与 Vehicle 的接线同一个写法）；
		// 旧的 MmtrComposition.stepAir / Unit.getPipePressure 已删除。
		final NotchedDriveController controller = new NotchedDriveController();
		controller.getBrakeModel().setCars(train.brakeCars());
		final ControlState apply = ControlState.zero().setBrakeNotch(8);
		double speed = 6;
		for (int i = 0; i < 600; i++) {
			speed = Math.max(0, speed + controller.compute(apply, consist, speed, 50).getAccelerationMetersPerSecondSquared() * 0.05);
		}
		System.out.println(String.format("[TEST] 拼出来的货列 30 s 全常用：6 m/s → %.4f m/s（尾车缸压 %.2f bar）",
			speed, controller.getBrakeModel().getCylinderBar(2)));
		assertTrue(speed < 0.5, "30 s of full service air brake must stop the built freight, got " + speed);
		assertTrue(controller.getBrakeModel().getCylinderBar(2) > 0, "the hauled wagon's own brake cylinder applies");
		assertTrue(controller.getPipePressure() < 0.995, "the driver's handle acts on the leading unit");
	}
}
