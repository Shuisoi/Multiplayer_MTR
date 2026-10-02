package mmtr.facepreview;

import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.mod.mmtr.MmtrPidText;
import org.mtr.mod.mmtr.face.MmtrFaceData;
import org.mtr.mod.mmtr.face.MmtrFaceDocument;
import org.mtr.mod.render.panel.MmtrFaceElements;
import org.mtr.mod.render.panel.MmtrFaceImages;
import org.mtr.mod.render.panel.MmtrPanelCanvas;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **车辆动态面离线预览**（notes/359 · F1）：把一块面画成 PNG，不用起客户端。
 *
 * <p>跑的是**真的那条链**：{@link MmtrFaceDocument} 解析 → {@link MmtrFaceElements} 画
 * （与游戏里同一份 Java2D 与同一套字体）→ {@code writePng}。于是"改一段 JSON → 看一眼"是一秒钟的事，
 * 而游戏里迭代要起客户端 + 找车 + 截图。</p>
 *
 * <pre>
 * java -Djava.awt.headless=true -cp … mmtr.facepreview.FacePreview \
 *      --anchors &lt;mmtr_anchors_x.json&gt; --list
 * java … --anchors … --face face_1 --preset stopped --out board.png
 * java … --anchors … --face pid_1 --preset running --widthM 1.6 --heightM 0.12
 * </pre>
 *
 * <h2>为什么"数据"要用预设而不是只给几个字段</h2>
 * <p>一块面好不好看**取决于它在什么状态下被看到**（跑着 / 停站开门 / 回库 / 没任务）。所以这里内置四个
 * 预设（{@link #preset}），并把它们**打出来**（{@code --print-data}）—— 作者照着改自己的字段即可。
 * 真实数据一律来自引擎镜像（{@code MmtrFaceFields} 那 52 行），预设只是"一组合理的值"。</p>
 *
 * <h2>诚实的边界</h2>
 * <p>离线预览的中文字形走系统兜底，与游戏里的 HarmonyOS 有细微差别 —— **排版看这张图，字形以游戏为准**
 * （见 notes/358 的同一句话）。另外它读的是**磁盘上的锚点 JSON**，不是打包前的车辆配置：
 * 要预览还没打包的配置，先跑一次 {@code pack-vehicle.ps1}。</p>
 */
public final class FacePreview {

	private static final int DEFAULT_PX_PER_METRE = 512;

	public static void main(String[] args) {
		final Map<String, String> options = new LinkedHashMap<>();
		final List<String> dataPairs = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			final String argument = args[i];
			if (!argument.startsWith("--")) {
				continue;
			}
			final String key = argument.substring(2);
			if (key.equals("data") && i + 1 < args.length) {
				dataPairs.add(args[++i]);
			} else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
				options.put(key, args[++i]);
			} else {
				options.put(key, "true");
			}
		}

		final String anchorPath = options.get("anchors");
		if (anchorPath == null) {
			usage();
			return;
		}
		final String text = read(anchorPath);
		if (text == null) {
			return;
		}
		final String vehicleId = options.getOrDefault("vehicle", "preview");

		if (options.containsKey("list")) {
			list(text);
			return;
		}

		// ---- 选哪块面 ----
		final String faceName = options.get("face");
		final MmtrFaceDocument document;
		if (faceName != null) {
			document = MmtrFaceDocument.fromAnchors(vehicleId, faceName, text);
			if (document == null) {
				System.err.println("[face-preview] 锚点文件里没有 faces." + faceName + " 这块面。有这些：");
				list(text);
				System.exit(2);
				return;
			}
		} else if (options.containsKey("builtin")) {
			final boolean next = "next".equalsIgnoreCase(options.get("builtin"));
			document = MmtrFaceDocument.builtinPid(next ? MmtrPidText.Board.NEXT_STATION : MmtrPidText.Board.DESTINATION, vehicleId, text);
		} else {
			System.err.println("[face-preview] 要 --face <锚点名> 或 --builtin pid|next（先 --list 看看有什么）");
			System.exit(2);
			return;
		}

		// ---- 数据：预设 + --data 覆盖 ----
		final Map<String, Object> values = preset(options.getOrDefault("preset", "running"));
		for (final String pair : dataPairs) {
			final int equals = pair.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			values.put(pair.substring(0, equals).trim(), parseValue(pair.substring(equals + 1)));
		}
		final MmtrFaceData data = MmtrFaceData.of(values::get);

		// ---- 牌面尺寸：优先锚点，其次命令行，最后默认 ----
		final double[] size = anchorSize(text, faceName == null ? null : faceName, options.containsKey("builtin") && "next".equalsIgnoreCase(options.get("builtin")));
		double widthM = size != null ? size[0] : 1.24;
		double heightM = size != null ? size[1] : 0.22;
		if (options.containsKey("widthM") && options.containsKey("heightM")) {
			widthM = Double.parseDouble(options.get("widthM"));
			heightM = Double.parseDouble(options.get("heightM"));
		}
		int pxPerMetre = document.pxPerMetre() > 0 ? document.pxPerMetre() : DEFAULT_PX_PER_METRE;
		if (options.containsKey("pxPerMetre")) {
			pxPerMetre = Integer.parseInt(options.get("pxPerMetre"));
		}

		// ---- 门：与游戏同一条口径（require 不成立 ⇒ 不画）----
		final boolean visible = document.visible(data.asMap());
		// ---- 时钟（F3）：动画/多页都看它。--timeMs 给一个固定时刻，不给就用 0（第一帧的样子）----
		final long timeMs = options.containsKey("timeMs") ? Long.parseLong(options.get("timeMs")) : 0;
		final int page = options.containsKey("page") ? Integer.parseInt(options.get("page")) : document.pageIndex(data.asMap(), timeMs);
		// ---- 图片：离线也要能看见 image 元素（资源包目录当兜底；见 MmtrFaceImages 的三条路）----
		applyImageRoots(options.get("pack"), anchorPath);
		final List<MmtrFaceDocument.Drawable> plan = page < 0 ? document.plan(data.asMap(), timeMs) : document.planPage(page, data.asMap(), timeMs);
		System.out.println("[face-preview] 文档=" + document.id() + "  侧=" + document.side()
			+ "  页=" + page + "/" + document.pageCount() + "（这一页 " + plan.size() + " 个元素）"
			+ (document.animated() ? "  有动画 " + document.fps() + " fps" : "")
			+ (document.drum() != null ? "  翻牌机 " + document.drum().count() + " 面" : "")
			+ "  t=" + timeMs + "ms"
			+ "  牌面=" + round(widthM) + "x" + round(heightM) + "m  px/m=" + pxPerMetre);
		System.out.println("[face-preview] 会画出来的字=" + (document.texts(data.asMap(), timeMs).isEmpty() ? "（没有）" : String.join(" / ", document.texts(data.asMap(), timeMs)))
			+ "（按 data+timeMs 自动选页；`--page` 只影响出图那一页）");
		if (options.containsKey("print-data")) {
			System.out.println("[face-preview] 数据=" + data.describe());
		}
		if (!visible && !options.containsKey("force")) {
			System.out.println("[face-preview] 按 require 这块面**不画**（要硬看版式就加 --force）");
			return;
		}

		final MmtrPanelCanvas canvas = MmtrPanelCanvas.create(widthM, heightM, pxPerMetre);
		MmtrFaceElements.paintPage(canvas, document, options.containsKey("page") ? page : -1, data.asMap(), timeMs);
		final String outPath = options.getOrDefault("out", "face-preview-" + (faceName == null ? "builtin" : faceName) + ".png");
		final File out = new File(outPath);
		if (out.getParentFile() != null) {
			out.getParentFile().mkdirs();
		}
		if (!canvas.writePng(out)) {
			System.err.println("[face-preview] 写 PNG 失败：" + out.getAbsolutePath());
			System.exit(1);
			return;
		}
		System.out.println("[face-preview] 写出 " + out.getAbsolutePath() + "（" + canvas.widthPx() + "x" + canvas.heightPx() + "px）");
	}

	/**
	 * {@code image} 元素的图片从哪找（离线没有游戏进程，走不到原版资源包那条路）。
	 *
	 * <p>优先 {@code --pack <目录>[;目录…]}（每个目录是一个资源包根，里面有 {@code assets/}）；
	 * 没给就自己找：从锚点文件往上找一个叫 {@code resourcepacks} 的目录，
	 * 再试工作区里那两处常见位置。都找不到就只影响 {@code image} 元素（画成洋红占位框），别的照画。</p>
	 */
	private static void applyImageRoots(String packOption, String anchorPath) {
		final List<java.nio.file.Path> roots = new ArrayList<>();
		if (packOption != null) {
			for (final String piece : packOption.split("[;,]")) {
				if (!piece.isBlank()) {
					roots.add(Paths.get(piece.trim()));
				}
			}
		}
		if (roots.isEmpty()) {
			try {
				java.nio.file.Path directory = Paths.get(anchorPath).toAbsolutePath().getParent();
				while (directory != null && roots.isEmpty()) {
					final java.nio.file.Path packs = directory.resolve("resourcepacks");
					if (Files.isDirectory(packs)) {
						try (final var stream = Files.list(packs)) {
							stream.filter(Files::isDirectory).forEach(roots::add);
						}
					}
					directory = directory.getParent();
				}
			} catch (Exception e) {
				// 找不着就算了：下面还有两处常见位置
			}
		}
		for (final String candidate : new String[]{"mmtr/game/fabric/run/resourcepacks", "run/resourcepacks"}) {
			final java.nio.file.Path packs = Paths.get(candidate);
			if (roots.isEmpty() && Files.isDirectory(packs)) {
				try (final var stream = Files.list(packs)) {
					stream.filter(Files::isDirectory).forEach(roots::add);
				} catch (Exception e) {
					// 同上
				}
			}
		}
		if (!roots.isEmpty()) {
			MmtrFaceImages.setFallbackRoots(roots);
			System.out.println("[face-preview] 图片兜底目录=" + roots);
		}
	}

	/** 四个状态预设：跑着 / 停站开门 / 回库趟 / 没任务。字段名与 {@code MmtrFaceFields} 一致。 */	private static Map<String, Object> preset(String name) {
		final Map<String, Object> values = new LinkedHashMap<>();
		// 公共：这是一趟 00109，开往海山，从鸥湾出发，司机在 A 端
		values.put("pid.service", "00109");
		values.put("pid.terminus", "海山");
		values.put("pid.next", "鸥湾");
		values.put("job.id", "00109");
		values.put("job.step", 3.0);
		values.put("job.steps", 12.0);
		values.put("job.note", "鸥湾站停车");
		values.put("mode", "AUTO");
		values.put("driver", "Shuisoi");
		values.put("active", true);
		values.put("pinned", false);
		values.put("cab.end", "A");
		values.put("cab.carIndex", 0.0);
		values.put("limitKmh", 100.0);
		values.put("light.a", 2.0);
		values.put("light.b", 0.0);
		values.put("clock", "09:41");

		switch (name == null ? "running" : name.toLowerCase()) {
			case "stopped" -> {
				values.put("speed", 0.0);
				values.put("lzb.supervising", true);
				values.put("lzb.ceilingKmh", 40.0);
				values.put("lzb.targetKmh", 0.0);
				values.put("lzb.targetM", 0.0);
				values.put("hold.reason", "");
				values.put("job.note", "站台作业：开门");
			}
			case "return" -> {
				// ★ 回库趟：本趟没有站台目标 —— 终点与下一站都为空（"不谎报终点"那条口径）
				values.put("pid.terminus", "");
				values.put("pid.next", "");
				values.put("speed", 0.008);
				values.put("job.note", "回库");
				values.put("limitKmh", 25.0);
			}
			case "idle" -> {
				values.put("pid.service", "");
				values.put("pid.terminus", "");
				values.put("pid.next", "");
				values.put("job.id", "");
				values.put("job.step", 0.0);
				values.put("job.steps", 0.0);
				values.put("mode", "");
				values.put("driver", "");
				values.put("active", false);
				values.put("pinned", true);
				values.put("speed", 0.0);
			}
			default -> {
				values.put("speed", 0.0166);   // ≈ 60 km/h
				values.put("lzb.supervising", true);
				values.put("lzb.ceilingKmh", 100.0);
				values.put("lzb.targetKmh", 80.0);
				values.put("lzb.targetM", 1240.0);
				values.put("handle.throttle", 3.0);
				values.put("hold.reason", "");
			}
		}
		return values;
	}

	/** {@code --data} 的值：先当数字，再当布尔，否则原样当字符串（{@code k=} = 空串）。 */
	private static Object parseValue(String value) {
		if (value.equals("true") || value.equals("false")) {
			return Boolean.parseBoolean(value);
		}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return value;
		}
	}

	/** 打印这个锚点文件里有什么：所有面文档的键（= 锚点名）+ 所有锚点。 */
	private static void list(String text) {
		try {
			final JsonElement root = JsonParser.parseString(text);
			if (!root.isJsonObject()) {
				System.err.println("[face-preview] 不是 JSON 对象");
				return;
			}
			final JsonObject object = root.getAsJsonObject();
			System.out.println("[face-preview] faces（键就是锚点名，写错了这块面永远不会被画）：");
			final JsonElement faces = object.get("faces");
			if (faces == null || !faces.isJsonObject() || faces.getAsJsonObject().size() == 0) {
				System.out.println("  （没有 faces 段 —— 老包：水牌走 pid/next 段，其他锚点没有动态面）");
			} else {
				for (final String key : faces.getAsJsonObject().keySet()) {
					final JsonObject face = faces.getAsJsonObject().getAsJsonObject(key);
					final int elements = face.has("elements") && face.get("elements").isJsonArray() ? face.getAsJsonArray("elements").size() : 0;
					System.out.println("  " + key + "   元素=" + elements
						+ (face.has("require") ? "  有 require 门" : "")
						+ (face.has("background") ? "  底色=" + face.get("background").getAsString() : "  底色=透明"));
				}
			}
			System.out.println("[face-preview] anchors：");
			final JsonElement anchors = object.get("anchors");
			if (anchors != null && anchors.isJsonArray()) {
				for (final JsonElement element : anchors.getAsJsonArray()) {
					if (!element.isJsonObject()) {
						continue;
					}
					final JsonObject anchor = element.getAsJsonObject();
					System.out.println("  " + string(anchor, "name") + "  kind=" + string(anchor, "kind")
						+ " car=" + string(anchor, "car") + " cab=" + string(anchor, "cab")
						+ " size=" + round(number(anchor, "widthM")) + "x" + round(number(anchor, "heightM")) + "m"
						+ (faces != null && faces.isJsonObject() && faces.getAsJsonObject().has(string(anchor, "name")) ? "   ← 有面文档" : ""));
				}
			}
		} catch (Exception e) {
			System.err.println("[face-preview] 解析锚点 JSON 失败：" + e);
		}
	}

	/** 锚点 JSON 里那块面的 {@code [widthM, heightM]}：先按面文档的键找锚点，再按 kind 找。 */
	private static double[] anchorSize(String anchorFileText, String faceName, boolean nextKind) {
		try {
			final JsonElement root = JsonParser.parseString(anchorFileText);
			if (!root.isJsonObject()) {
				return null;
			}
			final JsonElement anchors = root.getAsJsonObject().get("anchors");
			if (anchors == null || !anchors.isJsonArray()) {
				return null;
			}
			JsonObject fallback = null;
			for (final JsonElement element : anchors.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				final JsonObject anchor = element.getAsJsonObject();
				final String name = string(anchor, "name");
				if (faceName != null && faceName.equals(name)) {
					return new double[]{number(anchor, "widthM"), number(anchor, "heightM")};
				}
				final String kind = string(anchor, "kind");
				if (fallback == null && (nextKind ? "next" : "pid").equals(kind)) {
					fallback = anchor;
				}
			}
			return fallback == null ? null : new double[]{number(fallback, "widthM"), number(fallback, "heightM")};
		} catch (Exception e) {
			return null;
		}
	}

	private static String read(String path) {
		try {
			return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
		} catch (Exception e) {
			System.err.println("[face-preview] 读不了 " + path + "：" + e);
			System.exit(2);
			return null;
		}
	}

	private static String string(JsonObject object, String key) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	private static double number(JsonObject object, String key) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? 0 : element.getAsDouble();
	}

	private static String round(double value) {
		return String.format(java.util.Locale.ROOT, "%.3f", value);
	}

	private static void usage() {
		System.err.println("用法: FacePreview --anchors <mmtr_anchors_x.json> [--list] "
			+ "[--face <锚点名> | --builtin pid|next] [--vehicle <车型id>] [--out <png>] "
			+ "[--preset running|stopped|return|idle] [--data k=v]... [--widthM x --heightM y] "
			+ "[--pxPerMetre n] [--force] [--print-data]");
		System.err.println("  --list        列出这块车的所有面文档与锚点（不知道 --face 写什么时先看它）");
		System.err.println("  --force       即使 require 门不成立也画出来（只想看版式时用）");
		System.err.println("  例: --anchors saf420.json --face face_1 --preset stopped --out stopped.png");
		System.exit(2);
	}
}
