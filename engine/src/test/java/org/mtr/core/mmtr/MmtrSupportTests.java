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
	public void testSpeedToInternal() {
		assertEquals(0.001, MmtrSupport.siSpeedToInternal(1.0), 1e-12);
		assertEquals(1.0, MmtrSupport.internalSpeedToSi(MmtrSupport.siSpeedToInternal(1.0)), 1e-12);
	}

	@Test
	public void testPolicyFieldsDefaultNull() {
		final org.mtr.core.simulation.Simulator simulator = new org.mtr.core.simulation.Simulator("t", new String[]{"t"}, java.nio.file.Paths.get("build/mmtr-policy-test"), false);
		assertNull(simulator.mmtrConsistTypes);
		assertNull(simulator.mmtrDefaultConsistTypeId);
	}
}
