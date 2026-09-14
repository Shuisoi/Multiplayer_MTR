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
			.append(" route=").append(route == null ? "none" : route.getKind() + "/" + (route.isEstablished() ? "SET" : "PENDING"))
			.append(" blockHeld=").append(vehicle.isMmtrBlockHeldFromSync());
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
		// T5：**计划 / 实际**并排 —— 计划时刻来自任务，实际是这条进路被申请的时刻；
		// 计划若已漂移，冲突裁决里它已经不占先（notes/128 的漂移退化），这里一并标出来。
		out.append("\n  计划/实际: ").append(describePlan(route));

		// Every turnout the route still needs: who holds it, whether the operator parked it.
		if (!route.getForks().isEmpty()) {
			out.append("\n  turnouts:");
			final java.util.HashSet<String> nearestPassPerNode = new java.util.HashSet<>();
			for (final String[] fork : route.getForks()) {
				final long x = Long.parseLong(fork[0]);
				final long y = Long.parseLong(fork[1]);
				final long z = Long.parseLong(fork[2]);
				final boolean crossed = route.isForkCrossed(fork);
				/*
				 * 「同一处道岔在一趟里走两次」时，只有**最先要过的那一程**参与判定（notes/137）——
				 * 报告必须把这件事说出来，否则操作者会看到"两行要互斥的两个位"而以为引擎在自相矛盾。
				 */
				final boolean nearestPass = nearestPassPerNode.add(x + "," + y + "," + z);
				/*
				 * **这条腿要的是哪一位**（0/1）：进路判定与道岔位置比的正是它。不打印出来的话，
				 * "为什么 PENDING" 只能靠人把 (进向, 腿号) 在脑子里折成位置 —— 那正是排查最慢的一步
				 * （notes/134 的教训：看不见的状态修不好）。
				 */
				final int demand = simulator.mmtrPointAuthority.turnoutDemand(x, y, z, fork[3], Integer.parseInt(fork[4]));
				out.append("\n    ").append(x).append(',').append(y).append(',').append(z)
					.append(" via=").append(shortHex(fork[3]))
					.append(" wantLeg=").append(fork[4])
					.append(demand == Integer.MIN_VALUE ? " needPos=（不存在）" : " needPos=" + demand)
					.append(crossed ? " 已越过" : " 待过")
					.append(crossed || nearestPass ? "" : "（同节点后一程：等前一程过了再算）")
					.append(" | ").append(simulator.mmtrPointAuthority.state(x, y, z, fork[3]));
			}
			// 车辆此刻**正在申请**的那一组（原子集）：它与上面那张表就是"想要"与"拿到"的两半。
			final ObjectArrayList<String[]> pendingPointOps = vehicle.getMmtrPendingPointOps();
			out.append("\n  pendingRequests:").append(pendingPointOps.isEmpty() ? " 无" : "");
			for (final String[] op : pendingPointOps) {
				out.append("\n    ").append(op[0]).append(',').append(op[1]).append(',').append(op[2])
					.append(" via=").append(shortHex(op[3])).append(" leg=").append(op[4]);
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
				.append(" forks=").append(route.getForks().size())
				.append(" | 计划/实际: ").append(describePlan(route));
			if (!route.isEstablished()) {
				out.append(" | ").append(route.getStateReason());
			}
		}
		return out.toString();
	}

	/**
	 * T5 计划/实际对照：{@code plannedMillis} 来自任务（{@code MmtrTask.earliestMs}，作业单步骤带上来），
	 * {@code requestedMillis} 是这条进路**实际**被申请的时刻。
	 *
	 * <p>计划槽早于申请时刻 = **已漂移**，此时它在冲突裁决里已经不占先（notes/128）；报告里必须说清，
	 * 否则运营台会以为"我计划在先、为什么没先走"是引擎出错。</p>
	 */
	private static String describePlan(MmtrRoute route) {
		final long planned = route.getPlannedMillis();
		final long requested = route.getRequestedMillis();
		if (planned == Long.MAX_VALUE) {
			return "无计划（裁决按到达序）| 实际申请 " + requested;
		}
		return "计划 " + planned + " | 实际申请 " + requested
			+ (planned < requested ? "（已漂移：计划槽早于申请，裁决退回到达序）" : "（计划有效：裁决以计划为先）");
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
