package org.mtr.mod;

/**
 * MMTR port helper: the modern engine renamed its clamp utilities to package-private
 * clampSafe, so the mod keeps its own overloads for the legacy MathUtils.clamp(...) calls.
 */
public final class MathUtils {

	private MathUtils() {
	}

	public static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
	public static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
	public static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
	public static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
