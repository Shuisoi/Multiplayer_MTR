package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority.Kind;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 调车授权登记表 (C3a): which train currently holds a subsidiary-aspect authority, for which movement.
 *
 * <p>At most one authority per train (U8) — a second grant replaces the first, and a grant for one
 * train never authorises another. Expiry is lazy: every read validates against the clock, so a stale
 * authority can never wedge the section, exactly like {@link org.mtr.core.mmtr.point.MmtrPointAuthority}
 * grant windows.</p>
 *
 * <p>The registry is a pure data plane: it never moves a train. The vehicle reads it to decide whether
 * occupancy on an authorised rail blocks it, whether AWS is suppressed and whether protection may
 * judge a SPAD; the yard reads it to allow a second train in the section.</p>
 */
public final class MmtrShuntAuthorityRegistry {

	/** Default validity of a granted movement (ms) when the caller does not say. */
	public static final long DEFAULT_VALIDITY_MILLIS = 5 * 60 * 1000L;

	private final LongSupplier clock;
	private final Map<Long, MmtrShuntAuthority> byVehicle = new HashMap<>();

	public MmtrShuntAuthorityRegistry(LongSupplier clock) {
		this.clock = clock;
	}

	/**
	 * Authorise one movement. The aspect's default speed limit is used when {@code speedLimitKmh <= 0}.
	 *
	 * @return the granted authority
	 */
	public MmtrShuntAuthority grant(long vehicleId, @Nullable String grantRailHex, @Nullable String targetRailHex, Kind kind, double speedLimitKmh, long validityMillis) {
		final long now = clock.getAsLong();
		final long validity = validityMillis <= 0 ? DEFAULT_VALIDITY_MILLIS : validityMillis;
		final MmtrShuntAuthority authority = new MmtrShuntAuthority(
				kind == null ? Kind.SUBSIDIARY_SHUNT : kind,
				vehicleId,
				grantRailHex,
				targetRailHex,
				speedLimitKmh > 0 ? speedLimitKmh : (kind == null ? Kind.SUBSIDIARY_SHUNT : kind).getDefaultSpeedLimitKmh(),
				now,
				now + validity
		);
		byVehicle.put(vehicleId, authority);
		return authority;
	}

	/** The live authority of {@code vehicleId}, or {@code null} (expired authorities are dropped). */
	public @Nullable MmtrShuntAuthority active(long vehicleId) {
		final MmtrShuntAuthority authority = byVehicle.get(vehicleId);
		if (authority == null) {
			return null;
		}
		if (!authority.isActiveAt(clock.getAsLong())) {
			byVehicle.remove(vehicleId);
			return null;
		}
		return authority;
	}

	/** Withdraw a train's authority (coupling done, driver cancelled, terminal mission). */
	public boolean revoke(long vehicleId) {
		return byVehicle.remove(vehicleId) != null;
	}

	/** Withdraw every authority (teardown / tests). */
	public void revokeAll() {
		byVehicle.clear();
	}

	/**
	 * Whether {@code railHex} may hold more than one train right now because an authorised movement
	 * covers it (permissive working, GKRT0044). Expired authorities do not count.
	 */
	public boolean allowsCoexistence(String railHex) {
		if (railHex == null || railHex.isEmpty()) {
			return false;
		}
		final long now = clock.getAsLong();
		byVehicle.values().removeIf(authority -> !authority.isActiveAt(now));
		for (final MmtrShuntAuthority authority : byVehicle.values()) {
			if (authority.covers(railHex)) {
				return true;
			}
		}
		return false;
	}

	/** Every live authority, for the ops UI. */
	public ObjectArrayList<MmtrShuntAuthority> snapshot() {
		final long now = clock.getAsLong();
		byVehicle.values().removeIf(authority -> !authority.isActiveAt(now));
		return new ObjectArrayList<>(byVehicle.values());
	}

	public int size() {
		return snapshot().size();
	}
}
