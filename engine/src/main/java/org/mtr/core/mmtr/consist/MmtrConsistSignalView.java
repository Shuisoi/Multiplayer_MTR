package org.mtr.core.mmtr.consist;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.MmtrRegime;
import org.mtr.core.mmtr.consist.MmtrConsistBody.OccupiedSegment;
import org.mtr.core.mmtr.consist.MmtrConsistBody.SpineLeg;

/**
 * B6: the signal-facing view of a consist body. Signal work is frozen to wiring this round (S1–S4
 * stay as they are), so this adapter exists to make the two facts the signalling layer needs
 * explicit and testable:
 *
 * <ul>
 *   <li><strong>occupancy</strong> — the whole consist interval, not just the head rail. The old
 *       motion branch reported the head, which under-reports a train crossing a rail boundary;</li>
 *   <li><strong>regime</strong> — AWS/LZB is derived from the rail the <em>manned cab</em> is on,
 *       read in the direction of travel. A pushing locomotive is therefore classified by the coach
 *       that leads, exactly as in reality.</li>
 * </ul>
 *
 * <p>Both are pure functions of the walker: nothing here decides anything about the train.</p>
 */
public final class MmtrConsistSignalView {

	private MmtrConsistSignalView() {
	}

	/** Every rail slice the consist occupies right now (a train crossing a boundary occupies both). */
	public static ObjectArrayList<OccupiedSegment> occupiedSegments(MmtrConsistWalker walker) {
		return walker.occupancy();
	}

	/** Rail the manned cab stands on — the rail the AWS/LZB unit must read; {@code null} = unmanned. */
	public static @Nullable String mannedCabRailHex(MmtrConsistWalker walker) {
		return walker.leadingRailHex();
	}

	/**
	 * The signal regime the manned cab must obey: the directional speed limit of the rail it stands
	 * on (≤ 100 km/h AWS, ≥ 101 km/h LZB). Falls back to AWS when the consist is unmanned or the
	 * direction is unreachable (limit 0), which is the conservative display.
	 */
	public static MmtrRegime regime(MmtrConsistWalker walker) {
		final double speedLimitKmh = mannedCabSpeedLimitKmh(walker);
		return speedLimitKmh <= 0 ? MmtrRegime.AWS : MmtrRegime.fromSpeedLimitKmh(speedLimitKmh);
	}

	/** Directional speed limit (km/h) of the rail the manned cab stands on; {@code 0} = unknown/unreachable. */
	public static double mannedCabSpeedLimitKmh(MmtrConsistWalker walker) {
		if (!walker.cabs().isManned()) {
			return 0;
		}
		final double arcM = walker.frontArcM();
		final SpineLeg leg = walker.spineLegAtArcM(arcM);
		final Rail rail = walker.railAtArcM(arcM);
		if (leg == null || rail == null) {
			return 0;
		}
		final double metersPerMillisecond = rail.getSpeedLimitMetersPerMillisecond(leg.entryNode());
		return metersPerMillisecond <= 0 ? 0 : metersPerMillisecond * 3600.0;
	}
}
