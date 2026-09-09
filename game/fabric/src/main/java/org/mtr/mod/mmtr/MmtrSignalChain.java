package org.mtr.mod.mmtr;

import org.mtr.core.data.Position;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A2 client-side signal chain, pure: how far ahead (in rails, the protected one included) the nearest
 * occupied rail sits when the signal protecting a rail is approached from a node.
 *
 * <p>Extracted from {@code RenderSignalBase} so the CLIENT rule can be tested without Minecraft - it
 * is the second half of "信号 = 进路 × 闭塞" and had no coverage at all: the engine computes the same
 * thing in {@code MmtrSignalAspect}, and the two must agree.</p>
 *
 * <ul>
 *   <li>depth 1 = the protected rail itself is occupied, 2 = one rail beyond in the travel direction,
 *       3 = two beyond, 0 = clear;</li>
 *   <li>continuations keep the travel direction (positive dot product, no turn-backs);</li>
 *   <li>at a fork every branch counts UNLESS the engine mirrored a locked path for that rail - then
 *       only the mirrored candidate that actually continues from the node being left is followed (a
 *       route may traverse the same rail twice, so the mirror lists every candidate);</li>
 *   <li>the PENDING-entry danger (the movement waits outside its signal) is the caller's check.</li>
 * </ul>
 */
public final class MmtrSignalChain {

	/** One continuation rail at a node, with its far endpoint (used for the travel-direction test). */
	public static final class RailEnd {
		public final String railHex;
		public final Position farEnd;

		public RailEnd(String railHex, Position farEnd) {
			this.railHex = railHex;
			this.farEnd = farEnd;
		}
	}

	/** The rail graph as the client sees it (an adapter over {@code MinecraftClientData} in the renderer). */
	public interface RailGraph {
		/** The far endpoint of {@code railHex} when entered from {@code node}, or null when unknown. */
		@Nullable Position farEnd(Position node, String railHex);

		/** Every rail meeting at {@code node} except {@code railHex}, with that rail's far endpoint. */
		List<RailEnd> otherRailsAt(Position node, String railHex);
	}

	private MmtrSignalChain() {
	}

	/**
	 * @param startNode      the node the signal's protected rail(s) are entered from
	 * @param protectedHexes the rail(s) the signal protects (one per matching rail at the node)
	 * @param graph          the client rail graph
	 * @param blocked        per-rail occupancy (local simulation or the authoritative signal colors)
	 * @param lockedNextRails the mirrored locked path: rail hex -&gt; next rail candidates
	 * @param maxDepth       chain depth to model (3 = red / single / double yellow / green)
	 */
	public static int depth(Position startNode, List<String> protectedHexes, RailGraph graph, Predicate<String> blocked, Function<String, List<String>> lockedNextRails, int maxDepth) {
		List<Object[]> level = new ArrayList<>();
		for (final String hex : protectedHexes) {
			level.add(new Object[]{hex, startNode});
		}
		for (int depth = 1; depth <= maxDepth; depth++) {
			for (final Object[] entry : level) {
				if (blocked.test((String) entry[0])) {
					return depth;
				}
			}
			if (depth == maxDepth) {
				break;
			}
			final List<Object[]> nextLevel = new ArrayList<>();
			for (final Object[] entry : level) {
				final String curHex = (String) entry[0];
				final Position node = (Position) entry[1];
				final Position far = graph.farEnd(node, curHex);
				if (far == null) {
					continue;
				}
				final List<RailEnd> others = graph.otherRailsAt(far, curHex);
				if (others.isEmpty()) {
					continue;
				}
				// A2: a SET main route locks one path through this rail - follow only that rail, and only
				// the candidate that continues from the node being left (a route may run over the rail
				// twice, so the mirror lists every candidate).
				boolean followedLockedPath = false;
				for (final String lockedNext : lockedNextRails.apply(curHex)) {
					for (final RailEnd other : others) {
						if (other.railHex.equals(lockedNext)) {
							nextLevel.add(new Object[]{lockedNext, far});
							followedLockedPath = true;
							break;
						}
					}
					if (followedLockedPath) {
						break;
					}
				}
				if (followedLockedPath) {
					continue;
				}
				for (final RailEnd other : others) {
					// Continue only in the travel direction (dot product with the incoming heading).
					final double dot = (other.farEnd.getX() - far.getX()) * (far.getX() - node.getX()) + (other.farEnd.getZ() - far.getZ()) * (far.getZ() - node.getZ());
					if (dot > 0) {
						nextLevel.add(new Object[]{other.railHex, far});
					}
				}
			}
			if (nextLevel.isEmpty()) {
				break;
			}
			level = nextLevel;
		}
		return 0;
	}
}
