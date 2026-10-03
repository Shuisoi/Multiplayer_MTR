package org.mtr.core.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public final class SidingTests {

	private Siding siding;

	@BeforeEach
	public void setUp() {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get("build/test-data-siding"), false);
		siding = new Siding(new Position(0, 0, 0), new Position(10, 0, 0), 0.5, TransportMode.TRAIN, simulator);
		siding.setName("Test Siding");
	}

	@Test
	public void testSidingConstruction() {
		assertNotNull(siding);
		assertEquals("Test Siding", siding.getName());
	}

	@Test
	public void testSidingRailLength() {
		assertEquals(0.5, siding.getRailLength(), 1e-10);
	}

	@Test
	public void testSidingMaxVehicles() {
		assertEquals(0, siding.getMaxVehicles(), "Default max vehicles should be 0");
	}

	@Test
	public void testSidingMaxManualSpeed() {
		assertEquals(0, siding.getMaxManualSpeed(), 1e-10, "Default max manual speed should be 0");
	}

	/*
	 * notes/235：`testAccelerationDefaults` / `testDecelerationDefaults` 已删除 ——
	 * 车场不再有"加减速度"这两个参数（那套原版加减速模型整条删除），所以没有默认值可断言。
	 * 车辆的加减速现在由车底（{@code ConsistType} / {@code TrainPhysics}）决定。
	 */

	@Test
	public void testManualToAutomaticTime() {
		assertEquals(10000, siding.getManualToAutomaticTime(), "Default manual to automatic time should be 10000");
	}

	@Test
	public void testDelayedVehicleSpeedIncreasePercentage() {
		assertEquals(25, siding.getDelayedVehicleSpeedIncreasePercentage());
	}

	@Test
	public void testDelayedVehicleReduceDwellTimePercentage() {
		assertEquals(100, siding.getDelayedVehicleReduceDwellTimePercentage());
	}

	@Test
	public void testEarlyVehicleIncreaseDwellTime() {
		assertTrue(siding.getEarlyVehicleIncreaseDwellTime());
	}

	@Test
	public void testGetVehicleDetailsAtPlatformNotFound() {
		final var result = siding.getVehicleDetailsAtPlatform(0, 0);
		assertFalse(result.rightBoolean(), "No vehicle should be found at platform with no setup");
	}

	@Test
	public void testGetDepotNameWithNoArea() {
		assertEquals("", siding.getDepotName(), "Depot name should be empty when area is null");
	}
}
