package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.mmtr.signal.MmtrSignalAspect;
import org.mtr.core.simulation.Simulator;

/**
 * 联锁诊断报告 (interlocking report): one human-readable block that answers "what does the engine
 * think about this train's movement right now?" — the route's state, every turnout it still needs
 * (and who holds it), the aspect the signal layer would show for each rail of the route, and the
 * exact narrowing the clients were told. Built for the in-game verification pass: the operator can
 * compare what the lights show with what the engine says, without reading JSON feeds.
 *
 * <p>Pure projection: nothing here mutates or reserves anything.</p>
 */
public final class MmtrInterlockReport {

	private MmtrInterlockReport() {
	}

	/** @return a multi-line report for one train (or a note that it is unknown). */
	public static String describe(Simulator simulator, long vehicleId) {
		final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
		if (vehicle == null) {
			return "[interlock] 找不到车辆 " + vehicleId;
		}
		final StringBuilder out = new StringBuilder();
		final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		final String railHex = walker == null ? null : walker.railHex();
		final MmtrRoute route = simulator.mmtrRoutes.route(vehicleId);
		out.append("[interlock] vehicle=").append(vehicleId)
			.append(" rail=").append(shortHex(railHex))
			.append(" next=").append(shortHex(walker == null || walker.peekNextRail() == null ? null : walker.peekNextRail().getHexId()))
			.append(" route=").append(route == null ? "none" : route.getKind() + "/" + (route.isEstablished() ? "SET" : "PENDING"));
		if (route == null) {
			out.append(" (无进路：自由驾驶，信号按占用链显示)");
			return out.toString();
		}
		out.append(" target=").append(shortHex(route.getTargetRailHex()))
			.append(" rails=").append(route.getRailHexes().size())
			.append(" forks=").append(route.getForks().size());
		if (!route.isEstablished()) {
			out.append("\n  PENDING: ").append(route.getStateReason());
		}

		// Every turnout the route still needs: who holds it, whether the operator parked it.
		if (!route.getForks().isEmpty()) {
			out.append("\n  turnouts:");
			for (final String[] fork : route.getForks()) {
				final long x = Long.parseLong(fork[0]);
				final long y = Long.parseLong(fork[1]);
				final long z = Long.parseLong(fork[2]);
				out.append("\n    ").append(x).append(',').append(y).append(',').append(z)
					.append(" via=").append(shortHex(fork[3]))
					.append(" wantLeg=").append(fork[4])
					.append(route.isForkCrossed(fork) ? " 已越过" : " 待过")
					.append(" | ").append(simulator.mmtrPointAuthority.state(x, y, z, fork[3]));
			}
		}

		// What the signal layer shows for every rail of the route, and what the clients were told.
		final MmtrSignalAspect aspectView = simulator.mmtrSignalAspectView();
		final ObjectArrayList<String> rails = route.getRailHexes();
		out.append("\n  aspects:");
		for (final String hex : rails) {
			out.append(' ').append(shortHex(hex)).append('=').append(aspectView.aspectOf(hex));
		}
		final var mirror = simulator.mmtrRoutes.setMainRouteNextRails();
		out.append("\n  mirror(客户端收窄):");
		boolean any = false;
		for (final String hex : rails) {
			final ObjectArrayList<String> nexts = mirror.get(hex);
			if (nexts == null || nexts.isEmpty()) {
				continue;
			}
			any = true;
			out.append(' ').append(shortHex(hex)).append("->");
			for (int i = 0; i < nexts.size(); i++) {
				out.append(i == 0 ? "" : "|").append(shortHex(nexts.get(i)));
			}
		}
		if (!any) {
			out.append(" (无：调车进路或未设好)");
		}
		final ObjectArrayList<String> pendingEntries = new ObjectArrayList<>(simulator.mmtrRoutes.pendingEntryRails());
		out.append("\n  pendingEntryRails: ").append(pendingEntries.isEmpty() ? "无" : shortHexes(pendingEntries));
		return out.toString();
	}

	/** A one-line summary of every live route (interlock all). */
	public static String describeAll(Simulator simulator) {
		final ObjectArrayList<MmtrRoute> routes = simulator.mmtrRoutes.snapshot();
		if (routes.isEmpty()) {
			return "[interlock] 当前没有任何进路（所有信号按占用链显示）";
		}
		final StringBuilder out = new StringBuilder("[interlock] " + routes.size() + " 条进路");
		for (final MmtrRoute route : routes) {
			out.append("\n  v").append(route.getVehicleId())
				.append(' ').append(route.getKind())
				.append('/').append(route.isEstablished() ? "SET" : "PENDING")
				.append(" entry=").append(shortHex(route.getEntryRailHex()))
				.append(" target=").append(shortHex(route.getTargetRailHex()))
				.append(" rails=").append(route.getRailHexes().size())
				.append(" forks=").append(route.getForks().size());
			if (!route.isEstablished()) {
				out.append(" | ").append(route.getStateReason());
			}
		}
		return out.toString();
	}

	private static String shortHex(@Nullable String hex) {
		if (hex == null || hex.isEmpty()) {
			return "-";
		}
		final int from = Math.max(0, hex.length() - 8);
		return hex.substring(from);
	}

	private static String shortHexes(ObjectArrayList<String> hexes) {
		final StringBuilder out = new StringBuilder();
		for (final String hex : hexes) {
			out.append(out.length() == 0 ? "" : ", ").append(shortHex(hex));
		}
		return out.toString();
	}
}
