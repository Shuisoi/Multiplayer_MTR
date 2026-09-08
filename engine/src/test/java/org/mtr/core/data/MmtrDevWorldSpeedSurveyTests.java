package org.mtr.core.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signal S2 survey: loads the real development world save and prints the per-rail directional speed
 * limit distribution - how much of the real network is AWS band (<= 100 km/h) vs LZB band
 * (>= 101 km/h), which rails are asymmetric, and which LZB rails exist (longest first). Output is
 * consumed manually when planning real-machine AWS/LZB demonstration scenarios (notes/31).
 */
public final class MmtrDevWorldSpeedSurveyTests {

	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");

	@Test
	public void surveyRealWorldRailSpeedLimits() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		assertTrue(sim.rails.size() > 0, "rails loaded");

		final TreeMap<Long, Integer> band1 = new TreeMap<>();
		final TreeMap<Long, Integer> band2 = new TreeMap<>();
		int awsBoth = 0;
		int lzbAny = 0;
		int asymmetric = 0;
		final TreeMap<Double, Rail> lzbRailsByLength = new TreeMap<>();
		double longestRail = 0;

		for (final Rail rail : sim.rails) {
			final long limit1 = rail.getSpeedLimitKilometersPerHour(false);
			final long limit2 = rail.getSpeedLimitKilometersPerHour(true);
			band1.merge(limit1, 1, Integer::sum);
			band2.merge(limit2, 1, Integer::sum);
			if (limit1 <= 100 && limit2 <= 100) {
				awsBoth++;
			}
			if (limit1 >= 101 || limit2 >= 101) {
				lzbAny++;
				lzbRailsByLength.put(rail.railMath.getLength() * 1e6 + lzbRailsByLength.size(), rail); // key-safe ordering
			}
			if (limit1 != limit2) {
				asymmetric++;
			}
			longestRail = Math.max(longestRail, rail.railMath.getLength());
		}

		System.out.println("[SURVEY] rails=" + sim.rails.size() + " awsBoth(<=100/<=100)=" + awsBoth + " lzbAny(>=101 either dir)=" + lzbAny + " asymmetric=" + asymmetric + " longestRailM=" + Math.round(longestRail));
		final StringBuilder hist = new StringBuilder("[SURVEY] limit histogram (kmh -> count): dirA ");
		for (final Map.Entry<Long, Integer> e : band1.entrySet()) {
			hist.append(e.getKey()).append('=').append(e.getValue()).append(' ');
		}
		System.out.println(hist);
		final StringBuilder hist2 = new StringBuilder("[SURVEY] limit histogram dirB: ");
		for (final Map.Entry<Long, Integer> e : band2.entrySet()) {
			hist2.append(e.getKey()).append('=').append(e.getValue()).append(' ');
		}
		System.out.println(hist2);

		System.out.println("[SURVEY] LZB-band rails (>=101 either direction), longest first:");
		lzbRailsByLength.descendingMap().forEach((key, rail) -> {
			System.out.println("[SURVEY]   " + rail.getHexId() + " len=" + Math.round(rail.railMath.getLength()) + "m limitA=" + rail.getSpeedLimitKilometersPerHour(false) + " limitB=" + rail.getSpeedLimitKilometersPerHour(true));
		});
	}
}
