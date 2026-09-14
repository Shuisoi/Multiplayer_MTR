package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P1 输入层的根：**持久化的就是它**（{@code 任务系统-线路派生与车底交路-设计.md} §2.1 ①）。
 *
 * <p>与作业单那条路的方向正好相反：{@code mmtr-jobs.json} 持久化的是**任务本身**（手工作业单），
 * 而这里持久化的是**输入**（线路 / 密度 / 车底）—— 计划永远是算出来的，所以"任务不会死存在某个
 * 文件里"（设计 §1.2）。文件：{@code <存档>/<维度>/mmtr-plan.json}：</p>
 *
 * <pre>
 * { "lines":   [ { "lineId": "L1", "stops": [ ... ], ... } ],
 *   "patterns":[ { "lineId": "L1", "segments": [ ... ] } ],
 *   "fleet":   { "consists": [ ... ], "spares": [ ... ], "vehicleCount": 8 } }
 * </pre>
 *
 * <p>事件规则与货运队列是这一层的另外两块（设计 §4.1 的输入清单），分别在 P5 / P7 落地；
 * 本类预留了位置但**不假装已经有**（它们没有字段就不会被写出来）。</p>
 */
public final class MmtrPlanInputs implements SerializedDataBase {

	public final ObjectArrayList<MmtrLine> lines = new ObjectArrayList<>();
	public final ObjectArrayList<MmtrPattern> patterns = new ObjectArrayList<>();
	public MmtrFleet fleet = new MmtrFleet();

	public MmtrPlanInputs() {
	}

	public MmtrPlanInputs(ReaderBase readerBase) {
		updateData(readerBase);
	}

	public static MmtrPlanInputs parse(String json) {
		final MmtrPlanInputs inputs = new MmtrPlanInputs();
		inputs.updateData(org.mtr.core.serializer.JsonReader.parse(json));
		return inputs;
	}

	public static MmtrPlanInputs fromFile(Path path) {
		try {
			return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to load mmtr plan inputs from " + path, e);
		}
	}

	public void save(Path path) {
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.write(path, org.mtr.core.tool.Utilities.getJsonObjectFromData(this).toString().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to save mmtr plan inputs to " + path, e);
		}
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		readerBase.iterateReaderArray("lines", lines::clear, reader -> lines.add(new MmtrLine(reader)));
		readerBase.iterateReaderArray("patterns", patterns::clear, reader -> patterns.add(new MmtrPattern(reader)));
		fleet = new MmtrFleet();
		readerBase.unpackChild("fleet", reader -> fleet.updateData(reader));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeDataset(lines, "lines");
		writerBase.writeDataset(patterns, "patterns");
		fleet.serializeData(writerBase.writeChild("fleet"));
	}

	/** 新增或替换一条线路（同 lineId）。 */
	public void putLine(MmtrLine line) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).lineId.equals(line.lineId)) {
				lines.set(i, line);
				return;
			}
		}
		lines.add(line);
	}

	public boolean removeLine(String lineId) {
		final boolean removed = lines.removeIf(line -> line.lineId.equals(lineId));
		patterns.removeIf(pattern -> pattern.lineId.equals(lineId));
		return removed;
	}

	/** 新增或替换一条线路的密度表（同 lineId）。 */
	public void putPattern(MmtrPattern pattern) {
		for (int i = 0; i < patterns.size(); i++) {
			if (patterns.get(i).lineId.equals(pattern.lineId)) {
				patterns.set(i, pattern);
				return;
			}
		}
		patterns.add(pattern);
	}

	public @Nullable MmtrLine line(String lineId) {
		for (final MmtrLine line : lines) {
			if (line.lineId.equals(lineId)) {
				return line;
			}
		}
		return null;
	}

	public @Nullable MmtrPattern pattern(String lineId) {
		for (final MmtrPattern pattern : patterns) {
			if (pattern.lineId.equals(lineId)) {
				return pattern;
			}
		}
		return null;
	}

	/**
	 * **加载期校验**；返回全部问题（空的 = 通过）。
	 *
	 * <p>三层：① 每条线路 / 每张密度表自己的结构（{@link MmtrLine#validate()} /
	 * {@link MmtrPattern#validate()} / {@link MmtrFleet#validate()}）；
	 * ② **交叉引用**（线路上有密度表、密度表指向存在的线路、线路有出库股道）；
	 * ③ "够不够跑"由 {@link #validateCapacity} 单独做 —— 它需要**周转时间**，
	 * 而周转要从轨图算（P2/P3），输入层自己不猜走行时间。</p>
	 */
	public ObjectArrayList<String> validate() {
		final ObjectArrayList<String> errors = new ObjectArrayList<>();
		final java.util.HashSet<String> lineIds = new java.util.HashSet<>();
		for (final MmtrLine line : lines) {
			errors.addAll(line.validate());
			if (line.lineId != null && !line.lineId.isBlank() && !lineIds.add(line.lineId)) {
				errors.add("线路 id 重复：" + line.lineId);
			}
			if (line.yardSidingId == 0) {
				errors.add("线路 " + line.lineId + " 没有指定出库股道（yardSidingId=0）—— 交路的第一段（出库）没法生成");
			}
		}
		final java.util.HashSet<String> patternLineIds = new java.util.HashSet<>();
		for (final MmtrPattern pattern : patterns) {
			errors.addAll(pattern.validate());
			if (pattern.lineId != null && !pattern.lineId.isBlank() && !patternLineIds.add(pattern.lineId)) {
				errors.add("密度表重复：线路 " + pattern.lineId + " 有两张密度表");
			}
			if (line(pattern.lineId) == null) {
				errors.add("密度表指向不存在的线路：" + pattern.lineId);
			}
		}
		for (final MmtrLine line : lines) {
			if (pattern(line.lineId) == null) {
				errors.add("线路 " + line.lineId + " 没有密度表（不知道该多久发一趟）");
			}
		}
		errors.addAll(fleet.validate());
		return errors;
	}

	/**
	 * **够不够跑**（P1 验收 ③）：按某条线路的运营需求核对车底数。
	 *
	 * @param lineId     线路
	 * @param ringMillis 该线路的周转时间（往程走行 + 停站 + 终点处理 + 返程走行 + 停站）
	 * @return null = 够；否则一句可读原因（直接进加载报错）
	 */
	public @Nullable String validateCapacity(String lineId, long ringMillis) {
		final MmtrPattern pattern = pattern(lineId);
		if (pattern == null) {
			return "线路 " + lineId + " 没有密度表，算不出需要几个车底";
		}
		return fleet.validateCapacity(ringMillis, pattern.peakHeadwayMillis());
	}

	/** 计划输入的一句话摘要（日志/接口都用它，避免各处自己拼）。 */
	public String describe() {
		return "计划输入：线路 " + lines.size() + " 条 / 密度表 " + patterns.size() + " 张 / " + fleet;
	}

	/**
	 * **一条都没配**（没有线路、没有密度表、没有编组）。
	 *
	 * <p>与"配了但有错"必须分开：一台还没用时刻表的服务器不该在启动日志里报一堆配置错误 ——
	 * 那是**假警报**，会让真错误淹没在噪音里（本轮现场验证时接口在空配置下真的报了"车底为空"，
	 * 于是加了这一条）。</p>
	 */
	public boolean isEmpty() {
		return lines.isEmpty() && patterns.isEmpty() && fleet.consists.isEmpty() && fleet.spares.isEmpty();
	}
}
