package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.tool.Utilities;

public class VehiclePosition {

	private final ObjectArrayList<BlockedSegment> blockedSegments = new ObjectArrayList<>();

	public void addSegment(double startDistance, double endDistance, long id) {
		blockedSegments.add(new BlockedSegment(startDistance, endDistance, id));
	}

	public double getClosestOverlap(double startDistance, double endDistance, boolean reversePositions, long id) {
		double closestOverlap = Double.MAX_VALUE;
		boolean valueSet = false;

		for (final BlockedSegment blockedSegment : blockedSegments) {
			if (id != blockedSegment.id && Utilities.isIntersecting(startDistance, endDistance, blockedSegment.startDistance, blockedSegment.endDistance)) {
				if (reversePositions) {
					closestOverlap = Utilities.clampSafe(endDistance - blockedSegment.endDistance, 0, closestOverlap);
				} else {
					closestOverlap = Utilities.clampSafe(blockedSegment.startDistance - startDistance, 0, closestOverlap);
				}
				valueSet = true;
			}
		}

		return valueSet ? closestOverlap : -1;
	}

	/**
	 * C5: every blocked interval on this rail except {@code id}'s own, as {@code [start, end]} pairs in
	 * this rail's ordered-position-1 distance space. Callers that need the exact geometry (a consist
	 * body measuring the gap to the train it is coupling to) work from these intervals directly.
	 */
	public ObjectArrayList<double[]> segmentsExcluding(long id) {
		final ObjectArrayList<double[]> out = new ObjectArrayList<>(blockedSegments.size());
		for (final BlockedSegment blockedSegment : blockedSegments) {
			if (id != blockedSegment.id) {
				out.add(new double[]{blockedSegment.startDistance, blockedSegment.endDistance});
			}
		}
		return out;
	}

	private record BlockedSegment(double startDistance, double endDistance, long id) {
	}
}
