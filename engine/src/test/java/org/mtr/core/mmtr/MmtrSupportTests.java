package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public final class MmtrSupportTests {

	@Test
	public void testUnitConversions() {
		assertEquals(1e-6, MmtrSupport.siAccelerationToInternal(1.0), 1e-12);
		assertEquals(2e-6, MmtrSupport.siAccelerationToInternal(2.0), 1e-12);
		assertEquals(1000.0, MmtrSupport.internalSpeedToSi(1.0), 1e-9);
	}

	@Test
	public void testLegacyPowerMapping() {
		// positive handle -> throttle notch
		final ControlState power3 = MmtrSupport.controlFromLegacyPowerLevel(3, 7, 8);
		assertEquals(3, power3.getThrottleNotch());
		assertEquals(0, power3.getBrakeNotch());
		assertFalse(power3.isEmergency());
		// negative handle -> brake notch (clamped to brake notches)
		final ControlState brake2 = MmtrSupport.controlFromLegacyPowerLevel(-2, 7, 8);
		assertEquals(2, brake2.getBrakeNotch());
		// emergency at the legacy minimum
		final ControlState emergency = MmtrSupport.controlFromLegacyPowerLevel(-8, 7, 8);
		assertTrue(emergency.isEmergency());
		// coast at zero
		final ControlState neutral = MmtrSupport.controlFromLegacyPowerLevel(0, 7, 8);
		assertEquals(0, neutral.getThrottleNotch());
		assertEquals(0, neutral.getBrakeNotch());
		// over-range positive clamps to power notches
		final ControlState over = MmtrSupport.controlFromLegacyPowerLevel(99, 7, 8);
		assertEquals(7, over.getThrottleNotch());
	}

	@Test
	public void testPolicyFieldsDefaultNull() {
		final org.mtr.core.simulation.Simulator simulator = new org.mtr.core.simulation.Simulator("t", new String[]{"t"}, java.nio.file.Paths.get("build/mmtr-policy-test"), false);
		assertNull(simulator.mmtrConsistTypes);
		assertNull(simulator.mmtrDefaultConsistTypeId);
	}
}
