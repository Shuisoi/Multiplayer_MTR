package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.mmtr.job.MmtrCarSpec;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;

/**
 * P1 输入层：**车底**（{@code 任务系统-线路派生与车底交路-设计.md} §4.3）。
 *
 * <p>车底是**输入**而不是结果："车辆数固定，要利用好"（用户口径）—— 所以这里写死有几个编组、
 * 哪些编组参与套班（{@link #consists}）、哪些专供顶替（{@link #spares}），再由
 * {@link #requiredConsists} 把"运营到底需要几个"算出来比对（P1 验收 ③）：
 * <b>不够就在加载时报出来，而不是到了高峰才发现跑不动</b>。</p>
 *
 * <p>编组内容直接复用作业单那条路的 {@link MmtrCarSpec} —— 车底在一处写、三处用
 * （作业单 / 清册 / 交路），不再造一套平行的车型描述。</p>
 */
public final class MmtrFleet implements SerializedDataBase {

	/** 一条交路的车底：编组代码 + 车列 + （可选）本编组的最高速度。 */
	public static final class ConsistSpec implements SerializedDataBase {
		public String consistId = "";
		/** 本编组允许的最高速度（km/h）；{@code <= 0} = 由 ConsistType 决定，不在这里限制。 */
		public double maxSpeedKmh;
		public final ObjectArrayList<MmtrCarSpec> cars = new ObjectArrayList<>();

		public ConsistSpec() {
		}

		public ConsistSpec(String consistId, double maxSpeedKmh) {
			this.consistId = consistId;
			this.maxSpeedKmh = maxSpeedKmh;
		}

		public ConsistSpec(ReaderBase readerBase) {
			updateData(readerBase);
		}

		public ConsistSpec addCar(MmtrCarSpec car) {
			cars.add(car);
			return this;
		}

		@Override
		public void updateData(ReaderBase readerBase) {
			consistId = readerBase.getString("consistId", "");
			maxSpeedKmh = readerBase.getDouble("maxSpeedKmh", 0);
			readerBase.iterateReaderArray("cars", cars::clear, reader -> cars.add(new MmtrCarSpec(reader)));
		}

		@Override
		public void serializeData(WriterBase writerBase) {
			writerBase.writeString("consistId", consistId);
			if (maxSpeedKmh > 0) {
				writerBase.writeDouble("maxSpeedKmh", maxSpeedKmh);
			}
			writerBase.writeDataset(cars, "cars");
		}

		@Override
		public String toString() {
			return consistId + "(" + cars.size() + " 节" + (maxSpeedKmh > 0 ? "，" + Math.round(maxSpeedKmh) + "km/h" : "") + ")";
		}
	}

	/** 参与套班的编组（就该有 {@link #requiredConsists} 那么多）。 */
	public final ObjectArrayList<ConsistSpec> consists = new ObjectArrayList<>();
	/** **替补集合**：不排班，专供事件与晚点顶替（设计 §8.1）。 */
	public final ObjectArrayList<ConsistSpec> spares = new ObjectArrayList<>();
	/**
	 * 声明的车辆段/车场总车辆数（0 = 不声明）。
	 *
	 * <p>它是**输入**：用户口径"车辆数固定"。给了就会与 {@code consists + spares} 对账
	 * （数量对不上说明配置与打算不一致，属于加载期错误）；真正的"够不够跑"由
	 * {@link #requiredConsists} 判。</p>
	 */
	public long vehicleCount;

	public MmtrFleet() {
	}

	public MmtrFleet(ReaderBase readerBase) {
		updateData(readerBase);
	}

	public MmtrFleet addConsist(ConsistSpec consist) {
		consists.add(consist);
		return this;
	}

	public MmtrFleet addSpare(ConsistSpec spare) {
		spares.add(spare);
		return this;
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		vehicleCount = readerBase.getLong("vehicleCount", 0);
		readerBase.iterateReaderArray("consists", consists::clear, reader -> consists.add(new ConsistSpec(reader)));
		readerBase.iterateReaderArray("spares", spares::clear, reader -> spares.add(new ConsistSpec(reader)));
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeLong("vehicleCount", vehicleCount);
		writerBase.writeDataset(consists, "consists");
		writerBase.writeDataset(spares, "spares");
	}

