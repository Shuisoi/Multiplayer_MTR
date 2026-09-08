package org.mtr.core.mmtr.consist;

import org.jspecify.annotations.Nullable;

/**
 * 双驾驶室换端状态机 (double-cab change-ends state machine).
 *
 * <p>User model (2026-09-08): the cab is "the point on the train where the driver is fixed".
 * Changing ends is a <em>crew action</em>, not a vehicle manoeuvre — the train comes to rest, the
 * driver pulls the key out of the current cab, walks to the other cab and inserts the key there.
 * The train itself never turns; only the active cab changes, and the active cab's facing is what
 * decides the direction of travel.</p>
 *
 * <p>This class is pure: the caller passes the facts it cannot know (is the train stopped, is the
 * driver standing at that cab). Everything about the world stays outside.</p>
 *
 * <p>Two rules worth stating explicitly:</p>
 * <ul>
 *   <li>{@link #insertKey} is the only way to become manned, and it requires the train at rest —
 *       so 换端 can never start a reverse run in motion (keeps the no-reverse-gear red line);</li>
 *   <li>{@link #removeKey} is legal at any time. Pulling the key while moving drops the driving
 *       authority immediately; the caller must then cut traction and service-brake to a stand.</li>
 * </ul>
 */
public final class MmtrCabState {

	/** Which end of the consist an end refers to. */
	public enum End { A, B }

	public enum Cab {
		/** No cab manned (no key inserted). */
		NONE,
		/** The cab at the consist's A end; it faces A -&gt; B, so a manned CAB_A drives toward the B end. */
		CAB_A,
		/** The cab at the consist's B end; it faces B -&gt; A, so a manned CAB_B drives toward the A end. */
		CAB_B
	}

	private Cab activeCab = Cab.NONE;

	public Cab activeCab() {
		return activeCab;
	}

	public boolean isManned() {
		return activeCab != Cab.NONE;
	}

	/**
	 * Insert the key in {@code cab}. The train must be at rest and the driver must be standing at
	 * that cab; a consist can hold exactly one key at a time.
	 * @return whether the cab is now manned
	 */
	public boolean insertKey(Cab cab, boolean trainStopped, boolean driverAtCab) {
		if (cab == Cab.NONE || isManned() || !trainStopped || !driverAtCab) {
			return false;
		}
		activeCab = cab;
		return true;
	}

	/**
	 * Pull the key out of the active cab. Always legal (a driver can pull the key while the train is
	 * still rolling); the caller must drop traction and brake to a stand when that happens.
	 * @return whether a key was actually removed
	 */
	public boolean removeKey() {
		if (!isManned()) {
			return false;
		}
		activeCab = Cab.NONE;
		return true;
	}

	/**
	 * The full 换端 action as one call: the train is at rest and a driver is manning a cab, so he
	 * walks to the other cab and takes over there. The consist geometry is untouched — this is the
	 * whole point of the model.
	 * @return whether the active cab changed
	 */
	public boolean changeEnds(boolean trainStopped) {
		if (!isManned() || !trainStopped) {
			return false;
		}
		activeCab = activeCab == Cab.CAB_A ? Cab.CAB_B : Cab.CAB_A;
		return true;
	}

	/** The consist end that leads (the front) while the current cab is manned; {@code null} = unmanned. */
	public @Nullable End leadingEnd() {
		return switch (activeCab) {
			case CAB_A -> End.B;
			case CAB_B -> End.A;
			case NONE -> null;
		};
	}

	/** The consist end that trails (the rear) while the current cab is manned; {@code null} = unmanned. */
	public @Nullable End trailingEnd() {
		return switch (activeCab) {
			case CAB_A -> End.A;
			case CAB_B -> End.B;
			case NONE -> null;
		};
	}

	/** Whether the manned cab drives the consist toward {@code end}. */
	public boolean travelsToward(End end) {
		return leadingEnd() == end;
	}

	/** The cab that must be manned to drive toward {@code end}. */
	public static Cab cabFor(End end) {
		return end == End.B ? Cab.CAB_A : Cab.CAB_B;
	}
}
