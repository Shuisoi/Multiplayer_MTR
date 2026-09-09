package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry;
import org.mtr.core.simulation.Simulator;

/**
 * 闭塞区间诊断报告 (block-section report): the operator-facing text behind the OP command
 * {@code blocks [all|&lt;railHex&gt;]}. It answers the two questions the section feature raises in the
 * field, without reading JSON feeds:
 *
 * <ol>
 *   <li><strong>Which rails are actually split?</strong> The section model cuts a rail only where a
 *       registered signal projects onto it away from its end nodes. Real signals are placed beside a
 *       NODE (the user, 2026-09-10), so in practice every signal lands on an end node and each rail
 *       stays one section - the report makes that visible instead of leaving it to inference.</li>
 *   <li><strong>What is each signal bound to?</strong> Its target rail (BOUND), the nearest rail
 *       (AUTO placement), or nothing at all - including the "target rail found but the light stands
 *       more than {@link MmtrBlockService#SIGNAL_BIND_TOLERANCE_M} m off it" case, which used to be
 *       indistinguishable from a working binding.</li>
 * </ol>
 *
 * <p>Pure projection: nothing here mutates, reserves or moves anything.</p>
 */
public final class MmtrBlockReport {

	private MmtrBlockReport() {
	}

	/** World summary: rail/section counts, every split rail with its sections, and every signal's binding. */
	public static String describeAll(Simulator simulator) {
		final MmtrBlockService blocks = simulator.mmtrBlocks;
		blocks.refresh();
		final ObjectArrayList<Rail> splitRails = new ObjectArrayList<>();
		simulator.rails.forEach(rail -> {
			if (blocks.blocksOf(rail.getHexId()).size() > 1) {
				splitRails.add(rail);
			}
		});
		final StringBuilder out = new StringBuilder("[blocks] 轨道 ").append(blocks.railCount())
			.append(" 根 / 区间 ").append(blocks.blockCount())
			.append(" 个 / 被灯切分的轨 ").append(splitRails.size()).append(" 根");
		if (splitRails.isEmpty()) {
			out.append("（灯都贴在节点旁：每根轨 = 一个区间）");
		}

		final int[] signalCounts = {0, 0, 0, 0}; // total, node-adjacent, splitting, unbound
		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			signalCounts[0]++;
			out.append("\n[blocks] 灯 ").append(entry.x).append(',').append(entry.y).append(',').append(entry.z)
				.append(' ').append(entry.mode);
			final Rail rail = blocks.resolveRail(entry);
			if (rail == null) {
				signalCounts[3]++;
				out.append(" → 未绑定（离任何轨都超过 ").append(round(MmtrBlockService.SIGNAL_BIND_TOLERANCE_M)).append(" m）");
				continue;
			}
			final Double arc = MmtrBlockService.projectArc(rail, entry.x + 0.5, entry.y + 0.5, entry.z + 0.5);
			out.append(" → 轨 ").append(shortHex(rail.getHexId())).append(" 长=").append(round(rail.railMath.getLength()));
			if (arc == null) {
				signalCounts[3]++;
				out.append(" 投影超差（绑定轨在，但灯离轨超过 ").append(round(MmtrBlockService.SIGNAL_BIND_TOLERANCE_M)).append(" m，不切分）");
				continue;
			}
			final double length = rail.railMath.getLength();
			out.append(" 弧=").append(round(arc));
			if (arc <= MmtrBlockService.NODE_SNAP_M || arc >= length - MmtrBlockService.NODE_SNAP_M) {
				signalCounts[1]++;
				out.append(" 贴节点（不切分）");
			} else {
				signalCounts[2]++;
				out.append(" 切分该轨");
			}
		}
		out.append("\n[blocks] 灯共 ").append(signalCounts[0]).append(" 架：贴节点 ").append(signalCounts[1])
			.append(" / 切分轨 ").append(signalCounts[2]).append(" / 未生效 ").append(signalCounts[3]);

		for (final Rail rail : splitRails) {
			out.append('\n').append(describe(simulator, rail.getHexId()));
		}
		return out.toString();
	}

	/** One rail: its length, its section boundaries (with the signal that owns each) and its sections. */
	public static String describe(Simulator simulator, @Nullable String railHex) {
		if (railHex == null || railHex.isEmpty()) {
			return "[blocks] 用法: blocks all | blocks <railHex>";
		}
		final MmtrBlockService blocks = simulator.mmtrBlocks;
		final ObjectArrayList<MmtrBlockService.Block> sections = blocks.blocksOf(railHex);
		if (sections.isEmpty()) {
			return "[blocks] 找不到轨 " + shortHex(railHex);
		}
		final Rail rail = simulator.rails.stream().filter(candidate -> candidate.getHexId().equals(railHex)).findFirst().orElse(null);
		final double length = rail == null ? sections.get(sections.size() - 1).arcToM : rail.railMath.getLength();
		final StringBuilder out = new StringBuilder("[blocks] 轨 ").append(shortHex(railHex))
			.append(" 长=").append(round(length)).append(" 区间=").append(sections.size());
		out.append("\n  边界:");
		for (final MmtrBlockService.Boundary boundary : blocks.boundariesOf(railHex)) {
			out.append(' ').append(boundary.kind).append('@').append(round(boundary.arcFromOrdered1M));
			if (boundary.kind == MmtrBlockService.BoundaryKind.SIGNAL) {
				out.append('(').append(boundary.sourceKey).append(')');
			}
		}
		for (final MmtrBlockService.Block section : sections) {
			out.append("\n  区间 [").append(round(section.arcFromM)).append(", ").append(round(section.arcToM))
				.append(") 长=").append(round(section.lengthM()));
		}
		return out.toString();
	}

	private static String shortHex(@Nullable String hex) {
		if (hex == null || hex.isEmpty()) {
			return "-";
		}
		return hex.substring(Math.max(0, hex.length() - 8));
	}

	private static String round(double value) {
		return String.valueOf(Math.round(value * 100.0) / 100.0);
	}
}
