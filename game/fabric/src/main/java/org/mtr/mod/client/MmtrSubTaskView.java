package org.mtr.mod.client;

import org.mtr.mod.data.VehicleExtension;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * **站台作业子任务清单的客户端视图**（到站停稳 / 开门 / 停够 / 关门）。
 *
 * <h2>它做什么、不做什么</h2>
 *
 * <p>只做一件事：把引擎发来的编码<b>拆开画出来</b>。判定一个字都不做 —— 哪一条完成、哪一条在做、
 * 停留还剩几秒，全部由引擎算好（这就是 {@code mmtrSubTasks} 里为什么把"引擎判定"与"客户端是否确认"
 * 两格都编进去：客户端连"这一步算不算完成"都不需要自己判）。</p>
 *
 * <h2>编码格式</h2>
 *
 * <p>{@code KIND:STATE:ACK:人话;…} —— 见引擎侧 {@code MmtrSubTask#encode}。这里的解析器与它一对一，
 * 未知段一律跳过（引擎升级时客户端不应崩，只是少画一条）。</p>
 */
public final class MmtrSubTaskView {

	private MmtrSubTaskView() {
	}

	/** 一条子任务：类型（{@code ARRIVE} 等）、引擎判定（{@code PENDING/ACTIVE/DONE}）、客户端是否已确认、人话。 */
	public record Entry(String kind, String state, boolean acked, String text) {

		public boolean isDone() {
			return "DONE".equals(state);
		}
	}

	public static List<Entry> parse(@Nullable String encoded) {
		final List<Entry> entries = new ArrayList<>();
		if (encoded == null || encoded.isEmpty()) {
			return entries;
		}
		for (final String chunk : encoded.split(";")) {
			final String[] parts = chunk.split(":", 4);
			if (parts.length < 4) {
				continue;
			}
			entries.add(new Entry(parts[0], parts[1], "A".equals(parts[2]), parts[3]));
		}
		return entries;
	}

	public static List<Entry> of(@Nullable VehicleExtension vehicle) {
		return vehicle == null ? new ArrayList<>() : parse(vehicle.getMmtrSubTasksFromSync());
	}

	/** 第一条还没完成的子任务（{@code -1} = 全完成或没有链）—— 也就是"现在该做什么"。 */
	public static int currentIndex(List<Entry> entries) {
		for (int i = 0; i < entries.size(); i++) {
			if (!entries.get(i).isDone()) {
				return i;
			}
		}
		return -1;
	}

	/** 第一条**引擎已判定完成、客户端还没确认**的子任务（{@code -1} = 没有要确认的）。 */
	public static int firstUnconfirmedIndex(List<Entry> entries) {
		for (int i = 0; i < entries.size(); i++) {
			final Entry entry = entries.get(i);
			if (entry.isDone() && !entry.acked()) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 一行画出来的清单（**单行**形式）：{@code ◉停在 3站1台 ●开门 ○关门}。
	 *
	 * <p>用户要的"明确的双向确认"就在标记里一眼看得出：{@code ◉} = 引擎判定完成 + 客户端已确认，
	 * {@code ◉?} = 引擎说完成了、我这边还没确认。见 {@link #marker}。</p>
	 *
	 * <p>HUD 现在**不再用这一行**（用户口径 2026-09-25「子任务为分点独立显示」：每条各自一行，
	 * 见 {@code MmtrTaskHud}），保留它是给"需要一行摘要"的场合与既有调用方用。</p>
	 */
	public static String summary(List<Entry> entries) {
		final StringBuilder builder = new StringBuilder();
		for (final Entry entry : entries) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(marker(entry)).append(entry.text());
		}
		return builder.toString();
	}

	/** 一条子任务画成一行：{@code ◉停在 3站1台}（分点显示的单元）。 */
	public static String line(Entry entry) {
		return marker(entry) + entry.text();
	}

	/**
	 * 每条的**状态标记**（用户口径 2026-09-25「子任务为分点独立显示而不是》》》》」）。
	 *
	 * <h3>为什么把 {@code ✔}/{@code ▶} 换掉了</h3>
	 *
	 * <p>原来每一条前面画的是 {@code ✔✔} / {@code ✔?} / {@code ▶} / {@code ·}（notes/170 那套）。
	 * 实测（用 fontTools 读随包字体）：{@code ✔}(U+2714) 与 {@code ▶}(U+25B6) **都不在**
	 * HarmonyOS Sans SC 里 —— 它们只能落到 MC 内置的 unifont，于是这些标记与其余文字
	 * **不同源、不同基线、不同字面**，屏幕上看起来就是一串莫名其妙的符号。这正是用户报的
	 * 「》》》》」。</p>
	 *
	 * <p>换成同一族、**该字体里确实有**、且进宽都是整齐 1.000 em 的三个几何符号，
	 * 于是整条清单同源等距：</p>
	 *
	 * <table>
	 *   <caption>标记含义</caption>
	 *   <tr><th>标记</th><th>含义</th></tr>
	 *   <tr><td>{@code ○}</td><td>还没轮到</td></tr>
	 *   <tr><td>{@code ●}</td><td>正在做</td></tr>
	 *   <tr><td>{@code ◉}</td><td>完成，且**客户端已确认**（两侧都算数）</td></tr>
	 *   <tr><td>{@code ◉?}</td><td>引擎判定完成，但**客户端还没确认** —— 这一处差别就是"双向确认"的可视化</td></tr>
	 * </table>
	 */
	private static String marker(Entry entry) {
		if (entry.isDone()) {
			return entry.acked() ? "◉" : "◉?";
		}
		return "ACTIVE".equals(entry.state()) ? "●" : "○";
	}
}
