package org.mtr.core.mmtr.consist;

/**
 * C1: one coupling endpoint of a {@link MmtrUnit} ("连挂端点").
 *
 * <p>A coupler is the <em>semantic</em> seam where coupling/uncoupling is allowed — it is not a
 * geometric boundary (MTR can cut a car list anywhere). An 8-car EMU unit therefore has couplers
 * only at its two ends, while a rake of eight single-car wagon units has a coupler between every
 * pair. Invariant U1 (a unit is indivisible) and U6 (cuts only at seams) follow structurally from
 * that: there is simply no coupler inside an EMU unit.</p>
 *
 * <p><b>Identity is {@code (unitId, end)}</b> — {@link #equals(Object)} and {@link #hashCode()} use
 * nothing else, so two coupler objects describing the same endpoint are interchangeable regardless
 * of their declared type or air state. The <em>connection state</em> is deliberately not stored
 * here: it is derived from the {@link MmtrFormation} chain (a coupler is connected iff it is not one
 * of the formation's two free ends), which makes "one coupler has at most one partner" (U3) a
 * structural property instead of something that can drift out of sync.</p>
 */
public final class MmtrCoupler {

	public enum End {
		/** The end next to the unit's first car. */
		A,
		/** The end next to the unit's last car. */
		B
	}

	public enum Type {
		/** Reversible multiple working: traction in parallel, brake pipe and control continuity. */
		POWERED_CONSIST,
		/** Haulage: the trailing unit contributes no traction, only brake pipe continuity. */
		HAULED
	}

	private final String unitId;
	private final End end;
	private final Type type;
	private final boolean airCharged;

	public MmtrCoupler(String unitId, End end, Type type, boolean airCharged) {
		if (unitId == null || unitId.isEmpty()) {
			throw new IllegalArgumentException("coupler needs a unit id");
		}
		if (end == null || type == null) {
			throw new IllegalArgumentException("coupler needs an end and a type");
		}
		this.unitId = unitId;
		this.end = end;
		this.type = type;
		this.airCharged = airCharged;
	}

	public String unitId() {
		return unitId;
	}

	public End end() {
		return end;
	}

	public Type type() {
		return type;
	}

	/** Whether the attached unit starts with a charged brake pipe (a hauled wagon usually does not). */
	public boolean airCharged() {
		return airCharged;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof final MmtrCoupler coupler)) {
			return false;
		}
		return end == coupler.end && unitId.equals(coupler.unitId);
	}

	@Override
	public int hashCode() {
		return 31 * unitId.hashCode() + end.hashCode();
	}

	@Override
	public String toString() {
		return unitId + ":" + end;
	}
}
