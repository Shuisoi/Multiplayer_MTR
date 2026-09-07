package org.mtr.core.mmtr.segment;

import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;

/**
 * Turnout-authority node decision for the decoupled (segment + offset) motion layer (M2-Core
 * slice A/B).
 *
 * <p>The convention mirrors what {@code MmtrPointRegistry.discover} and
 * {@code org.mtr.core.path.SidingPathFinder} already establish: coming into a node on
 * {@code viaRailHex} there are at most two candidate continuations — {@code straightHex}
 * (branch0, the straightest) and {@code divergingHex} (branch1, the nearest diverging rail).
 * Branch is <em>only</em> ever what an operator (or a live task) sets to 0/1; it is never
 * chosen automatically. The difference versus the current engine is <em>where</em> the decision
 * is applied: today it constrains a one-shot route search (path is still baked at dispatch);
 * here it is asked <em>at run time</em>, each time a train reaches a node, so no pre-baked
 * through-route is required (自由开).</p>
 */
public final class MmtrNodeRouter {

	/** The two continuations available when entering a node on a given approach rail. */
	public static final class Continuation {
		/** branch0 = straightest continuation rail hex (never null). */
		public final String straightHex;
		/** branch1 = nearest diverging continuation rail hex; null when only one way leads on. */
		public final @Nullable String divergingHex;

		public Continuation(String straightHex, @Nullable String divergingHex) {
			this.straightHex = straightHex;
			this.divergingHex = divergingHex;
		}

		public static Continuation single(String straightHex) {
			return new Continuation(straightHex, null);
		}
	}

	private MmtrNodeRouter() {
	}

	/**
	 * Elect which rail to continue onto after reaching a node on the approach rail.
	 *
	 * <p>Precedence: a live task directive that names one of the two continuations wins (an
	 * executing job is the most current instruction, parallel to — and overriding a stale —
	 * operator setting). Otherwise an explicitly operator-set branch is honoured. A node with a
	 * single forward continuation is simply straight (no decision needed, not an authority
	 * matter). A real fork with <em>no</em> authority set and no task target returns {@code null}
	 * — the train must wait for an operator/task to decide (never auto).</p>
	 *
	 * @return the elected rail hex, or {@code null} when authority is required but absent.
	 */
	public static @Nullable String elect(Continuation continuation, @Nullable Integer operatorBranch, @Nullable String taskTargetHex) {
		if (continuation.divergingHex == null) {
			// Only one way forward: not a fork, not an authority question.
			return continuation.straightHex;
		}
		if (taskTargetHex != null && (taskTargetHex.equals(continuation.straightHex) || taskTargetHex.equals(continuation.divergingHex))) {
			return taskTargetHex;
		}
		if (operatorBranch != null) {
			return operatorBranch == 0 ? continuation.straightHex : continuation.divergingHex;
		}
		return null;
	}

	/**
	 * Convenience over a persisted {@link BranchStore}: reads whether the operator explicitly set
	 * this (node, approach) turnout and delegates to {@link #elect}.
	 */
	public static @Nullable String electFromStore(Continuation continuation, BranchStore branches, long nodeX, long nodeY, long nodeZ, String viaRailHex, @Nullable String taskTargetHex) {
		final boolean set = branches.contains(nodeX, nodeY, nodeZ, viaRailHex);
		final Integer operatorBranch = set ? branches.get(nodeX, nodeY, nodeZ, viaRailHex) : null;
		return elect(continuation, operatorBranch, taskTargetHex);
	}
}
