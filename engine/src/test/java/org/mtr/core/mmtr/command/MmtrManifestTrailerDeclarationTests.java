package org.mtr.core.mmtr.command;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.mmtr.manifest.MmtrRollingStockManifest;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * notes/271 片 1：**"在世界文件里声明一列挂车"这条路必须真的通**。
 *
 * <p>修前：清单能存 {@code powered:false} / {@code consistTypeId}（{@link MmtrCarSpec} 是全字段的），
 * 但 {@link MmtrRollingStockManifest#asCommandLines()} 只拼车型、{@code putSiding()} 把
 * {@code powered} 写死成 {@code true} —— 于是世界文件里写了也没用，"挂车"在数据上一直是动力车
 * （notes/247 §2.4 只能靠手改世界 json 兜）。</p>
 *
 * <p>这条用例钉三件事：</p>
 * <ol>
 *   <li>声明的逐车字段出现在指令行里（逗号列表，与位置对齐）；</li>
 *   <li>那行指令按**执行器自己的规则**能建出逐车字段正确的车卡 —— 包括三态（显式无动力）；</li>
 *   <li>老清单（没写新字段）拼出来的指令**逐字不变**，且车卡仍是"没表态"。</li>
 * </ol>
 */
public final class MmtrManifestTrailerDeclarationTests {

	@Test
	public void aTrailerRakeDeclaredInTheManifestReachesTheCommandLineAndTheCars() {
		final String json = manifest(car("p1", "false", "p1_trailer", "0.8", "true")
			+ "," + car("p1", "false", "p1_trailer", "0.8", "false"));

		final List<String> lines = MmtrRollingStockManifest.parse(json).asCommandLines();
		assertEquals(1, lines.size(), "一条股道一行");
		final String line = lines.get(0);
		assertTrue(line.contains("--powered=false,false"), line);
		assertTrue(line.contains("--consist-type=p1_trailer,p1_trailer"), line);
		assertTrue(line.contains("--load=0.8,0.8"), line);
		assertTrue(line.contains("--coupler-after=true,"), line);

		final List<VehicleCar> cars = carsOf(line, 2);
		for (final VehicleCar car : cars) {
			assertFalse(car.getMmtrPowered(), "挂车不提供牵引");
			assertTrue(car.isMmtrPoweredDeclared(), "清单里写了 powered:false ⇒ 这是一次显式声明");
			assertEquals("p1_trailer", car.getMmtrConsistTypeId());
			assertEquals(0.8, car.getMmtrLoadRatio(), 1e-9);
		}
		assertTrue(cars.get(0).getMmtrCouplerAfter(), "第一节之后有车钩（能在那儿解挂）");
		assertFalse(cars.get(1).getMmtrCouplerAfter());
	}

	/**
	 * 三态：{@code powered} **没写** 与 **写了 false** 必须能被区分 —— 前者是"作者没表态"（沿用
	 * "借车底、最多一节借牵引"的老兜底），后者是"这列车永远没有牵引"。
	 */
	@Test
	public void theTriStateDistinguishesUnspecifiedFromExplicitlyUnpowered() {
		// 没写 powered / consistTypeId / loadRatio
		final List<String> undeclaredLines = MmtrRollingStockManifest.parse(manifest(car("p1", null, null, null, null))).asCommandLines();
		assertFalse(undeclaredLines.get(0).contains("--powered"), undeclaredLines.get(0));
		assertFalse(undeclaredLines.get(0).contains("--consist-type"), undeclaredLines.get(0));
		final VehicleCar undeclared = carsOf(undeclaredLines.get(0), 1).get(0);
		assertTrue(undeclared.getMmtrPowered(), "没表态时的缺省仍是动力（老行为）");
		assertFalse(undeclared.isMmtrPoweredDeclared(), "没表态 ≠ 声明");
		assertEquals("", undeclared.getMmtrConsistTypeId());
		assertEquals(0, undeclared.getMmtrLoadRatio(), 1e-9);

		// 显式写了 false
		final List<String> declaredLines = MmtrRollingStockManifest.parse(manifest(car("p1", "false", null, null, null))).asCommandLines();
		assertTrue(declaredLines.get(0).contains("--powered=false"), declaredLines.get(0));
		final VehicleCar declared = carsOf(declaredLines.get(0), 1).get(0);
		assertFalse(declared.getMmtrPowered());
		assertTrue(declared.isMmtrPoweredDeclared(), "写了 powered:false ⇒ 显式声明（片 2 起永不给牵引）");
	}

	/** 老清单（只有 vehicleId/length）拼出来的指令必须逐字保持旧格式，否则"零回归"就成了口号。 */
	@Test
	public void aManifestWithoutTheNewFieldsKeepsTheOldCommandLine() {
		final String json = "{\"depots\":[{\"depotId\":\"7\",\"sidings\":[{\"sidingId\":\"9\",\"cars\":["
			+ "{\"vehicleId\":\"saf101\",\"length\":16}]}]}]}";
		assertEquals("vehicle spawn saf101 --depot=7 --siding=9",
			MmtrRollingStockManifest.parse(json).asCommandLines().get(0));
	}

	/** 同一份三态与载重要一路走到**运行时车卡**上（否则出了作者层就没了）。 */
	@Test
	public void theTriStateAndLoadRatioReachTheRuntimeCar() {
		final MmtrCarSpec spec = new MmtrCarSpec(new JsonReader(JsonParser.parseString(
			"{\"vehicleId\":\"p1\",\"length\":16,\"powered\":false,\"consistTypeId\":\"p1_trailer\",\"loadRatio\":0.75}")));
		final VehicleCar car = spec.toVehicleCar();
		assertFalse(car.getMmtrPowered());
		assertTrue(car.isMmtrPoweredDeclared());
		assertEquals("p1_trailer", car.getMmtrConsistTypeId());
		assertEquals(0.75, car.getMmtrLoadRatio(), 1e-9);

		// 写盘再读回来：键一定在（片 1 的口径 —— 落盘后一律视为已声明，设计文档 §4 片 1 已记明）
		assertTrue(new VehicleCar(new JsonReader(Utilities.getJsonObjectFromData(car))).isMmtrPoweredDeclared());

		// 从来没写过这个键的车卡 = 没表态
		final VehicleCar untouched = new VehicleCar(new JsonReader(JsonParser.parseString("{\"vehicleId\":\"p1\",\"length\":16}")));
		assertFalse(untouched.isMmtrPoweredDeclared());
		assertTrue(untouched.getMmtrPowered());
	}

	// ---------------------------------------------------------------- 夹具

	private static String manifest(String cars) {
		return "{\"depots\":[{\"depotId\":\"7\",\"sidings\":[{\"sidingId\":\"9\",\"cars\":[" + cars + "]}]}]}";
	}

	/** 一辆车卡；{@code null} 的字段**不写进 json**（这才叫"没表态"）。 */
	private static String car(String vehicleId, String powered, String consistTypeId, String loadRatio, String couplerAfter) {
		final StringBuilder builder = new StringBuilder("{\"vehicleId\":\"").append(vehicleId).append("\",\"length\":16");
		if (powered != null) {
			builder.append(",\"powered\":").append(powered);
		}
		if (consistTypeId != null) {
			builder.append(",\"consistTypeId\":\"").append(consistTypeId).append('"');
		}
		if (loadRatio != null) {
			builder.append(",\"loadRatio\":").append(loadRatio);
		}
		if (couplerAfter != null) {
			builder.append(",\"mmtrCouplerAfter\":").append(couplerAfter);
		}
		return builder.append('}').toString();
	}

	/** 走**执行器自己的**那条路：切词 → 取选项 → {@link MmtrVehicleCommands#carsFor} 建车卡。 */
	private static List<VehicleCar> carsOf(String line, int expectedCars) {
		final List<String> words = MmtrCommandDispatcher.tokenize(line);
		final Map<String, String> options = new HashMap<>();
		final List<String> ids = new ArrayList<>();
		for (int i = 2; i < words.size(); i++) {
			final String word = words.get(i);
			if (word.startsWith("--")) {
				final int equals = word.indexOf('=');
				if (equals < 0) {
					options.put(word.substring(2).toLowerCase(Locale.ENGLISH), "true");
				} else {
					options.put(word.substring(2, equals).toLowerCase(Locale.ENGLISH), word.substring(equals + 1));
				}
			} else {
				ids.add(word);
			}
		}
		assertEquals(expectedCars, ids.size(), "车型个数：" + line);
		return new ArrayList<>(MmtrVehicleCommands.carsFor(ids, options, 16));
	}
}
