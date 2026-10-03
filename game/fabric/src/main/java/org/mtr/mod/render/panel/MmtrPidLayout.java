package org.mtr.mod.render.panel;

import org.apache.commons.io.IOUtils;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.data.IGui;
import org.mtr.mod.mmtr.MmtrPidText;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * **水牌版式**（notes/358）：不同车型（甚至同一车型的不同牌）各有各的**尺寸与排版**，都在锚点 JSON 里配。
 *
 * <h2>为什么要有这一层（用户 2026-10-01 口径）</h2>
 * <p>「不同车型有不同尺寸和排版的水牌，想办法能够独立吧。」尺寸本来就独立（每块锚点自带
 * {@code widthM/heightM}），但"排版"原来只有一套写死的两行规则 —— 一块很扁的牌和一块方形牌
 * 用同一套字号比例，必然有一边难看。</p>
 *
 * <p>于是照 {@link MmtrHudLayout} 那套（已在仪表盘上跑了一年的同型先例）：
 * **打包配置 → 锚点 JSON 的 {@code pid} / {@code next} 段 → 客户端按车型解析并缓存 → 画布上按行摆字**。
 * 版式里的所有数字都是**比例**（{@code x}/{@code y} 是牌面宽高的 0..1，{@code size} 是牌高的比例），
 * 所以同一份版式在不同尺寸的牌上自动等比 —— "按车型独立"与"换尺寸不用改版式"两件事同时成立。</p>
 *
 * <h2>锚点 JSON 里的形状</h2>
 * <pre>
 * "pid":  { "background": "#FF101418", "textColor": "#FFF2F4F6", "pxPerMetre": 512,
 *           "rows": [ { "field": "service",  "x": 0.5, "y": 0.21, "size": 0.24, "color": "#FF9FB3C8" },
 *                     { "field": "terminus", "x": 0.5, "y": 0.65, "size": 0.52 } ] }
 * "next": { "rows": [ { "text": "下一站", "x": 0.5, "y": 0.75, "size": 0.22 },
 *                     { "field": "next", "x": 0.5, "y": 0.35, "size": 0.5 } ] }
 * </pre>
 *
 * <p>每行认：{@code field}（{@code service}/{@code terminus}/{@code next}，见
 * {@link MmtrPidText#field}）、或 {@code text}（字面量，如"下一站"）；可选 {@code x}/{@code y}
 * （0..1，缺省 0.5）、{@code size}（牌高比例，缺省 0.4）、{@code align}（left/center/right）、
 * {@code color}、{@code prefix}（前缀，如"开往 "）、{@code suffix}。</p>
 *
 * <p><b>没有 {@code pid}/{@code next} 段 = 现在的默认版式</b>（两行、班次号在上、站名在下）：
 * 老包一个字节都不用改，行为与加这一层之前逐字相同。</p>
 *
 * <h2>哪一行不写</h2>
 * <p>取不到内容的行**不画**（而不是画个空位）：终点未知（回库趟）就只有班次号那一行；下一站未知时
 * 下一站牌整块不画 —— 与 {@link MmtrPidText#lines} 是同一条口径（{@code MmtrPidLayoutTests} 钉着
 * "默认版式画出来的字与 lines() 完全一致"）。</p>
 *
 * <h2>★ 这一层**不管"该不该挂牌"**（与"怎么摆"分开）</h2>
 * <p>"车不在作业单上 ⇒ 两块都不挂"、"下一站未知 ⇒ 下一站牌不挂"这两条**门**在
 * {@code MmtrPidBoard}（判据仍是 {@link MmtrPidText#lines}，一处说了算）。所以这里的
 * {@link #texts}/{@link #paint} 是**纯展示**的：{@code text} 那种字面量行（"下一站"这类固定字）
 * 在门开之前也会解析出字来 —— 门没开，整块牌根本不会被画。
 * 这样分开的好处是作者可以写"回库趟显示『回库』"这种**只有字面量**的版式，而"没作业单的车不挂牌"
 * 依然由门保证（{@code MmtrPidLayoutTests#literalRowsAreBoardGatedNotLayoutGated} 同时钉两边）。</p>
 */
public final class MmtrPidLayout {

	private static final String NAMESPACE = "mtr";
	private static final String FILE_PREFIX = "mmtr_anchors_";
	private static final String FILE_SUFFIX = ".json";

	private static final int DEFAULT_BACKGROUND = 0xFF101418;
	private static final int DEFAULT_TEXT = 0xFFF2F4F6;
	/** 默认版式的两行比例（= 加这一层之前写死的那套：主行 52% 牌高、副行 24%、整块居中）。 */
	private static final double DEFAULT_MAIN_SIZE = 0.52;
	private static final double DEFAULT_SUB_SIZE = 0.24;

	private static final Object2ObjectOpenHashMap<String, MmtrPidLayout> CACHE = new Object2ObjectOpenHashMap<>();

	private final String id;
	private final int background;
	private final int textColor;
	private final int pxPerMetre;
	private final ObjectArrayList<Row> rows;

	private MmtrPidLayout(String id, int background, int textColor, int pxPerMetre, ObjectArrayList<Row> rows) {
		this.id = id;
		this.background = background;
		this.textColor = textColor;
		this.pxPerMetre = pxPerMetre;
		this.rows = rows;
	}

	/**
	 * 一块牌上的一行。
	 *
	 * @param field  取哪个字段（{@link MmtrPidText#field}）；为空 = 用 {@code text} 那个字面量
	 * @param text   字面量（"下一站"这类固定字）
	 * @param prefix 写在内容前面的字（如"开往 "）
	 * @param suffix 写在内容后面的字
	 * @param x      水平位置（0..1 牌宽）
	 * @param y      垂直位置（0..1 牌高，0 = 牌底）
	 * @param size   字高（牌高的比例）
	 * @param align  水平对齐
	 * @param color  颜色（0 = 用整块牌的 textColor）
	 */
	private record Row(String field, String text, String prefix, String suffix, double x, double y, double size, IGui.HorizontalAlignment align, int color) {

		String resolve(String service, String terminus, String nextStation) {
			final String content = field.isEmpty()
				? (text == null ? "" : text)
				: MmtrPidText.field(field, service, terminus, nextStation);
			return content.isEmpty() ? "" : prefix + content + suffix;
		}
	}

	/**
	 * 这块牌上**当前会画出来的字**（自上而下，取不到内容的行已剔除）—— 用例与日志读它。
	 *
	 * <p>为什么不暴露整行：行里还有坐标/字号/颜色，那些是画的事；"写哪些字"才是判据。</p>
	 */
	public String[] texts(String service, String terminus, String nextStation) {
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		for (final Row row : rows) {
			final String text = row.resolve(service, terminus, nextStation);
			if (!text.isEmpty()) {
				out.add(text);
			}
		}
		return out.toArray(new String[0]);
	}

	/** 版式里有几行（含当前取不到内容、因而不会画的行）。 */
	public int rowCount() {
		return rows.size();
	}

	/** 第 {@code index} 行的**行高比例**（牌高的比例）；越界返回 0。用例用它钉"比例被钳在合法范围"。 */
	public double rowSize(int index) {
		return index >= 0 && index < rows.size() ? rows.get(index).size() : 0;
	}

	/** 第 {@code index} 行的水平/垂直位置（0..1 比例）；越界返回 -1。用例用它钉"越界坐标被钳回来"。 */
	public double rowX(int index) {
		return index >= 0 && index < rows.size() ? rows.get(index).x() : -1;
	}

	public double rowY(int index) {
		return index >= 0 && index < rows.size() ? rows.get(index).y() : -1;
	}

	/** 该车型、该种牌（水牌 / 下一站牌）的版式；没有配置就是默认版式。 */
	public static MmtrPidLayout get(String vehicleId, MmtrPidText.Board board) {
		if (vehicleId == null || vehicleId.isEmpty()) {
			return defaultLayout(board);
		}
		final String key = vehicleId + "|" + board;
		final MmtrPidLayout cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		final MmtrPidLayout layout = parse(vehicleId, board, readAnchorFile(vehicleId));
		CACHE.put(key, layout);
		return layout;
	}

	/** 资源重载时丢掉缓存（与 {@code MmtrVehicleAnchors.clearCache()} 同一个时机）。 */
	public static void clearCache() {
		CACHE.clear();
	}

	/** 版式身份（进重画签名）：作者改了版式、或换了车型，都会变。 */
	public String id() {
		return id;
	}

	/** 这块牌想要的像素密度（px/m）；0 = 用调用方的默认值。 */
	public int pxPerMetre() {
		return pxPerMetre;
	}

	/** 画一块牌：先铺底，再逐行摆字（取不到内容的行不画；超宽等比缩小）。 */
	public void paint(MmtrPanelCanvas canvas, String service, String terminus, String nextStation) {
		final double widthM = canvas.widthM();
		final double heightM = canvas.heightM();
		canvas.fill(0, 0, widthM, heightM, background);
		for (final Row row : rows) {
			final String text = row.resolve(service, terminus, nextStation);
			if (text.isEmpty()) {
				continue;
			}
			double sizeM = row.size() * heightM;
			final double maxTextWidthM = widthM * MAX_TEXT_WIDTH_RATIO;
			final double widthAtFullSize = canvas.textWidth(text, sizeM);
			if (widthAtFullSize > maxTextWidthM && widthAtFullSize > 0) {
				sizeM = sizeM * maxTextWidthM / widthAtFullSize;
			}
			canvas.text(text, row.x() * widthM, row.y() * heightM, sizeM, row.color() == 0 ? textColor : row.color(), row.align(), IGui.VerticalAlignment.CENTER);
		}
	}

	/** 文字最多占牌宽的多少（超了就整行等比缩小，免得被牌边切掉）。 */
	private static final double MAX_TEXT_WIDTH_RATIO = 0.90;

	/** 默认版式：与 notes/357 那套写死的排版逐字一致（主行 52%、副行 24%、整块居中）。 */
	public static MmtrPidLayout defaultLayout(MmtrPidText.Board board) {
		final ObjectArrayList<Row> rows = new ObjectArrayList<>();
		if (board == MmtrPidText.Board.DESTINATION) {
			rows.add(new Row("service", "", "", "", 0.5, 0.21, DEFAULT_SUB_SIZE, IGui.HorizontalAlignment.CENTER, 0));
			rows.add(new Row("terminus", "", "", "", 0.5, 0.65, DEFAULT_MAIN_SIZE, IGui.HorizontalAlignment.CENTER, 0));
		} else {
			rows.add(new Row("", MmtrPidText.NEXT_STATION_LABEL, "", "", 0.5, 0.21, DEFAULT_SUB_SIZE, IGui.HorizontalAlignment.CENTER, 0));
			rows.add(new Row("next", "", "", "", 0.5, 0.65, DEFAULT_MAIN_SIZE, IGui.HorizontalAlignment.CENTER, 0));
		}
		return new MmtrPidLayout("default-" + board, DEFAULT_BACKGROUND, DEFAULT_TEXT, 0, rows);
	}

	/**
	 * 解析锚点 JSON 里的版式段（{@code pid} 或 {@code next}）。
	 *
	 * @param vehicleId      车型 id（日志与身份用）
	 * @param anchorFileText 整份 {@code mmtr_anchors_<id>.json}
	 */
	public static MmtrPidLayout parse(String vehicleId, MmtrPidText.Board board, String anchorFileText) {
		final String key = board == MmtrPidText.Board.DESTINATION ? "pid" : "next";
		if (anchorFileText == null || anchorFileText.isEmpty()) {
			return defaultLayout(board);
		}
		try {
			final JsonElement root = JsonParser.parseString(anchorFileText);
			if (!root.isJsonObject()) {
				return defaultLayout(board);
			}
			final JsonElement section = root.getAsJsonObject().get(key);
			if (section == null || !section.isJsonObject()) {
				return defaultLayout(board);
			}
			final JsonObject object = section.getAsJsonObject();
			final int background = parseColor(getString(object, "background", ""), DEFAULT_BACKGROUND);
			final int textColor = parseColor(getString(object, "textColor", ""), DEFAULT_TEXT);
			final int pxPerMetre = Math.max(0, (int) getDouble(object, "pxPerMetre", 0));
			final ObjectArrayList<Row> rows = new ObjectArrayList<>();
			final JsonArray array = object.getAsJsonArray("rows");
			if (array != null) {
				for (final JsonElement element : array) {
					if (!element.isJsonObject()) {
						continue;
					}
					final JsonObject row = element.getAsJsonObject();
					final String field = getString(row, "field", "").trim().toLowerCase(java.util.Locale.ROOT);
					final String literal = getString(row, "text", "");
					if (field.isEmpty() && literal.isEmpty()) {
						// 既没字段也没字面量：这一行什么都写不出来，丢掉（并且说一声，免得作者以为画上了）
						Init.LOGGER.warn("[MMTR] {} 的 {} 版式里有一行既没有 field 也没有 text，已忽略", vehicleId, key);
						continue;
					}
					rows.add(new Row(
						field,
						literal,
						getString(row, "prefix", ""),
						getString(row, "suffix", ""),
						clamp01(getDouble(row, "x", 0.5)),
						clamp01(getDouble(row, "y", 0.5)),
						clamp(getDouble(row, "size", 0.4), 0.02, 1.5),
						parseAlign(getString(row, "align", "")),
						parseColor(getString(row, "color", ""), 0)
					));
				}
			}
			if (rows.isEmpty()) {
				Init.LOGGER.warn("[MMTR] 车型 {} 的 {} 版式是空的，用默认版式", vehicleId, key);
				return defaultLayout(board);
			}
			return new MmtrPidLayout(vehicleId + "@" + key + "@" + anchorFileText.hashCode(), background, textColor, pxPerMetre, rows);
		} catch (Exception e) {
			Init.LOGGER.error("[MMTR] 解析车型 {} 的 {} 版式失败", vehicleId, key, e);
			return defaultLayout(board);
		}
	}

	private static String readAnchorFile(String vehicleId) {
		final String[] content = {""};
		try {
			ResourceManagerHelper.readResource(new Identifier(NAMESPACE, FILE_PREFIX + vehicleId + FILE_SUFFIX), inputStream -> {
				try (final InputStream stream = inputStream) {
					content[0] = IOUtils.toString(stream, StandardCharsets.UTF_8);
				} catch (IOException e) {
					Init.LOGGER.error("Failed to read MMTR anchors for {}", vehicleId, e);
				}
			});
		} catch (Exception e) {
			Init.LOGGER.error("Failed to load MMTR anchors for {}", vehicleId, e);
		}
		return content[0];
	}

	private static IGui.HorizontalAlignment parseAlign(String align) {
		return switch (align == null ? "" : align.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "left" -> IGui.HorizontalAlignment.LEFT;
			case "right" -> IGui.HorizontalAlignment.RIGHT;
			default -> IGui.HorizontalAlignment.CENTER;
		};
	}

	/** {@code #RRGGBB}, {@code #AARRGGBB} or {@code 0xRRGGBB}; {@code fallback} when unusable. */
	private static int parseColor(String value, int fallback) {
		if (value == null || value.isEmpty()) {
			return fallback;
		}
		String hex = value.trim();
		if (hex.startsWith("#")) {
			hex = hex.substring(1);
		} else if (hex.startsWith("0x") || hex.startsWith("0X")) {
			hex = hex.substring(2);
		}
		try {
			final long parsed = Long.parseLong(hex, 16);
			return hex.length() <= 6 ? (int) (0xFF000000L | parsed) : (int) parsed;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static double clamp01(double value) {
		return clamp(value, 0, 1);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String getString(JsonObject object, String key, String fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static double getDouble(JsonObject object, String key, double fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
	}
}
