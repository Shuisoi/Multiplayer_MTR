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
					+ " --siding=" + siding.sidingId);
			}
		}
		return lines;
	}

	/** 给一条股道写入编组（热改列车表用）；已存在则替换。 */
	public void putSiding(long depotId, String depotName, long sidingId, String sidingName, java.util.List<String> carIds, double carLength) {
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
		for (final String carId : carIds) {
			// MmtrCarSpec 只有无参构造（它是序列化用的数据类），字段直接赋值。
			final MmtrCarSpec spec = new MmtrCarSpec();
			spec.vehicleId = carId;
			spec.length = carLength;
			spec.width = 5;
			spec.bogie1Position = -carLength / 3;
			spec.bogie2Position = carLength / 3;
			spec.powered = true;
			targetSiding.cars.add(spec);
		}
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
