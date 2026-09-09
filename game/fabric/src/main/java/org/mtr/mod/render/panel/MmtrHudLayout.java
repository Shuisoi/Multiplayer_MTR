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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * C6: the 2D dashboard layout of ONE vehicle model, read from the {@code hud} object of
 * {@code assets/mtr/mmtr_anchors_<vehicleId>.json} (written by the OBJ packager, so it travels with
 * the model). Different rolling stock has different instrument panels, so the layout is per model -
 * a locomotive, a coach and a freight wagon each author their own; the two ends of one car share it.
 *
 * <p>Every positional value is a FRACTION of the panel, not a metre value, so one layout survives a
 * model whose {@code mmtr_hud} face is resized: {@code x}/{@code w}/{@code x1}/{@code x2} are
 * fractions of the panel width, {@code y}/{@code h}/{@code size}/{@code radius}/{@code lineWidth} of
 * its height. The client multiplies them by the anchor's {@code widthM}/{@code heightM} before
 * painting, and the {@link MmtrPanelCanvas} stays metric.</p>
 *
 * <pre>
 * "hud": {
 *   "background": "#FF05080C",
 *   "widgets": [
 *     {"kind": "roundRect", "x": 0.015, "y": 0.06, "w": 0.97, "h": 0.88, "radius": 0.08, "color": "#FF0E141C"},
 *     {"kind": "speed", "x": 0.5, "y": 0.6, "size": 0.52, "color": "#FFFFFFFF", "align": "center"},
 *     {"kind": "text",  "text": "km/h", "x": 0.5, "y": 0.19, "size": 0.2, "color": "#FFD2E6F7"}
 *   ]
 * }
 * </pre>
 *
 * <p>Widget kinds: {@code speed} (live km/h), {@code limit} (live speed limit, {@code --} when 0),
 * {@code text} (static), and the shapes {@code rect} / {@code roundRect} / {@code line} /
 * {@code circle} / {@code arc}. Colours are {@code #RRGGBB} or {@code #AARRGGBB}.</p>
 *
 * <p>A model without a {@code hud} object falls back to {@link #DEFAULT}, which is the plain
 * centred speed readout the panel used before layouts existed.</p>
 */
public final class MmtrHudLayout {

	/** Anchor file the layout rides in, next to the model. */
	private static final String NAMESPACE = "mtr";
	private static final String FILE_PREFIX = "mmtr_anchors_";
	private static final String FILE_SUFFIX = ".json";

	private static final int DEFAULT_BACKGROUND = 0xFF05080C;
	private static final int DEFAULT_PANEL = 0xFF0E141C;
	private static final int DEFAULT_TEXT = 0xFFFFFFFF;
	private static final int DEFAULT_UNIT = 0xFFD2E6F7;

	/** The layout used when a model does not author one: background, panel, centred speed, unit. */
	public static final MmtrHudLayout DEFAULT = defaultLayout();

	private static final Object2ObjectOpenHashMap<String, MmtrHudLayout> CACHE = new Object2ObjectOpenHashMap<>();

	private final String id;
	private final int background;
	private final ObjectArrayList<Widget> widgets;

	private MmtrHudLayout(String id, int background, ObjectArrayList<Widget> widgets) {
		this.id = id;
		this.background = background;
		this.widgets = widgets;
	}

	/** @return the layout of a model, or {@link #DEFAULT} when it does not author one */
	public static MmtrHudLayout get(String vehicleId) {
		if (vehicleId == null || vehicleId.isEmpty()) {
			return DEFAULT;
		}
		final MmtrHudLayout cached = CACHE.get(vehicleId);
		if (cached != null) {
			return cached;
		}
		final MmtrHudLayout layout = read(vehicleId);
		CACHE.put(vehicleId, layout);
		return layout;
	}

	/** Drops the cache so a reloaded resource pack is picked up. */
	public static void clearCache() {
		CACHE.clear();
	}

	/**
	 * @return an identity of this layout for the panel's redraw signature; changes when the authored
	 * layout changes, so a resource reload repaints the panel
	 */
	public String id() {
		return id;
	}

	/** Paints the layout onto a canvas whose size comes from the {@code mmtr_hud} anchor. */
	public void paint(MmtrPanelCanvas canvas, int speedKmh, long limitKmh) {
		final double width = canvas.widthM();
		final double height = canvas.heightM();
		canvas.fill(0, 0, width, height, background);

		for (final Widget widget : widgets) {
			switch (widget.kind) {
				case "speed" -> canvas.text(String.valueOf(speedKmh), widget.x * width, widget.y * height, widget.size * height, widget.color, widget.align, IGui.VerticalAlignment.CENTER);
				case "limit" -> canvas.text(limitKmh > 0 ? String.valueOf(limitKmh) : "--", widget.x * width, widget.y * height, widget.size * height, widget.color, widget.align, IGui.VerticalAlignment.CENTER);
				case "text" -> canvas.text(widget.text, widget.x * width, widget.y * height, widget.size * height, widget.color, widget.align, IGui.VerticalAlignment.CENTER);
				case "rect" -> canvas.fill(widget.x * width, widget.y * height, widget.w * width, widget.h * height, widget.color);
				case "roundRect" -> canvas.fillRoundRect(widget.x * width, widget.y * height, widget.w * width, widget.h * height, widget.radius * height, widget.color);
				case "line" -> canvas.line(widget.x * width, widget.y * height, widget.x2 * width, widget.y2 * height, widget.lineWidth * height, widget.color);
				case "circle" -> canvas.circle(widget.x * width, widget.y * height, widget.radius * height, widget.color);
				case "arc" -> canvas.arc(widget.x * width, widget.y * height, widget.radius * height, widget.lineWidth * height, widget.start, widget.end, widget.color);
				default -> {
				}
			}
		}
	}

	/**
	 * One drawn element. The kind decides which fields matter; everything unused stays at its default,
	 * so a layout file only writes what it needs. Positions are fractions of the panel (see the class
	 * doc). The parser fills the fields in, which is why they are not final.
	 */
	private static final class Widget {

		private final String kind;
		private double x = 0.5;
		private double y = 0.5;
		private double w;
		private double h;
		private double size;
		private double radius;
		private double x2 = 0.5;
		private double y2 = 0.5;
		private double lineWidth;
		private double start;
		private double end = 360;
		private int color = DEFAULT_TEXT;
		private IGui.HorizontalAlignment align = IGui.HorizontalAlignment.CENTER;
		private String text = "";

		private Widget(String kind) {
			this.kind = kind;
		}

		private static Widget shape(String kind, double x, double y, double w, double h, double radius, int color) {
			final Widget widget = new Widget(kind);
			widget.x = x;
			widget.y = y;
			widget.w = w;
			widget.h = h;
			widget.radius = radius;
			widget.color = color;
			return widget;
		}

		private static Widget live(String kind, double x, double y, double size, int color, IGui.HorizontalAlignment align) {
			final Widget widget = new Widget(kind);
			widget.x = x;
			widget.y = y;
			widget.size = size;
			widget.color = color;
			widget.align = align;
			return widget;
		}

		private static Widget parse(JsonObject object, String kind) {
			final Widget widget = new Widget(kind);
			widget.x = getDouble(object, "x", widget.x);
			widget.y = getDouble(object, "y", widget.y);
			widget.w = getDouble(object, "w", 0);
			widget.h = getDouble(object, "h", 0);
			widget.size = getDouble(object, "size", 0);
			widget.radius = getDouble(object, "radius", 0);
			widget.x2 = getDouble(object, "x2", widget.x);
			widget.y2 = getDouble(object, "y2", widget.y);
			widget.lineWidth = getDouble(object, "lineWidth", getDouble(object, "width", 0));
			widget.start = getDouble(object, "start", 0);
			widget.end = getDouble(object, "end", 360);
			widget.color = parseColor(getString(object, "color", ""), DEFAULT_TEXT);
			widget.align = parseAlign(getString(object, "align", "center"));
			widget.text = getString(object, "text", "");
			return widget;
		}
	}

	private static MmtrHudLayout defaultLayout() {
		final ObjectArrayList<Widget> widgets = new ObjectArrayList<>();
		widgets.add(Widget.shape("roundRect", 0.015, 0.06, 0.97, 0.88, 0.08, DEFAULT_PANEL));
		widgets.add(Widget.live("speed", 0.5, 0.60, 0.52, DEFAULT_TEXT, IGui.HorizontalAlignment.CENTER));
		final Widget unit = Widget.live("text", 0.5, 0.19, 0.20, DEFAULT_UNIT, IGui.HorizontalAlignment.CENTER);
		unit.text = "km/h";
		widgets.add(unit);
		return new MmtrHudLayout("default", DEFAULT_BACKGROUND, widgets);
	}

	private static MmtrHudLayout read(String vehicleId) {
		final String[] content = {""};
		try {
			ResourceManagerHelper.readResource(new Identifier(NAMESPACE, FILE_PREFIX + vehicleId + FILE_SUFFIX), inputStream -> {
				try (final InputStream stream = inputStream) {
					content[0] = IOUtils.toString(stream, StandardCharsets.UTF_8);
				} catch (IOException e) {
					Init.LOGGER.error("[MMTR] failed to read the HUD layout of {}", vehicleId, e);
				}
			});
		} catch (Exception e) {
			// No anchors file at all is normal for a model without a dashboard.
			return DEFAULT;
		}

		if (content[0].isEmpty()) {
			return DEFAULT;
		}

		try {
			final JsonElement root = JsonParser.parseString(content[0]);
			if (!root.isJsonObject()) {
				return DEFAULT;
			}
			final JsonElement hud = root.getAsJsonObject().get("hud");
			if (hud == null || !hud.isJsonObject()) {
				return DEFAULT;
			}
			final JsonObject hudObject = hud.getAsJsonObject();
			final int background = parseColor(getString(hudObject, "background", ""), DEFAULT_BACKGROUND);
			final ObjectArrayList<Widget> widgets = new ObjectArrayList<>();
			final JsonArray array = hudObject.getAsJsonArray("widgets");
			if (array != null) {
				for (final JsonElement element : array) {
					if (!element.isJsonObject()) {
						continue;
					}
					final JsonObject object = element.getAsJsonObject();
					final String kind = getString(object, "kind", "");
					if (kind.isEmpty()) {
						continue;
					}
					widgets.add(Widget.parse(object, kind));
				}
			}
			if (widgets.isEmpty()) {
				Init.LOGGER.warn("[MMTR] model {} has an empty hud layout, using the default panel", vehicleId);
				return DEFAULT;
			}
			// The identity changes whenever the authored layout changes, so a resource reload repaints.
			return new MmtrHudLayout(vehicleId + "@" + content[0].hashCode(), background, widgets);
		} catch (Exception e) {
			Init.LOGGER.error("[MMTR] failed to parse the HUD layout of {}", vehicleId, e);
			return DEFAULT;
		}
	}

	private static IGui.HorizontalAlignment parseAlign(String align) {
		return switch (align == null ? "" : align.toLowerCase()) {
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

	private static String getString(JsonObject object, String key, String fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static double getDouble(JsonObject object, String key, double fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
	}
}
