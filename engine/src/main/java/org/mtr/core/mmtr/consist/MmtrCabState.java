package org.mtr.core.mmtr.consist;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

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
 *
 * <p><strong>钥匙归属 (key ownership, 2026-09-10).</strong> A stabled train that is waiting for a
 * crew has no driver, yet the consist model still needs to know which end leads — so the engine
 * itself holds a <em>system key</em> ({@link KeyHolder#SYSTEM}) in the A-end cab when the stock is
 * staged from the yard manifest. That placeholder must never lock a real crew out: taking a cab
 * with a crew key displaces an unattended system key, exactly like a driver boarding a train that
 * the depot computer had been holding. Two crew members, however, can never hold the same consist
 * — the second one is refused. The holder is remembered so the server can check a driving request
 * against the key that is actually in the cab instead of trusting the client.</p>
 */
public final class MmtrCabState {

	/** Which end of the consist an end refers to. */
	public enum End { A, B }

	public enum Cab {
		/** No cab manned (no key inserted). */
		NONE,
		/** The cab at the consist's A end; its driver faces outward past the A end, so it leads with A. */
		CAB_A,
		/** The cab at the consist's B end; its driver faces outward past the B end, so it leads with B. */
		CAB_B
	}

	/**
	 * Who holds the key that makes the consist manned. {@link #NONE} when no key is inserted.
	 */
	public enum KeyHolder {
		/** No key inserted. */
		NONE,
		/** The engine's placeholder key: a staged consist waiting for a crew, or an auto run. */
		SYSTEM,
		/** A crew member's key (a player took the cab). */
		CREW
	}

	private Cab activeCab = Cab.NONE;
	private KeyHolder keyHolder = KeyHolder.NONE;
	private @Nullable UUID crewUuid;
	/**
	 * C5b: arc position of the manned cab from the consist's A end, in metres — {@link Double#NaN}
	 * while unmanned. {@code CAB_A} is arc 0 and {@code CAB_B} is the far end, but a coupling leaves
	 * the crew in a cab that is now INSIDE the formation (the locomotive that coupled onto a rake
	 * becomes the rear unit), and the two-end model could not express that: the train had to be left
	 * unmanned. The arc says where the key actually is; the direction of travel still comes from the
	 * cab's facing ({@link #activeCab()}).
	 */
	private double cabArcM = Double.NaN;

	public Cab activeCab() {
		return activeCab;
	}

	/** C5b: arc position of the manned cab from the A end, or {@code NaN} when unmanned. */
	public double cabArcM() {
		return cabArcM;
	}

	/** C5b: whether the manned cab is inside the formation rather than at one of its two ends. */
	public boolean isInteriorCab() {
		return isManned() && cabArcM > 1e-6 && !Double.isNaN(cabArcM);
	}

	public KeyHolder keyHolder() {
		return keyHolder;
	}

	/** The crew member whose key is in the cab, or {@code null} (system key / operator command). */
	public @Nullable UUID crewUuid() {
		return crewUuid;
	}

	public boolean isManned() {
		return activeCab != Cab.NONE;
	}

	/** Whether the engine's own placeholder key is in the cab (no player behind it). */
	public boolean isSystemKey() {
		return isManned() && keyHolder == KeyHolder.SYSTEM;
	}

	/** Whether a player's key is in the cab. */
	public boolean isCrewKey() {
		return isManned() && keyHolder == KeyHolder.CREW;
	}

	/**
	 * Insert the key in {@code cab}. The train must be at rest and the driver must be standing at
	 * that cab; a consist can hold exactly one key at a time.
	 *
	 * <p>An unattended <em>system</em> key is displaced (the crew takes over from the engine); a key
	 * already held by another crew member is not. {@code crewUuid} identifies the player taking the
	 * cab and may be {@code null} for an operator command.</p>
	 *
	 * @return whether the cab is now manned by the crew
	 */
	public boolean insertKey(Cab cab, boolean trainStopped, boolean driverAtCab) {
		return insertKey(cab, trainStopped, driverAtCab, null);
	}

	/** @see #insertKey(Cab, boolean, boolean) */
	public boolean insertKey(Cab cab, boolean trainStopped, boolean driverAtCab, @Nullable UUID crewUuid) {
		if (cab == Cab.NONE || !trainStopped || !driverAtCab) {
			return false;
		}
		// A system key is a placeholder: the crew may always take the cab from it. A key that is
		// already in a crew member's hands is not displaced - only that same member may re-enter.
		if (isCrewKey() && (crewUuid == null || !crewUuid.equals(this.crewUuid))) {
			return false;
		}
		activeCab = cab;
		cabArcM = Double.NaN; // a named end cab; interior cabs use insertKeyAtArc
		keyHolder = KeyHolder.CREW;
		this.crewUuid = crewUuid;
		return true;
	}

	/**
	 * C5b: insert the key in an <em>interior</em> cab at {@code arcM} from the A end, with the driver
	 * facing {@code towardA}. Used by the coupling surgery, which leaves the crew in the locomotive's
	 * cab even though that cab is now inside the merged formation.
	 */
	public boolean insertKeyAtArc(double arcM, boolean towardA, boolean trainStopped, boolean driverAtCab, @Nullable UUID crewUuid) {
		if (!insertKey(towardA ? Cab.CAB_A : Cab.CAB_B, trainStopped, driverAtCab, crewUuid)) {
			return false;
		}
		cabArcM = arcM;
		return true;
	}

	/**
	 * The engine stages its own placeholder key (yard manifest spawn / auto run). Refused while any
	 * key is already in the cab — a running crew always wins over the engine.
	 *
	 * @return whether the system key is now in {@code cab}
	 */
	public boolean insertSystemKey(Cab cab, boolean trainStopped) {
		if (cab == Cab.NONE || isManned() || !trainStopped) {
			return false;
		}
		activeCab = cab;
		cabArcM = Double.NaN;
		keyHolder = KeyHolder.SYSTEM;
		crewUuid = null;
		return true;
	}

	/**
	 * Pull the key out of the active cab. Always legal (a driver can pull the key while the train is
	 * still rolling); the caller must drop traction and brake to a stand when that happens.
	 *
	 * @return whether a key was actually removed
	 */
	public boolean removeKey() {
		return removeKey(null);
	}

	/**
	 * Pull the key out of the active cab, as {@code crewUuid}.
	 *
	 * <p>A crew member can only pull their own key; the engine's placeholder key can only be released
	 * by the engine itself or by an operator command ({@code crewUuid == null}).</p>
	 *
	 * @return whether a key was actually removed
	 */
	public boolean removeKey(@Nullable UUID crewUuid) {
		if (!isManned()) {
			return false;
		}
		// A crew member can only pull the key they themselves inserted (and never the engine's
		// placeholder key); an operator command ({@code null}) may release whatever is in the cab.
		if (crewUuid != null && (!isCrewKey() || !crewUuid.equals(this.crewUuid))) {
			return false;
		}
		activeCab = Cab.NONE;
		keyHolder = KeyHolder.NONE;
		this.crewUuid = null;
		cabArcM = Double.NaN;
		return true;
	}

	/** Whether {@code crewUuid} holds the key of this consist ({@code null} = any crew / operator). */
	public boolean isHeldBy(@Nullable UUID crewUuid) {
		return isManned() && (crewUuid == null || isCrewKey() && crewUuid.equals(this.crewUuid));
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
		// Walking to the other cab leaves an interior cab behind: the crew ends up at a named end.
		cabArcM = Double.NaN;
		return true;
	}

	/**
	 * The consist end that leads (the front) while the current cab is manned; {@code null} = unmanned.
	 *
	 * <p>Physical rule: a cab sits at one <em>end</em> of the consist and its driver looks
	 * <strong>outward</strong> past that end, so driving from it makes that same end lead. The A-end
	 * cab therefore leads with the A end, the B-end cab with the B end. (An earlier version had this
	 * inverted, which made a driver in the A-end cab move the train tail-first — exactly the reverse
	 * running the project forbids.)</p>
	 */
	public @Nullable End leadingEnd() {
		return switch (activeCab) {
			case CAB_A -> End.A;
			case CAB_B -> End.B;
			case NONE -> null;
		};
	}

	/** The consist end that trails (the rear) while the current cab is manned; {@code null} = unmanned. */
	public @Nullable End trailingEnd() {
		return switch (activeCab) {
			case CAB_A -> End.B;
			case CAB_B -> End.A;
			case NONE -> null;
		};
	}

	/** Whether the manned cab drives the consist toward {@code end}. */
	public boolean travelsToward(End end) {
		return leadingEnd() == end;
	}

	/** The cab that must be manned to drive toward {@code end}. */
	public static Cab cabFor(End end) {
		return end == End.A ? Cab.CAB_A : Cab.CAB_B;
	}
}
