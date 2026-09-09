package org.mtr.core.mmtr.route;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.point.MmtrPointAuthority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进路登记表 (S5): the live {@link MmtrRoute} per train, and the lookup the signal layer needs —
 * "which route, if any, is set over this rail?".
 *
 * <p>Deliberately a derived view, not a second state machine: {@link #refresh(long, MmtrPointAuthority)}
 * recomputes establishment from the authoritative {@link MmtrPointAuthority} (the same source the
 * walker elects from), so the two can never disagree. Requests/grants/queueing stay in the point
 * authority; this registry only names and exposes the movement they belong to.</p>
 */
public final class MmtrRouteRegistry {

	private final Map<Long, MmtrRoute> byVehicle = new HashMap<>();

	/**
	 * Install the route of a train. A re-plan (different movement) replaces the previous route; a
	 * repeat of the SAME movement keeps the live object, so a mission whose self-arm retries while
	 * it waits for the interlocking does not churn the route identity every tick.
	 */
	public MmtrRoute request(MmtrRoute route) {
		final MmtrRoute existing = byVehicle.get(route.getVehicleId());
		if (existing != null && existing.sameMovement(route)) {
			return existing;
		}
		byVehicle.put(route.getVehicleId(), route);
		return route;
	}

	public @Nullable MmtrRoute route(long vehicleId) {
		return byVehicle.get(vehicleId);
	}

	/** Drop a train's route (terminal mission, cancel, vehicle deletion). */
	public boolean release(long vehicleId) {
		return byVehicle.remove(vehicleId) != null;
	}

	/**
	 * Recompute a route's establishment state against the turnout authority. A route is SET when
	 * every turnout it still needs is granted to its owner; otherwise it is PENDING and the reason
	 * names the first OUTSTANDING blocking point (operator park / another holder / queue). A crossed
	 * turnout is neither required nor reported - its hold was released at the crossing, so naming it
	 * would send the operator looking at a point the train has already left.
	 */
	public void refresh(long vehicleId, MmtrPointAuthority authority) {
		final MmtrRoute route = byVehicle.get(vehicleId);
		if (route == null) {
			return;
		}
		if (route.getForks().isEmpty()) {
			route.applyState(true, "no turnout in this route");
			return;
		}
		if (route.allForksCrossed()) {
			// Route locking releases sectionally: once the train has crossed every turnout, nothing is
			// left to hold - the movement stays set over the rails ahead.
			route.applyState(true, "all turnouts crossed");
			return;
		}
		final ObjectArrayList<String[]> outstanding = new ObjectArrayList<>();
		for (final String[] fork : route.getForks()) {
			if (!route.isForkCrossed(fork)) {
				outstanding.add(fork);
			}
		}
		boolean allGranted = true;
		for (final String[] fork : outstanding) {
			if (!authority.isGrantedTo(Long.parseLong(fork[0]), Long.parseLong(fork[1]), Long.parseLong(fork[2]), fork[3], route.getOwner())) {
				allGranted = false;
				break;
			}
		}
		route.applyState(allGranted, allGranted
			? "all turnouts held by " + route.getOwner()
			: MmtrRunPlanner.describeForkWait(outstanding, authority, route.getOwner()));
	}

	/**
	 * Every SET route running over {@code railHex}, ordered by vehicle id (deterministic feed /
	 * signal output). PENDING routes are not returned: an unset route does not authorise a proceed
	 * aspect.
	 */
	public ObjectArrayList<MmtrRoute> routesOverRail(@Nullable String railHex) {
		final ObjectArrayList<MmtrRoute> out = new ObjectArrayList<>();
		if (railHex == null || railHex.isEmpty()) {
			return out;
		}
		for (final MmtrRoute route : sorted()) {
			if (route.isEstablished() && route.coversRail(railHex)) {
				out.add(route);
			}
		}
		return out;
	}

	/** The first SET route over {@code railHex} (lowest vehicle id), or null. */
	public @Nullable MmtrRoute routeOverRail(@Nullable String railHex) {
		final ObjectArrayList<MmtrRoute> routes = routesOverRail(railHex);
		return routes.isEmpty() ? null : routes.get(0);
	}

	/** Every live route, ordered by vehicle id (ops feed). */
	public ObjectArrayList<MmtrRoute> snapshot() {
		return new ObjectArrayList<>(sorted());
	}

	/**
	 * A2 client mirror: rail hex -&gt; the next rail(s) of every SET MAIN route running over it, in route
	 * order. A route may traverse the SAME rail twice (牵出—推进 / 尽头换向: the real aassdd shunt's
	 * plan had one rail twice), so the value is a list and the consumer picks the entry that shares the
	 * node it is walking toward — the same rule the engine's {@code MmtrSignalAspect} applies. A plain
	 * rail -&gt; rail map would silently keep only the outbound leg and lose the narrowing on the way
	 * back.
	 */
	public Object2ObjectOpenHashMap<String, ObjectArrayList<String>> setMainRouteNextRails() {
		final Object2ObjectOpenHashMap<String, ObjectArrayList<String>> out = new Object2ObjectOpenHashMap<>();
		for (final MmtrRoute route : sorted()) {
			if (!route.isEstablished() || route.getKind() != MmtrRoute.Kind.MAIN) {
				continue;
			}
			final ObjectArrayList<String> rails = route.getRailHexes();
			for (int i = 0; i + 1 < rails.size(); i++) {
				final String from = rails.get(i);
				final String to = rails.get(i + 1);
				final ObjectArrayList<String> nexts = out.computeIfAbsent(from, key -> new ObjectArrayList<>());
				if (!nexts.contains(to)) {
					nexts.add(to);
				}
			}
		}
		return out;
	}

	/** A2 client mirror: rails that are the entry of a PENDING route — their signal shows danger. */
	public ObjectOpenHashSet<String> pendingEntryRails() {
		final ObjectOpenHashSet<String> out = new ObjectOpenHashSet<>();
		for (final MmtrRoute route : sorted()) {
			if (!route.isEstablished()) {
				final String entry = route.getEntryRailHex();
				if (entry != null && !entry.isEmpty()) {
					out.add(entry);
				}
			}
		}
		return out;
	}

	public int size() {
		return byVehicle.size();
	}

	public void clear() {
		byVehicle.clear();
	}

	private List<MmtrRoute> sorted() {
		final List<MmtrRoute> routes = new ArrayList<>(byVehicle.values());
		routes.sort((a, b) -> Long.compare(a.getVehicleId(), b.getVehicleId()));
		return routes;
	}
}
