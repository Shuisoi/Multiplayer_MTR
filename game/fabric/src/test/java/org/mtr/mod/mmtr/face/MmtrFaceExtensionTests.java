package org.mtr.mod.mmtr.face;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mtr.mod.render.panel.MmtrFaceElements;
import org.mtr.mod.render.panel.MmtrFaceExtensions;
import org.mtr.mod.render.panel.MmtrPanelCanvas;

import javax.imageio.ImageIO;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * **F4 的判据：第三方扩展真的能生效，而且全程不碰引擎/模组源码**（notes/361）。
 *
 * <p>这个用例做的事就是"一个附属模组作者会做的事"：</p>
 * <ol>
 *   <li>把 {@code sandbox/face-addon/src} 下的 Java **真的编译**（JDK 自带的编译器 API）；</li>
 *   <li>把编译产物（含 {@code META-INF/services}）用 {@link java.net.URLClassLoader} 装进来；</li>
 *   <li>交给 {@link MmtrFaceExtensions#load(ClassLoader)}（走 ServiceLoader，与客户端启动同一条路）；</li>
 *   <li>然后断言四样东西都在**面文档**里生效了：元素画得出、算子算得出、过滤器用得上、字段进得了快照。</li>
 * </ol>
 *
 * <p>为什么值得这么写：SPI 最容易的失败不是"没生效"，而是"看起来生效了"——
 * 只有真的编译第三方代码、真的走发现那条路、真的画出一张图，才算把升级阶梯的第三级钉住。</p>
 */
public final class MmtrFaceExtensionTests {

	/** 用例的工作目录是 {@code mmtr/game/fabric}。 */
	private static final Path ADDON_SOURCE = Path.of("..", "..", "..", "sandbox", "face-addon", "src");
	/**
	 * 编译产物落在 {@code sandbox} 下，**不用 %TEMP%**：这台机器上给 java 进程的临时目录不可写
	 * （`AccessDeniedException`）—— 同一个毛病在离线出图工具里表现为"ImageIO 一张图都读不出"
	 * （notes/360 §5）。落在工作区里还顺带让人能直接翻编译产物。
	 */
	private static final Path ADDON_OUTPUT = Path.of("..", "..", "..", "sandbox", "face-addon", "out");
	private static final String EXTENSION_CLASS = "vendor.faceaddon.AddonFaceExtension";

	@AfterEach
	public void tearDown() {
		// 扩展是**全局**状态：跑完必须清干净，否则会影响同一批里别的用例（"铁律：用例之间不许串味"）
		MmtrFaceExtensions.clear();
		MmtrFaceWarnings.clear();
	}

	@Test
	public void aThirdPartyExtensionTakesEffectWithoutTouchingTheMod() {
		final ClassLoader addonClassLoader = compileAddon();
		assertNotNull(addonClassLoader, "扩展没编译出来");

		final int loaded = MmtrFaceExtensions.load(addonClassLoader);
		assertTrue(loaded >= 1, "ServiceLoader 没找到 " + EXTENSION_CLASS + "（loaded=" + loaded + "）");
		assertTrue(MmtrFaceExtensions.loaded().contains(EXTENSION_CLASS), "装载清单里没有 " + EXTENSION_CLASS);

		// ① 元素：类型认得，且真的往画布上画了东西（画一张图再读像素回来）
		assertTrue(MmtrFaceElements.knows("vendor:bar"), "扩展元素类型没注册上");
		assertTrue(MmtrFaceElements.types().contains("vendor:bar"), "types() 里应当能看见扩展类型");
		assertTrue(MmtrFaceSchema.knownKeys("vendor:bar").contains("value"), "扩展声明的键没进拼写守卫的白名单");
		assertTrue(paintedSomething("vendor:bar"), "vendor:bar 没有画出红色像素");

		// ② 算子
		assertEquals(25.0, MmtrFaceLogic.asNumber(MmtrFaceLogic.evalJson("{\"vendor:percent\": [50, 200]}", Map.of())), 1.0E-9);
		assertEquals(null, MmtrFaceLogic.evalJson("{\"vendor:percent\": [50, 0]}", Map.of()), "除以 0 ⇒ null（与内置算术同一条口径）");
		assertTrue(MmtrFaceLogic.allOperators().contains("vendor:percent"), "allOperators() 应当能看见扩展算子");
		assertFalse(MmtrFaceLogic.operators().contains("vendor:percent"), "随包发行的算子清单（32 个）里不该出现扩展 —— 工具一致性用例钉着那一份");

		// ③ 过滤器（顺带钉住"扩展名自带冒号 ⇒ 参数要用最长匹配解析"）
		assertEquals("12 km/h", MmtrFaceText.resolve("{v|vendor:kmh}", Map.of("v", 12.4)));
		assertEquals("12 mph", MmtrFaceText.resolve("{v|vendor:kmh:mph}", Map.of("v", 12.4)), "参数是 kmh 之后那一段，不是第一个冒号之后那一段");
		assertTrue(MmtrFaceText.allFilters().contains("vendor:kmh"));
		assertFalse(MmtrFaceText.filters().contains("vendor:kmh"), "随包发行的过滤器清单里不该出现扩展（工具一致性用例钉着那 8 个）");

		// ④ 字段：从快照算，缺料就不放进去
		assertEquals("牵引", fieldValue(2500d));
		assertEquals("惰行", fieldValue(0d));
		assertEquals("制动", fieldValue(-5000d));
		assertEquals(null, MmtrFaceData.of(field -> null).get("vendor:traction"), "取不到电机出力 ⇒ 这个字段没有（不是空串、不是 0）");
		assertTrue(MmtrFaceField.Registry.size() >= 1);
	}

	/** 命名规矩：没有命名空间的注册一律**拒绝**（并记一条账），内置那一套不受影响。 */
	@Test
	public void anUnnamespacedRegistrationIsRejected() {
		MmtrFaceExtensions.register(registrar -> {
			registrar.element("bar", (canvas, document, element, paint) -> {
			});
			registrar.function("percent", (arguments, data) -> (double) arguments.size());
			registrar.filter("kmh", (value, parameter) -> value);
			registrar.field(new MmtrFaceField() {

				@Override
				public String name() {
					return "traction";
				}

				@Override
				public MmtrFaceFields.Type type() {
					return MmtrFaceFields.Type.STR;
				}

				@Override
				public Object value(Map<String, Object> values) {
					return "x";
				}
			});
			// 带点也不行：那会插进内置字段的子树里
			registrar.field(new MmtrFaceField() {

				@Override
				public String name() {
					return "vendor:traction.deep";
				}

				@Override
				public MmtrFaceFields.Type type() {
					return MmtrFaceFields.Type.STR;
				}

				@Override
				public Object value(Map<String, Object> values) {
					return "x";
				}
			});
		});

		assertFalse(MmtrFaceElements.knows("bar"), "没命名空间的元素类型必须被拒绝");
		assertFalse(MmtrFaceLogic.allOperators().contains("percent"), "没命名空间的算子必须被拒绝");
		assertFalse(MmtrFaceText.allFilters().contains("kmh"), "没命名空间的过滤器必须被拒绝");
		assertEquals(0, MmtrFaceField.Registry.size(), "没命名空间 / 带点的字段必须被拒绝");
		final List<String> warnings = MmtrFaceWarnings.drain();
		assertTrue(warnings.size() >= 4, "四条拒绝都该记一条账，实际：" + warnings);
		assertTrue(warnings.stream().anyMatch(message -> message.contains("没有命名空间")), "账里应当说清为什么被拒：" + warnings);
	}

	// ---- 工具 -------------------------------------------------------------------------------------

	/** 把 {@code sandbox/face-addon} 编译到临时目录，返回一个能加载它的类加载器。 */
	private static ClassLoader compileAddon() {
		if (!Files.isDirectory(ADDON_SOURCE)) {
			fail("找不到参考扩展的源码目录：" + ADDON_SOURCE.toAbsolutePath());
		}
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			fail("这个 JVM 没有 Java 编译器（要用 JDK 跑用例，不能用 JRE）");
		}
		try {
			final Path output = ADDON_OUTPUT;
			deleteRecursively(output);
			Files.createDirectories(output);
			final List<String> sources = new ArrayList<>();
			try (final var stream = Files.walk(ADDON_SOURCE)) {
				stream.filter(path -> path.toString().endsWith(".java")).forEach(path -> sources.add(path.toString()));
			}
			assertFalse(sources.isEmpty(), "扩展源码目录里没有 .java");

			final List<String> arguments = new ArrayList<>(List.of("-proc:none", "-nowarn", "-encoding", "UTF-8",
				"-d", output.toString(), "-cp", System.getProperty("java.class.path")));
			arguments.addAll(sources);
			final java.io.ByteArrayOutputStream diagnostics = new java.io.ByteArrayOutputStream();
			final int exit = compiler.run(null, null, diagnostics, arguments.toArray(new String[0]));
			assertEquals(0, exit, "编译参考扩展失败（javac 退出码 " + exit + "）：" + diagnostics.toString(StandardCharsets.UTF_8));

			// 服务声明也要进输出目录：ServiceLoader 就是按它找实现的
			final Path services = ADDON_SOURCE.resolve(Path.of("META-INF", "services", MmtrFaceExtension.class.getName()));
			assertTrue(Files.isRegularFile(services), "参考扩展少了 META-INF/services 声明：" + services);
			final Path targetServices = output.resolve(Path.of("META-INF", "services", MmtrFaceExtension.class.getName()));
			Files.createDirectories(targetServices.getParent());
			Files.writeString(targetServices, Files.readString(services, StandardCharsets.UTF_8), StandardCharsets.UTF_8);

			return new java.net.URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, MmtrFaceExtensionTests.class.getClassLoader());
		} catch (Exception e) {
			fail("准备参考扩展时出错：" + e);
			return null;
		}
	}

	/** 用这个元素类型画一块面，再看 PNG 里有没有按预期出现红色像素。 */
	private static boolean paintedSomething(String type) {
		final String anchors = "{\"faces\":{\"f\":{\"background\":0,\"elements\":[{\"type\":\"" + type
			+ "\",\"x\":0.1,\"y\":0.1,\"w\":0.8,\"h\":0.2,\"color\":\"#FFFF0000\",\"value\":0.5}]}}}";
		final MmtrFaceDocument document = MmtrFaceDocument.fromAnchors("addon", "f", anchors);
		assertNotNull(document, "这块（用扩展元素的）面解析不出来");
		final MmtrPanelCanvas canvas = MmtrPanelCanvas.create(1, 1, 64);
		MmtrFaceElements.paint(canvas, document, Map.of(), 0);
		try {
			final File png = Path.of("..", "..", "..", "sandbox", "face-addon", "out-bar.png").toFile();
			png.getParentFile().mkdirs();
			assertTrue(canvas.writePng(png), "出图失败");
			final BufferedImage image = ImageIO.read(png);
			assertNotNull(image, "读不回来 PNG");
			// 画布 y 朝上 ⇒ 米制 y=0.1..0.3 对应从顶部数第 45..58 行
			final int filled = image.getRGB(20, image.getHeight() - 12);
			final int empty = image.getRGB(45, image.getHeight() - 12);
			final boolean filledIsRed = ((filled >> 16) & 0xFF) > 200 && ((filled >> 8) & 0xFF) < 60;
			final boolean emptyIsNotRed = !(((empty >> 16) & 0xFF) > 200 && ((empty >> 8) & 0xFF) < 60);
			if (!filledIsRed) {
				fail("value=0.5 ⇒ 横条该画到一半（x=0.5 之前是红的），实际像素 " + Integer.toHexString(filled));
			}
			assertTrue(emptyIsNotRed, "value=0.5 ⇒ 横条不该画过一半，实际像素 " + Integer.toHexString(empty));
			return true;
		} catch (Exception e) {
			fail("画/读扩展元素的图时出错：" + e);
			return false;
		} finally {
			canvas.dispose();
		}
	}

	/** 递归删一个目录（重跑用例时不留旧的 class）。 */
	private static void deleteRecursively(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (final var stream = Files.walk(root)) {
			stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.delete(path);
				} catch (Exception ignored) {
					// 删不掉就算了：下面的 createDirectories 会给出更清楚的错
				}
			});
		} catch (Exception e) {
			// 同上
		}
	}

	/** 用给定的电机出力算一次扩展字段。 */
	private static Object fieldValue(double forceN) {
		final Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("motor.forceN", forceN);
		return MmtrFaceData.of(raw::get).get("vendor:traction");
	}
}
