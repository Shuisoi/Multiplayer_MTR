package org.mtr.mod.mmtr;

import org.mtr.core.data.Position;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A2 client-side signal chain, pure: how far ahead (in SECTIONS, the protected one included) the
 * nearest occupied section sits when the signal protecting a rail is approached from a node.
 *
 * <p>Extracted from {@code RenderSignalBase} so the CLIENT rule can be tested without Minecraft - it
 * is the second half of "信号 = 进路 × 闭塞" and had no coverage at all: the engine computes the same
 * thing in {@code MmtrSignalAspect}, and the two must agree.</p>
 *
 * <ul>
 *   <li>depth 1 = the protected section itself is occupied, 2 = the section beyond it in the travel
 *       direction, 3 = the one after that, 0 = clear;</li>
 *   <li>B3b: a rail split by a wayside signal is walked as TWO steps (the near section, then the far
 *       one), so a train standing in the far section leaves the signal protecting the near one at a
 *       caution instead of red - matching the engine, which stops S1 at that signal (B2);</li>
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

	/**
	 * One block section of a rail, mirrored by the engine (B3b). The colour is the reserved signal
	 * colour the authoritative channel holds while the section is occupied.
	 */
	public static final class Section {
		public final double fromM;
		public final double toM;
		public final long color;

		public Section(double fromM, double toM, long color) {
			this.fromM = fromM;
			this.toM = toM;
			this.color = color;
		}
	}

	/** The rail graph as the client sees it (an adapter over {@code MinecraftClientData} in the renderer). */
	public interface RailGraph {
		/** The far endpoint of {@code railHex} when entered from {@code node}, or null when unknown. */
		@Nullable Position farEnd(Position node, String railHex);

		/** Every rail meeting at {@code node} except {@code railHex}, with that rail's far endpoint. */
		List<RailEnd> otherRailsAt(Position node, String railHex);

		/** The arc of {@code node} on {@code railHex} in ordered-position-1 space (0 when unknown). */
		default double entryArc(Position node, String railHex) {
			return 0;
		}

		/** The length of {@code railHex} in metres (0 when unknown). */
		default double railLength(Position node, String railHex) {
			return 0;
		}
	}

	private MmtrSignalChain() {
	}

	/**
	 * Whole-rail walk (no section data): the pre-B3b rule, kept for callers/tests that have no mirror.
	 *
	 * @param startNode       the node the signal's protected rail(s) are entered from
	 * @param protectedHexes  the rail(s) the signal protects (one per matching rail at the node)
	 * @param graph           the client rail graph
	 * @param blocked         per-rail occupancy (local simulation or the authoritative signal colors)
	 * @param lockedNextRails the mirrored locked path: rail hex -&gt; next rail candidates
	 * @param maxDepth        chain depth to model (3 = red / single / double yellow / green)
	 */
	public static int depth(Position startNode, List<String> protectedHexes, RailGraph graph, Predicate<String> blocked, Function<String, List<String>> lockedNextRails, int maxDepth) {
		return depth(startNode, protectedHexes, graph, hex -> Collections.emptyList(), (hex, color) -> false, blocked, hex -> false, lockedNextRails, maxDepth);
	}

	/**
	 * B3b section-aware walk.
	 *
	 * @param sections       per-rail mirrored sections (empty list = the rail is one section)
	 * @param sectionBlocked whether a rail's section colour is currently held
	 * @param railBlocked    the per-rail occupancy test (used when no sections are mirrored, and as the
	 *                       conservative fallback for a blocked colour that belongs to no section)
	 */
	public static int depth(Position startNode, List<String> protectedHexes, RailGraph graph, Function<String, List<Section>> sections, BiPredicate<String, Long> sectionBlocked, Predicate<String> railBlocked, Function<String, List<String>> lockedNextRails, int maxDepth) {
		return depth(startNode, protectedHexes, graph, sections, sectionBlocked, railBlocked, hex -> false, lockedNextRails, maxDepth);
	}

	/**
	 * ④ 显示层: the full walk, including the mirrored "junction not cleared" node keys.
	 *
	 * @param restrictedNodes {@code x,y,z} keys of junctions the engine cannot clear (undecided points or
	 *                        a fouled clearance zone); a step through such a node is as good as occupied,
	 *                        so the signal shows the danger the motion rules (①/②/③) already enforce
	 */
	public static int depth(Position startNode, List<String> protectedHexes, RailGraph graph, Function<String, List<Section>> sections, BiPredicate<String, Long> sectionBlocked, Predicate<String> railBlocked, Predicate<String> restrictedNodes, Function<String, List<String>> lockedNextRails, int maxDepth) {
		List<Object[]> level = new ArrayList<>();
		for (final String hex : protectedHexes) {
			level.add(new Object[]{hex, startNode, graph.entryArc(startNode, hex)});
		}
		for (int depth = 1; depth <= maxDepth; depth++) {
			for (final Object[] entry : level) {
				final String stepHex = (String) entry[0];
				final Position stepNode = (Position) entry[1];
				if (sectionBlocked(stepHex, (Double) entry[2], sections, sectionBlocked, railBlocked)
					|| restrictedNodes.test(nodeKey(stepNode))
					|| restrictedNodes.test(nodeKey(graph.farEnd(stepNode, stepHex)))) {
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
				final double arc = (Double) entry[2];
				final Section section = sectionAt(sections.apply(curHex), arc);
				if (section != null && section.toM < graph.railLength(node, curHex) - 1e-9) {
					// B3b: another section on the SAME rail - the next step keeps the entry node.
					nextLevel.add(new Object[]{curHex, node, section.toM});
					continue;
				}
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
							nextLevel.add(new Object[]{lockedNext, far, graph.entryArc(far, lockedNext)});
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
						nextLevel.add(new Object[]{other.railHex, far, graph.entryArc(far, other.railHex)});
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

	/** The {@code x,y,z} key the mirror uses for a node (④ restricted-junction set). */
	private static String nodeKey(@Nullable Position node) {
		return node == null ? "" : node.getX() + "," + node.getY() + "," + node.getZ();
	}

	/**
	 * Whether the section of {@code hex} containing {@code arc} is occupied. Mirrors the engine rule: the
	 * section's own colour wins; a blocked colour that belongs to NO mirrored section (a legacy MTR block
	 * or a manual block) conservatively closes the whole rail.
	 */
	private static boolean sectionBlocked(String hex, double arc, Function<String, List<Section>> sections, BiPredicate<String, Long> sectionBlocked, Predicate<String> railBlocked) {
		final List<Section> list = sections.apply(hex);
		if (list.isEmpty()) {
			return railBlocked.test(hex);
		}
		final Section section = sectionAt(list, arc);
		if (section != null && sectionBlocked.test(hex, section.color)) {
			return true;
		}
		boolean anySectionColorBlocked = false;
		for (final Section candidate : list) {
			if (sectionBlocked.test(hex, candidate.color)) {
				anySectionColorBlocked = true;
				break;
			}
		}
		return railBlocked.test(hex) && !anySectionColorBlocked;
	}

	/** The section containing {@code arc} (half-open [from, to); the far end belongs to the last one). */
	private static @Nullable Section sectionAt(List<Section> sections, double arc) {
		for (final Section section : sections) {
			if (arc >= section.fromM - 1e-9 && arc < section.toM - 1e-9) {
				return section;
			}
		}
		return sections.isEmpty() ? null : sections.get(sections.size() - 1);
	}
}
