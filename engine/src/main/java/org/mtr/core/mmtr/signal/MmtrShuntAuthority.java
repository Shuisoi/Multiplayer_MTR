package org.mtr.core.mmtr.signal;

/**
 * 调车授权 / 呼唤显示 (subsidiary signal authority) — C3a.
 *
 * <p>Real basis (design §5.2, {@code 参考-英铁AWS与TPWS机制.md} §7): coupling has to let a locomotive
 * pass a signal at <em>danger</em> into an already-occupied section. That is not "disable a data
 * limit": it is a <strong>subsidiary aspect</strong> — the main head stays red and a small calling-on
 * / shunt-ahead display authorises the move, at low speed, prepared to stop short of any obstruction.
 * Under such an aspect AWS is <em>suppressed</em> (a warning horn for a movement the signal just
 * authorised would be wrong), and the TPWS-equivalent protection must not judge a SPAD for it.</p>
 *
 * <p>One authority authorises exactly one train for one movement (invariant U8). It covers the rail
 * the move starts on and the target rail (the occupied one), and it dies with its window — after that
 * the section is back to "one train per section".</p>
 *
 * <p><strong>UNVERIFIED (O6):</strong> the authority standard (GERT8075) is a charged document and
 * was unreachable, so the aspect table below is implemented as a configurable table with the common
 * practice value (15 mph ≈ 25 km/h) and marked as unverified. Getting the text later changes this
 * table only, never the architecture.</p>
 */
public final class MmtrShuntAuthority {

	/** The two subsidiary aspects MMTR models. */
	public enum Kind {
		/**
		 * 调车副显示 (subsidiary shunt): the classic "pass the signal at danger and shunt into the
		 * occupied section" authority.
		 */
		SUBSIDIARY_SHUNT(true, false, 25),
		/**
		 * 呼唤显示 (calling-on): authority to enter an occupied platform/section and draw up to the
		 * train already standing there.
		 */
		CALLING_ON(true, false, 25);

		private final boolean suppressAws;
		private final boolean enforceProtection;
		private final double defaultSpeedLimitKmh;

		Kind(boolean suppressAws, boolean enforceProtection, double defaultSpeedLimitKmh) {
			this.suppressAws = suppressAws;
			this.enforceProtection = enforceProtection;
			this.defaultSpeedLimitKmh = defaultSpeedLimitKmh;
		}

		/** Whether AWS warnings are suppressed under this aspect (real semantics, not an omission). */
		public boolean suppressesAws() {
			return suppressAws;
		}

		/** Whether the TPWS-equivalent protection may still judge a SPAD/overrun under this aspect. */
		public boolean enforcesProtection() {
			return enforceProtection;
		}

		/** The aspect's default movement speed limit in km/h (UNVERIFIED, see the class comment). */
		public double getDefaultSpeedLimitKmh() {
			return defaultSpeedLimitKmh;
		}
	}

	private final Kind kind;
	private final long vehicleId;
	private final String grantRailHex;
	private final String targetRailHex;
	private final double speedLimitKmh;
	private final long grantedAtMillis;
	private final long expiresAtMillis;

	MmtrShuntAuthority(Kind kind, long vehicleId, String grantRailHex, String targetRailHex, double speedLimitKmh, long grantedAtMillis, long expiresAtMillis) {
		this.kind = kind;
		this.vehicleId = vehicleId;
		this.grantRailHex = grantRailHex == null ? "" : grantRailHex;
		this.targetRailHex = targetRailHex == null ? "" : targetRailHex;
		this.speedLimitKmh = speedLimitKmh;
		this.grantedAtMillis = grantedAtMillis;
		this.expiresAtMillis = expiresAtMillis;
	}

	public Kind getKind() {
		return kind;
	}

	/** The one train this authority is valid for (U8). */
	public long getVehicleId() {
		return vehicleId;
	}

	/** Rail the authorised movement starts from. */
	public String getGrantRailHex() {
		return grantRailHex;
	}

	/** The occupied rail the movement is authorised to enter. */
	public String getTargetRailHex() {
		return targetRailHex;
	}

	/** Movement speed limit in km/h. */
	public double getSpeedLimitKmh() {
		return speedLimitKmh;
	}

	public long getGrantedAtMillis() {
		return grantedAtMillis;
	}

	public long getExpiresAtMillis() {
		return expiresAtMillis;
	}

	public boolean isActiveAt(long nowMillis) {
		return nowMillis < expiresAtMillis;
	}

	public long remainingMillis(long nowMillis) {
		return Math.max(0, expiresAtMillis - nowMillis);
	}

	/** Whether the authority covers {@code railHex} (the movement's own rails). */
	public boolean covers(String railHex) {
		return railHex != null && (railHex.equals(grantRailHex) || railHex.equals(targetRailHex));
	}

	/** Whether AWS is suppressed under this aspect. */
	public boolean suppressesAws() {
		return kind.suppressesAws();
	}

	/** Whether the protection layer may still judge a SPAD/overrun under this aspect. */
	public boolean enforcesProtection() {
		return kind.enforcesProtection();
	}

	@Override
	public String toString() {
		return kind.name() + " vehicle=" + vehicleId + " " + grantRailHex + " -> " + targetRailHex + " @" + speedLimitKmh + "km/h";
	}
}
