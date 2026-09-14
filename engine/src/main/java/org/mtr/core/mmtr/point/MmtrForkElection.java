package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;

/**
 * The single source of truth for "which rail do I continue onto at this node?".
 *
 * <p>Extracted verbatim from {@code MmtrMotionWalker.electAtFork} during the consist-body rewrite
 * (B3) so the new double-ended walker and the legacy single-point walker cannot drift apart. The
 * decision order is unchanged and is the design R2 rule:</p>
 *
 * <ol>
 *   <li>manual operator branch (point-op / legacy preset) — never auto;</li>
 *   <li>an explicit auto grant held by {@code owner} at its ordered-leg index;</li>
 *   <li>the live task target rail, when it is one of the ordered legs;</li>
 *   <li>a single continuation (no authority question);</li>
 *   <li>otherwise {@code null} — the train waits at the fork.</li>
 * </ol>
 *
 * <p>Continuations are ordered deterministically per approach direction by {@link MmtrPoint}
 * (straight &gt; left &gt; right &gt; other), so operator indices and planner presets agree; an
 * authoritative 进向表 entry for {@code (node, viaRail)} overrides the geometry entirely.</p>
 */
public final class MmtrForkElection {

	private MmtrForkElection() {
	}

	/**
	 * Elect the continuation rail when the train stands at {@code node} having arrived on
	 * {@code viaRail} from {@code enteredFrom}.
	 *
	 * @return the elected rail, or {@code null} when the train must halt (unset fork / no
	 * continuation at all)
	 */
	public static @Nullable Rail elect(Data data, BranchStore branches, @Nullable MmtrPointAuthority pointAuthority, @Nullable String pointAuthorityOwner, @Nullable String targetRailHex, Position node, Position enteredFrom, Rail viaRail) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(node);
		if (neighbors == null) {
			return null;
		}
		final ObjectArrayList<Rail> forwardRails = new ObjectArrayList<>();
		for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
			if (e.getValue() != viaRail) {
				forwardRails.add(e.getValue());
			}
		}
		if (forwardRails.isEmpty()) {
			return null;
		}
		final ObjectArrayList<String> declared = data instanceof final Simulator simulator
			? simulator.mmtrJunctionLegs.get(node.getX(), node.getY(), node.getZ(), viaRail.getHexId()) : null;
		final ObjectArrayList<MmtrPointLeg> legs = MmtrPoint.computeOrderedLegs(node, enteredFrom, viaRail, neighbors, declared);
		if (legs.isEmpty()) {
			return null;
		}

		/*
		 * **物理道岔的禁行闸门**（用户 2026-09-13 的规格）：一处道岔只有一个位置，两条进路互斥。
		 *
		 * <p>位置 0 = 正线贯通（岔股**禁止通行**）；位置 1 = 岔股开放（正线被断开的那一侧**禁止通行**）。
		 * 做法是**过滤**而不是取代：下面的取值顺序（人工位 → 进路授权 → 任务目标 → 唯一续行）保持不变，
		 * 但轮到"没开通的那一侧"时一律不放行 —— 返回 null，车停在岔前。原来缺的正是这道闸门：
		 * 它允许从岔股开往正线远端这种**物理上不存在的组合**（列车会在尖轨处脱轨）。</p>
		 *
		 * <p>非单开道岔（度 4 交叉、三岔口）没有物理道岔模型，闸门不生效，仍走老顺序。</p>
		 */
		MmtrTurnout turnout = null;
		int turnoutPosition = 0;
		if (data instanceof final Simulator turnoutSimulator) {
			turnout = turnoutSimulator.mmtrTurnout(node.getX(), node.getY(), node.getZ());
			if (turnout != null) {
				// 位置：调用方用的那个 store 是唯一真源（生产里就是模拟器那一份；测试会注入自己的）。
				// 但"授权/进路要的那条腿"会先把道岔扳过去（联锁扳动道岔），所以先同步一次。
				if (branches == null || branches == turnoutSimulator.mmtrPointBranches) {
					turnoutSimulator.mmtrSyncTurnoutPositionToGrant(turnout);
					turnoutPosition = turnoutSimulator.mmtrTurnoutPosition(node.getX(), node.getY(), node.getZ());
				} else {
					// 注入的 store：先把行视图折成位置（写行 = 扳道岔），再叠上**这次调用给的**授权持有的腿
					// （注意是调用方给的那份授权表，不是模拟器自己那份 —— 测试会把两者分开装）
					turnoutPosition = turnout.positionFromRows(branches, node.getX(), node.getY(), node.getZ());
					final int grantedPosition = turnoutPositionFromGrant(pointAuthority, pointAuthorityOwner, turnout, node);
					if (grantedPosition != Integer.MIN_VALUE) {
						turnoutPosition = grantedPosition;
					}
				}
				if (turnout.continuationFrom(viaRail.getHexId(), turnoutPosition) == null) {
					return null;
				}
			}
		}

		Rail chosen = null;
		final long px = node.getX();
		final long py = node.getY();
		final long pz = node.getZ();
		final String viaHex = viaRail.getHexId();
		if (branches != null && branches.contains(px, py, pz, viaHex)) {
			final int operator = branches.get(px, py, pz, viaHex);
			if (operator >= 0 && operator < legs.size()) {
				chosen = findRailByHex(forwardRails, legs.get(operator).railHex);
			}
		}
		if (chosen == null && pointAuthority != null && pointAuthorityOwner != null) {
			if (pointAuthority.isGrantedTo(px, py, pz, viaHex, pointAuthorityOwner)) {
				final int granted = pointAuthority.grantedLeg(px, py, pz, viaHex);
				if (granted >= 0 && granted < legs.size()) {
					chosen = findRailByHex(forwardRails, legs.get(granted).railHex);
				}
			}
		}
		if (chosen == null && targetRailHex != null) {
			for (final MmtrPointLeg leg : legs) {
				if (leg.railHex.equals(targetRailHex)) {
					chosen = findRailByHex(forwardRails, leg.railHex);
					break;
				}
			}
		}
		if (chosen == null && legs.size() == 1) {
			chosen = findRailByHex(forwardRails, legs.get(0).railHex);
		}
		if (chosen != null && turnout != null) {
			// 闸门：选出来的这条轨必须是道岔当前**开通**的那一侧；否则一律不放行
			final String allowed = turnout.continuationFrom(viaHex, turnoutPosition);
			if (allowed == null || !allowed.equals(chosen.getHexId())) {
				/*
				 * **联锁按意图扳岔**（用户 2026-09-14 的选择 ①）：人工位 / 授权 / 任务目标已经明确
				 * "要这条腿"了，而道岔还停在默认位（0 = 正线贯通，岔股禁止通行）—— 那就把它扳过去，
				 * 而不是让列车在岔前干等。与设计 §5.3"玩家不扳岔，联锁扳岔"一致；人工锁着的不扳、
				 * 有人物理持有的也不扳（两道闸门在 Simulator#mmtrThrowTurnoutForIntent 里）。
				 */
				if (!(data instanceof final Simulator intentSimulator)) {
					return null;
				}
				int leg = -1;
				for (int i = 0; i < legs.size(); i++) {
					if (legs.get(i).railHex.equals(chosen.getHexId())) {
						leg = i;
						break;
					}
				}
				final int position = leg < 0 ? Integer.MIN_VALUE : turnout.positionForLeg(viaHex, leg);
				if (position == Integer.MIN_VALUE
					|| !intentSimulator.mmtrThrowTurnoutForIntent(node.getX(), node.getY(), node.getZ(), position, pointAuthorityOwner)) {
					return null;
				}
			}
		}
		return chosen;
	}

	/**
	 * **这次调用给的**授权表里，有没有哪条腿指明了道岔位置。
	 *
	 * <p>用调用方给的授权表（而不是模拟器自己那份）：测试会把走行、授权、道岔行视图分开装，
	 * 生产里它们恰好是同一份。{@code owner} 非空时只认它持有的授权；为空时按任意持有者读。</p>
	 */
	private static int turnoutPositionFromGrant(@Nullable MmtrPointAuthority authority, @Nullable String owner, MmtrTurnout turnout, Position node) {
		if (authority == null) {
			return Integer.MIN_VALUE;
		}
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			if (owner != null && !authority.isGrantedTo(node.getX(), node.getY(), node.getZ(), via, owner)) {
				continue;
			}
			final int granted = authority.grantedLeg(node.getX(), node.getY(), node.getZ(), via);
			if (granted < 0) {
				continue;
			}
			final int position = turnout.positionForLeg(via, granted);
			if (position != Integer.MIN_VALUE) {
				return position;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Whether the node has any continuation other than {@code viaRail} at all (used to tell a halt from an end of line). */
	public static boolean hasContinuation(Data data, Position node, Rail viaRail) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(node);
		if (neighbors == null) {
			return false;
		}
		for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
			if (e.getValue() != viaRail) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable Rail findRailByHex(ObjectArrayList<Rail> forwardRails, String hex) {
		for (final Rail forwardRail : forwardRails) {
			if (forwardRail.getHexId().equals(hex)) {
				return forwardRail;
			}
		}
		return null;
	}
}
