package org.mtr.core.mmtr.manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.tool.Utilities;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rolling-stock manifest ("车辆生成表"): the operator-declared plan of which depot siding should
 * carry which train on every server restart. The server runs an explicit vehicle reset at startup
 * (all generated trains cleared) and then spawns exactly what this table declares. The table only
 * fixes the model/consist per siding; every generated train still gets its own world-unique
 * vehicle id and can be operated by id afterwards (delete / task assignment).
 * File: {@code <root>/<dimension>/mmtr-rolling-stock.json}.
 */
public final class MmtrRollingStockManifest implements SerializedDataBase {

	public final ObjectArrayList<MmtrManifestDepot> depots = new ObjectArrayList<>();

	public static MmtrRollingStockManifest parse(String json) {
		final JsonElement root = JsonParser.parseString(json);
		final MmtrRollingStockManifest manifest = new MmtrRollingStockManifest();
		if (root.isJsonObject()) {
			new JsonReader(root).iterateReaderArray("depots", manifest.depots::clear, reader -> manifest.depots.add(new MmtrManifestDepot(reader)));
		}
		return manifest;
	}

	public static MmtrRollingStockManifest fromFile(Path path) {
		try {
			return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to load mmtr rolling-stock manifest from " + path, e);
		}
	}

	public void save(Path path) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.write(path, Utilities.getJsonObjectFromData(this).toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to save mmtr rolling-stock manifest to " + path, e);
		}
	}

	public boolean isEmpty() {
		return depots.isEmpty();
	}

	public int sidingEntryCount() {
		int count = 0;
		for (final MmtrManifestDepot depot : depots) {
			count += depot.sidings.size();
		}
		return count;
	}

	/**
	 * 列车表 → **指令表**：每条"某股道配某编组"翻译成一条 {@code vehicle spawn} 指令。
	 *
	 * <p>这是"初始生成内容 = 硬编码的指令表"这个约定的落地方式：表里的每一行都能被
	 * {@code MmtrCommandDispatcher} 直接执行，所以启动播种与管理员手工操作走的是**同一个执行器**，
	 * 也就不再存在"读文件的应用逻辑"和"运行时操作逻辑"两套实现。</p>
	 *
	 * <p>写指令时用 depot / siding 的 id（十进制 64 位）：名称会变、可能重复，id 不会。</p>
	 */
	public java.util.List<String> asCommandLines() {
		final java.util.ArrayList<String> lines = new java.util.ArrayList<>();
		for (final MmtrManifestDepot depot : depots) {
			for (final MmtrManifestSiding siding : depot.sidings) {
				if (siding.cars.isEmpty()) {
					continue;
				}
				final java.util.ArrayList<String> cars = new java.util.ArrayList<>();
				for (final MmtrCarSpec car : siding.cars) {
					cars.add(car.vehicleId);
				}
				lines.add("vehicle spawn " + String.join(" ", cars)
					+ " --depot=" + depot.depotId
					+ " --siding=" + siding.sidingId
					+ perCarOptions(siding.cars));
			}
		}
		return lines;
	}

	/**
	 * **逐车车卡 → 指令片段**（notes/271 片 1）。
	 *
	 * <p>这是"清单 → 指令表"那条路上原来漏掉的一环：清单一向能存 {@code powered} /
	 * {@code consistTypeId} / 车钩声明（{@link MmtrCarSpec} 是全字段的），但翻译成指令时**只拼了车型**，
	 * 于是世界文件里写了 {@code powered:false} 也到不了车 —— 现场只能手改世界 json（notes/247 §2.4）。
	 * 结果就是"挂车"在数据上一直是动力车。</p>
	 *
	 * <p>缺省值**不写**，所以老清单拼出来的指令逐字不变：{@code powered} 只有**显式声明过**才写
	 * （三态必须保住 —— 没写和写了 false 不是一回事）、{@code consistTypeId} 非空才写、
	 * {@code loadRatio} 非零才写、两个车钩开关只在偏离缺省时写。</p>
	 */
	public static String perCarOptions(java.util.List<MmtrCarSpec> cars) {
		final StringBuilder powered = new StringBuilder();
		final StringBuilder consistType = new StringBuilder();
		final StringBuilder load = new StringBuilder();
		final StringBuilder couplerAfter = new StringBuilder();
		final StringBuilder manualCoupler = new StringBuilder();
		boolean anyPowered = false;
		boolean anyConsistType = false;
		boolean anyLoad = false;
		boolean anyCouplerAfter = false;
		boolean anyManualCoupler = false;
		for (final MmtrCarSpec car : cars) {
			appendListItem(powered, car.poweredDeclared == null ? "" : String.valueOf(car.powered));
			anyPowered |= car.poweredDeclared != null;
			appendListItem(consistType, car.consistTypeId == null ? "" : car.consistTypeId);
			anyConsistType |= car.consistTypeId != null && !car.consistTypeId.isEmpty();
			appendListItem(load, car.loadRatio > 0 ? String.valueOf(car.loadRatio) : "");
			anyLoad |= car.loadRatio > 0;
			appendListItem(couplerAfter, car.mmtrCouplerAfter ? "true" : "");
			anyCouplerAfter |= car.mmtrCouplerAfter;
			appendListItem(manualCoupler, car.mmtrAutoCoupler ? "" : "true");
			anyManualCoupler |= !car.mmtrAutoCoupler;
		}
		final StringBuilder out = new StringBuilder();
		if (anyPowered) {
			out.append(" --powered=").append(powered);
		}
		if (anyConsistType) {
			out.append(" --consist-type=").append(consistType);
		}
		if (anyLoad) {
			out.append(" --load=").append(load);
		}
		if (anyCouplerAfter) {
			out.append(" --coupler-after=").append(couplerAfter);
		}
		if (anyManualCoupler) {
			out.append(" --manual-coupler=").append(manualCoupler);
		}
		return out.toString();
	}

	private static void appendListItem(StringBuilder builder, String item) {
		if (builder.length() > 0) {
			builder.append(',');
		}
		builder.append(item);
	}

	/**
	 * 给一条股道写入编组（**逐车车卡原样写入**）；已存在则替换。
	 *
	 * <p>notes/271 片 1：原来这里把 {@code spec.powered} 写死成 {@code true} —— 于是"清单里声明一列
	 * 无动力挂车"这条路在**写盘那一步**就断了（存储侧本来是全字段的）。现在原样写入：缺省留
	 * {@code poweredDeclared = null}（= 这个维度没说），与手写清单的行为一致。</p>
	 */
	public void putSiding(long depotId, String depotName, long sidingId, String sidingName, java.util.List<MmtrCarSpec> cars) {
		MmtrManifestDepot target = null;
		for (final MmtrManifestDepot depot : depots) {
			if (depot.depotId == depotId) {
				target = depot;
				break;
			}
		}
		if (target == null) {
			target = new MmtrManifestDepot();
			target.depotId = depotId;
			target.name = depotName == null ? "" : depotName;
			depots.add(target);
		}
		MmtrManifestSiding targetSiding = null;
		for (final MmtrManifestSiding siding : target.sidings) {
			if (siding.sidingId == sidingId) {
				targetSiding = siding;
				break;
			}
		}
		if (targetSiding == null) {
			targetSiding = new MmtrManifestSiding();
			targetSiding.sidingId = sidingId;
			targetSiding.name = sidingName == null ? "" : sidingName;
			target.sidings.add(targetSiding);
		}
		targetSiding.cars.clear();
		targetSiding.cars.addAll(cars);
	}

	/** 老签名：只给车型，几何按 {@code carLength} 均分；车卡按"没说动力 / 没说车底 / 零载重"写。 */
	public void putSiding(long depotId, String depotName, long sidingId, String sidingName, java.util.List<String> carIds, double carLength) {
		final java.util.ArrayList<MmtrCarSpec> specs = new java.util.ArrayList<>();
		for (final String carId : carIds) {
			// MmtrCarSpec 只有无参构造（它是序列化用的数据类），字段直接赋值。
			final MmtrCarSpec spec = new MmtrCarSpec();
			spec.vehicleId = carId;
			spec.length = carLength;
			spec.width = 5;
			spec.bogie1Position = -carLength / 3;
			spec.bogie2Position = carLength / 3;
			// 不再写死 powered = true：三态里"没说"才是缺省（片 1）。
			specs.add(spec);
		}
		putSiding(depotId, depotName, sidingId, sidingName, specs);
	}

	/**
	 * 从列车表里删条目。
	 *
	 * @param sidingId 0 表示删掉整个车辆段
	 * @return 是否删掉了东西
	 */
	public boolean remove(long depotId, long sidingId) {
		for (int i = depots.size() - 1; i >= 0; i--) {
			final MmtrManifestDepot depot = depots.get(i);
			if (depot.depotId != depotId) {
				continue;
			}
			if (sidingId == 0) {
				depots.remove(i);
				return true;
			}
			for (int j = depot.sidings.size() - 1; j >= 0; j--) {
				if (depot.sidings.get(j).sidingId == sidingId) {
					depot.sidings.remove(j);
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		readerBase.iterateReaderArray("depots", depots::clear, reader -> depots.add(new MmtrManifestDepot(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeDataset(depots, "depots");
	}
}
