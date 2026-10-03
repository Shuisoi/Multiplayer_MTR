package mmtr.pidpreview;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.mod.mmtr.MmtrPidText;
import org.mtr.mod.render.panel.MmtrPanelCanvas;
import org.mtr.mod.render.panel.MmtrPidLayout;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 水牌版式离线预览（notes/358）：把 {@code mmtr_anchors_<车型>.json} 里那块牌**画成一张 PNG**，
 * 不用起客户端。
 *
 * <p>跑的是**真的那条链**：{@link MmtrPidLayout#parse} 解析版式 → {@link MmtrPanelCanvas} 画
 * （与游戏里同一份 Java2D 代码、同一套字体）→ {@code writePng}。于是"改版式 → 看一眼"是一秒钟的事，
 * 而游戏里迭代要起客户端 + 找车 + 截图。</p>
 *
 * <p><b>牌面尺寸默认从锚点读</b>（{@code widthM/heightM}，与游戏里完全一致）；给了命令行尺寸就覆盖它 ——
 * 用来回答"这块牌做成 1.6 × 0.12 m 好不好看"。所有位置/字号都是比例，所以同一份版式在任何尺寸下都自动等比。</p>
 *
 * <pre>
 * java -Djava.awt.headless=true -cp … mmtr.pidpreview.PidPreview \
 *      &lt;mmtr_anchors_x.json&gt; &lt;out.png&gt; &lt;pid|next&gt; &lt;班次号&gt; &lt;终点&gt; &lt;下一站&gt; [宽m 高m px/m]
 * </pre>
 */
public final class PidPreview {

	public static void main(String[] args) throws Exception {
		if (args.length < 6) {
			System.err.println("用法: PidPreview <mmtr_anchors_x.json> <out.png> <pid|next> <班次号> <终点> <下一站> [宽m 高m px/m]");
			System.exit(2);
		}
		final String anchorPath = args[0];
		final String outPath = args[1];
		final boolean nextBoard = "next".equalsIgnoreCase(args[2]);
		final MmtrPidText.Board board = nextBoard ? MmtrPidText.Board.NEXT_STATION : MmtrPidText.Board.DESTINATION;
		final String service = args[3];
		final String terminus = args[4];
		final String nextStation = args[5];

		final String text = new String(Files.readAllBytes(Paths.get(anchorPath)), StandardCharsets.UTF_8);
		double widthM = 1.24;
		double heightM = 0.22;
		final double[] anchorSize = anchorSize(text, nextBoard ? "next" : "pid");
		if (anchorSize != null) {
			widthM = anchorSize[0];
			heightM = anchorSize[1];
		}
		int pxPerMetre = 512;
		if (args.length >= 9) {
			widthM = Double.parseDouble(args[6]);
			heightM = Double.parseDouble(args[7]);
			pxPerMetre = Integer.parseInt(args[8]);
		}

		final MmtrPidLayout layout = MmtrPidLayout.parse("preview", board, text);
		if (layout.pxPerMetre() > 0 && args.length < 9) {
			pxPerMetre = layout.pxPerMetre();
		}

		// 与 MmtrPidBoard 同一条口径门：不在作业单上（班次号空）或下一站牌没站名 ⇒ 不画
		if (MmtrPidText.lines(board, service, terminus, nextStation).length == 0) {
			System.out.println("[pid-preview] 这块牌按口径不画（" + MmtrPidText.describe(board, MmtrPidText.lines(board, service, terminus, nextStation)) + "）");
			System.exit(0);
		}

		final MmtrPanelCanvas canvas = MmtrPanelCanvas.create(widthM, heightM, pxPerMetre);
		layout.paint(canvas, service, terminus, nextStation);
		final File out = new File(outPath);
		if (out.getParentFile() != null) {
			out.getParentFile().mkdirs();
		}
		canvas.writePng(out);

		System.out.println("[pid-preview] " + (nextBoard ? "下一站牌" : "水牌")
			+ "  版式=" + layout.id()
			+ "  牌面=" + widthM + "x" + heightM + "m  " + canvas.widthPx() + "x" + canvas.heightPx() + "px"
			+ "  行=" + String.join(" / ", layout.texts(service, terminus, nextStation)));
		System.out.println("[pid-preview] 写出 " + out.getAbsolutePath());
	}

	/** 锚点 JSON 里那种牌的 {@code [widthM, heightM]}（找不到 ⇒ null，调用方用默认尺寸）。 */
	private static double[] anchorSize(String anchorFileText, String kind) {
		try {
			final JsonElement root = JsonParser.parseString(anchorFileText);
			if (!root.isJsonObject()) {
				return null;
			}
			final JsonArray anchors = root.getAsJsonObject().getAsJsonArray("anchors");
			if (anchors == null) {
				return null;
			}
			for (final JsonElement element : anchors) {
				if (!element.isJsonObject()) {
					continue;
				}
				final JsonObject anchor = element.getAsJsonObject();
				if (anchor.has("kind") && kind.equals(anchor.get("kind").getAsString())) {
					return new double[]{anchor.get("widthM").getAsDouble(), anchor.get("heightM").getAsDouble()};
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}
}
