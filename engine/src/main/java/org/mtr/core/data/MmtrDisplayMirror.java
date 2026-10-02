package org.mtr.core.data;

/**
 * **② 通道里"没有别的标脏点"的那批显示读数的上一拍值**（notes/369 §8）。
 *
 * <h2>它解决什么</h2>
 * <p>车辆快照的"发不发"是由 {@code VehicleExtraData.checkForUpdate()} 与各处
 * {@code mmtrMarkSyncDirty()} 一起决定的。历史上这批字段是靠**别的**标脏顺手带出去的
 * （司机 HUD 的限速 / LZB / 闭塞扣车 / 停留理由 / 调车授权倒计时），于是
 * "车稳稳停着、什么都标脏不了"的那几秒里它们到不了客户端 —— 而恰恰是那几秒，
 * 司机盯着 HUD 想知道"为什么不动"。</p>
 *
 * <h2>为什么是"逐字段比较"而不是哈希/时间戳</h2>
 * <p>本仓的既有口径是**判据要能推出结论，而不是赌概率**（见 notes/174 的方向选择）：
 * 一个哈希漏掉一次变化，表现就是"HUD 上某个字停在旧值"，而这种静默错正是这个项目最恨的一类。
 * 十来个字段逐字段比一遍是几条指令，换掉的是"永远查不出来"的可能。</p>
 *
 * <p>只放**没有别的标脏点**的字段：手柄/门/旗标那些已经由它们各自的写入路径标脏
 * （notes/369 §8 有完整清单与理由），重复列在这里只会让"谁负责标脏"变模糊。</p>
 */
public record MmtrDisplayMirror(
	String holdReason,
	long speedLimitKmh,
	boolean lzbSupervising,
	long lzbCeilingKmh,
	long lzbTargetKmh,
	double lzbTargetDistanceM,
	boolean awsWarningPending,
	boolean awsWarningAcknowledged,
	boolean blockHeld,
	double shuntRemainingSeconds
) {

	/** 从这辆车当前的镜像字段取一份（{@code Vehicle#updateMmtrSyncFields()} 末尾调用）。 */
	static MmtrDisplayMirror of(Vehicle vehicle) {
		return new MmtrDisplayMirror(
			vehicle.getMmtrHoldReasonFromSync(),
			vehicle.getMmtrSpeedLimitKmhFromSync(),
			vehicle.isMmtrLzbSupervisingFromSync(),
			vehicle.getMmtrLzbCeilingKmhFromSync(),
			vehicle.getMmtrLzbTargetKmhFromSync(),
			vehicle.getMmtrLzbTargetDistanceMFromSync(),
			vehicle.isMmtrAwsWarningPendingFromSync(),
			vehicle.isMmtrAwsWarningAcknowledgedFromSync(),
			vehicle.isMmtrBlockHeldFromSync(),
			vehicle.getMmtrShuntRemainingSFromSync()
		);
	}
}
