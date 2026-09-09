package org.mtr.core.mmtr;

/**
 * 实机诊断开关 (real-machine trace gate).
 *
 * <p>Motion-Core prints one line per moving vehicle per tick (the sub-stepped integration result) and
 * the client-sync pass prints one per dirty vehicle per client per tick. Both are invaluable when
 * chasing a driving defect and ruinous otherwise: a single coupled consist fills thousands of log
 * lines a minute and buries the messages that matter (mission state, coupling gates, signal holds).
 * They are therefore OFF by default and switched on only when someone is actually looking:</p>
 *
 * <ul>
 *   <li>server start: {@code -Dmmtr.trace=true} (system property),</li>
 *   <li>at runtime: the OP command {@code trace on} / {@code trace off} from the web console.</li>
 * </ul>
 *
 * <p>State-of-the-world messages (mission armed/refused, coupling surgery, occupancy stops, turnout
 * waits) are NOT behind this gate - they fire on state changes and must always be visible.</p>
 */
public final class MmtrTrace {

	private static volatile boolean enabled = Boolean.getBoolean("mmtr.trace");

	private MmtrTrace() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
	}

	/** Print {@code message} only while the trace is on (same {@code System.out} the game log captures). */
	public static void log(String message) {
		if (enabled) {
			System.out.println(message);
		}
	}
}
