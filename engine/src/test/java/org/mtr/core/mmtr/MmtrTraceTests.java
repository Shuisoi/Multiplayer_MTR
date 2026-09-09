package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-tick motion / client-sync traces are the difference between a readable real-machine log and
 * thousands of lines a minute, so the gate itself is a contract: silent while off, one line while on,
 * and never left switched on for the rest of the suite.
 */
public final class MmtrTraceTests {

	@Test
	public void perTickTracesStaySilentUntilSwitchedOn() {
		final PrintStream originalOut = System.out;
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
			MmtrTrace.setEnabled(false);
			MmtrTrace.log("[MMTR-DRV] motion seg=hidden");
			assertTrue(buffer.toString(StandardCharsets.UTF_8).isEmpty(), "trace off: nothing reaches the log");
			MmtrTrace.setEnabled(true);
			MmtrTrace.log("[MMTR-DRV] motion seg=visible");
		} finally {
			System.setOut(originalOut);
			MmtrTrace.setEnabled(false);
		}
		assertEquals("[MMTR-DRV] motion seg=visible" + System.lineSeparator(), buffer.toString(StandardCharsets.UTF_8), "trace on: the line is printed exactly once");
		assertFalse(MmtrTrace.isEnabled(), "the gate is left off for the rest of the suite");
	}
}
