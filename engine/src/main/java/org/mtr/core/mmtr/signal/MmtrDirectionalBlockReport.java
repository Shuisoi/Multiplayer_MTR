package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;

/**
 * 闭塞区间 v2 诊断报告: the operator-facing text for the <strong>directional, lamp-to-lamp</strong>
 * section model ({@link MmtrDirectionalBlockService}).
 *
 * <p>It answers the question the v1 report could not: <em>which lamp protects which stretch of line,
 * and how far does that stretch run?</em> A v2 section is what one lamp authorises, walked in the
 * direction the lamp faces up to the next lamp, so a section normally spans several rails and the
 * report says exactly which ones.</p>
 *
 * <p>Read-only: nothing here mutates, reserves or moves anything.</p>
 */
public final class MmtrDirectionalBlockReport {

	private MmtrDirectionalBlockReport() {
	}

	/** The world summary plus one line per section. */
	public static String describeAll(Simulator simulator) {
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);
		final ObjectArrayList<MmtrDirectionalBlockService.Section> sections = new ObjectArrayList<>(service.allSections().values());
		sections.sort((a, b) -> a.id.compareTo(b.id));

		final StringBuilder out = new StringBuilder("[blocks-v2] 有向区间 ").append(sections.size())
			.append(" 个 / 覆盖轨 ").append(service.railsWithSections())
			.append(" 根 / 灯 ").append(simulator.mmtrSignals.signals.size()).append(" 架");
		if (sections.isEmpty()) {
			out.append("（没有任何灯能定出区间：检查灯的朝向是否沿轨）");
			return out.toString();
		}

		int multiRail = 0;
		for (final MmtrDirectionalBlockService.Section section : sections) {
			if (section.spans.size() > 1) {
				multiRail++;
			}
			out.append("\n[blocks-v2] 区间 ").append(section.id).append(" → ")
				.append(section.exitSignalKey == null || section.exitSignalKey.isEmpty() ? "尽头" : section.exitSignalKey)
				.append(" 跨 ").append(section.spans.size()).append(" 根轨 长=").append(round(section.lengthM()));
			for (final MmtrDirectionalBlockService.RailSpan span : section.spans) {
				out.append("\n    ").append(shortHex(span.railHex)).append(" 弧[").append(round(span.arcFromM))
					.append(", ").append(round(span.arcToM)).append(") 长=").append(round(span.lengthM()));
			}
		}
		out.append("\n[blocks-v2] 汇总: 跨多根轨的区间 ").append(multiRail).append(" / ").append(sections.size());
		return out.toString();
	}

	/** One rail's sections in both directions (diagnostics for a specific rail). */
	public static String describe(Simulator simulator, @Nullable String railHex) {
		if (railHex == null || railHex.isEmpty()) {
			return "[blocks-v2] 用法: blocks-v2 all | blocks-v2 <railHex>";
		}
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);
		final ObjectArrayList<MmtrDirectionalBlockService.Section> sections = service.sectionsOfRail(railHex);
		if (sections.isEmpty()) {
			return "[blocks-v2] 轨 " + shortHex(railHex) + " 不属于任何区间（无灯照到它）";
		}
		final Rail rail = simulator.rails.stream().filter(candidate -> candidate.getHexId().equals(railHex)).findFirst().orElse(null);
		final StringBuilder out = new StringBuilder("[blocks-v2] 轨 ").append(shortHex(railHex))
			.append(" 长=").append(rail == null ? "?" : round(rail.railMath.getLength()))
			.append(" 属于 ").append(sections.size()).append(" 个有向区间");
		for (final MmtrDirectionalBlockService.Section section : sections) {
			out.append("\n  ").append(section.id).append(" → ")
				.append(section.exitSignalKey == null || section.exitSignalKey.isEmpty() ? "尽头" : section.exitSignalKey)
				.append(" 本轨弧[").append(spanOn(section, railHex)).append(")");
		}
		return out.toString();
	}

	private static String spanOn(MmtrDirectionalBlockService.Section section, String railHex) {
		for (final MmtrDirectionalBlockService.RailSpan span : section.spans) {
			if (span.railHex.equals(railHex)) {
				return round(span.arcFromM) + ", " + round(span.arcToM);
			}
		}
		return "-, -";
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