	/**
	 * **运营所需车底数 N**（设计 §5.1 ②）：{@code N = ceil(ring / 高峰最小间隔)}，至少 1。
	 *
	 * @param ringMillis          周转时间：往程走行 + 沿途停站 + 终点处理 + 返程走行 + 沿途停站
	 * @param peakHeadwayMillis   高峰最小发车间隔（{@link MmtrPattern#peakHeadwayMillis()}）
	 */
	public static long requiredConsists(long ringMillis, long peakHeadwayMillis) {
		if (ringMillis <= 0 || peakHeadwayMillis <= 0) {
			return 1;
		}
		return Math.max(1, (ringMillis + peakHeadwayMillis - 1) / peakHeadwayMillis);
	}

	/**
	 * **够不够跑**（P1 验收 ③）——返回 null 表示够，否则给出一句能直接给操作者看的原因。
	 *
	 * @param ringMillis        周转时间（P2/P3 从轨图算；P1 的用例显式传，见类注释）
	 * @param peakHeadwayMillis 高峰最小间隔
	 */
	public String validateCapacity(long ringMillis, long peakHeadwayMillis) {
		final long required = requiredConsists(ringMillis, peakHeadwayMillis);
		final long available = consists.size();
		if (available < required) {
			return "车底不够跑：运营需要 N=" + required + " 个编组（周转 " + Math.round(ringMillis / 60000.0)
				+ " min / 高峰间隔 " + Math.round(peakHeadwayMillis / 60000.0) + " min），"
				+ "现在只配了 " + available + " 个（缺 " + (required - available) + " 个）"
				+ (spares.isEmpty() ? "" : "；替补 " + spares.size() + " 个不算在套班里");
		}
		return null;
	}

	/** 结构校验；返回全部问题（空的 = 通过）。 */
	public ObjectArrayList<String> validate() {
		final ObjectArrayList<String> errors = new ObjectArrayList<>();
		if (consists.isEmpty()) {
			errors.add("车底为空：至少要有一个参与套班的编组");
		}
		final java.util.HashSet<String> seen = new java.util.HashSet<>();
		for (final ConsistSpec consist : consists) {
			validateConsist(consist, "编组", seen, errors);
		}
		for (final ConsistSpec spare : spares) {
			validateConsist(spare, "替补", seen, errors);
		}
		if (vehicleCount > 0 && vehicleCount < consists.size() + spares.size()) {
			errors.add("声明的车辆数 " + vehicleCount + " 少于配置的编组数 " + (consists.size() + spares.size())
				+ "（" + consists.size() + " 套班 + " + spares.size() + " 替补）");
		}
		return errors;
	}

	private static void validateConsist(ConsistSpec consist, String role, java.util.HashSet<String> seen, ObjectArrayList<String> errors) {
		if (consist.consistId == null || consist.consistId.isBlank()) {
			errors.add(role + "缺少 consistId");
		} else if (!seen.add(consist.consistId)) {
			errors.add("编组代码重复：" + consist.consistId + "（套班与替补之间也不许重名）");
		}
		if (consist.cars.isEmpty()) {
			errors.add(role + " " + consist.consistId + " 没有车（cars 为空）");
		}
		if (consist.maxSpeedKmh < 0) {
			errors.add(role + " " + consist.consistId + " 的最高速度为负（" + consist.maxSpeedKmh + "）");
		}
	}

	/** 按编组代码查（套班/替补一起查）；找不到返回 null。 */
	public ConsistSpec consist(String consistId) {
		for (final ConsistSpec consist : consists) {
			if (consist.consistId.equals(consistId)) {
				return consist;
			}
		}
		for (final ConsistSpec spare : spares) {
			if (spare.consistId.equals(consistId)) {
				return spare;
			}
		}
		return null;
	}

	/** 是不是替补（顶替协议要按这个分派）。 */
	public boolean isSpare(String consistId) {
		for (final ConsistSpec spare : spares) {
			if (spare.consistId.equals(consistId)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString() {
		return "车底 " + consists.size() + " 套班 + " + spares.size() + " 替补"
			+ (vehicleCount > 0 ? "（声明车辆数 " + vehicleCount + "）" : "");
	}
}
