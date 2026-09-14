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
		// 一灯多腿时值是"多条区间"，这里摊平：报表要的是"世界上有多少段区间"，不是"多少盏灯"
		final ObjectArrayList<MmtrDirectionalBlockService.Section> sections = new ObjectArrayList<>();
		service.allSections().values().forEach(sections::addAll);
		sections.sort((a, b) -> a.id.compareTo(b.id));

		final StringBuilder out = new StringBuilder("[blocks-v2] 有向区间 ").append(sections.size())
			.append(" 个 / 覆盖轨 ").append(service.railsWithSectionsCount())
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

	/**
	 * 水闸区间的完整转储 + 节点归属自检 (diagnostics): every block with its spans, then every node with the
	 * block it belongs to, then a count of anything without an owner.
	 *
	 * <p>This is the check the layer's whole promise rests on - "每个轨道层每个节点都有且只有一个区间层所属" -
	 * so it reports the failures rather than only the successes: a node with no owner, or a node whose owner is
	 * not one of the blocks, is printed with the rail and arc that produced it.</p>
	 */
	public static String describeBlocks(Simulator simulator) {
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);
		final ObjectArrayList<MmtrDirectionalBlockService.GateBlock> blocks = service.gateBlocks();
		final StringBuilder out = new StringBuilder("[blocks] 水闸区间 ").append(blocks.size())
			.append(" 个 / 轨 ").append(simulator.rails.size()).append(" 根 / 灯 ").append(simulator.mmtrSignals.signals.size()).append(" 架");
		int spanCount = 0;
		int unguarded = 0;
		for (final MmtrDirectionalBlockService.GateBlock block : blocks) {
			spanCount += block.spans.size();
			if (block.entryLampKey.isEmpty()) {
				unguarded++;
			}
		}
		out.append("\n[blocks] 段 ").append(spanCount).append(" / 有灯区间 ").append(blocks.size() - unguarded)
			.append(" / 无灯区间 ").append(unguarded);

		out.append("\n[blocks] 节点归属:");
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, String> owners = service.nodeOwners();
		int missing = 0;
		for (final String owner : owners.values()) {
			if (owner == null || owner.isEmpty()) {
				missing++;
			}
		}
		out.append(" 节点 ").append(owners.size()).append(" 个 / 无归属 ").append(missing);
		return out.toString();
	}

	/** One node's ownership, with everything that went into it (diagnostics). */
	public static String describeNode(Simulator simulator, String nodeKey) {
		final MmtrDirectionalBlockService service = new MmtrDirectionalBlockService(simulator);
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, String> owners = service.nodeOwners();
		final String owner = owners.get(nodeKey);
		final StringBuilder out = new StringBuilder("[blocks] 节点 ").append(nodeKey).append(" 归属=")
			.append(owner == null ? "（不在节点表里）" : (owner.isEmpty() ? "（无）" : owner));
		final String[] parts = nodeKey.split(",");
		if (parts.length == 3) {
			try {
				out.append(new MmtrDirectionalBlockService(simulator).describeNodeResolution(
					new org.mtr.core.data.Position(Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()), Long.parseLong(parts[2].trim()))));
			} catch (NumberFormatException ignored) {
				out.append("\n  （节点坐标无法解析，用法: blocks <x>,<y>,<z>）");
			}
		}
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
