package org.mtr.core.operation;

import org.mtr.core.generated.operation.VehiclePatchSchema;
import org.mtr.core.serializer.ReaderBase;

/**
 * 一辆车的**稀疏补丁**（notes/174）：{@link VehicleSyncPatch} 产出的那段 JSON，
 * 形状与整份快照一致（{@code {"vehicle":…,"data":…}}），客户端直接原地合并。
 *
 * <p>为什么是"字符串套 JSON"：patch 是**任意子集**的字段，schema 生成器写的是"每个字段都写"，
 * 表达不了"只写变了的那几个"。这一层只负责把那段 JSON 原样搬过去；真正的紧凑在于
 * **它只含变化的叶子**（实测典型 40–120 B，而整份快照 3.5 KB）。</p>
 */
public final class VehiclePatch extends VehiclePatchSchema {

	public VehiclePatch(long vehicleId, String patch) {
		super(vehicleId, patch);
	}

	public VehiclePatch(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
	}

	public long getVehicleId() {
		return vehicleId;
	}

	public String getPatch() {
		return patch;
	}
}
