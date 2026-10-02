package org.mtr.mod.render.panel;

import org.mtr.mod.Init;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.mmtr.face.MmtrFaceSource;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link MmtrFaceSource} 的**客户端实现**：把面系统问的字段名，落到这台车（镜像）的读数上
 * （notes/359 §数据接口）。
 *
 * <p>这是整个系统里**唯一**一处"字段名 ↔ 游戏 getter"的映射，而且和字段登记表
 * {@code MmtrFaceFields} 由用例 {@code MmtrFaceFieldsTests} 双向核对：</p>
 * <ul>
 *   <li>登记表里每个 {@code raw} 字段，这里必须有一个 {@code case}；</li>
 *   <li>这里每个 {@code case}，登记表里必须有一行 —— 否则那个量作者根本写不出来（"死字段"）。</li>
 * </ul>
 * <p>所以"加一个字段"这件事不会漏步骤：漏了就红（四步曲见 {@code MmtrFaceFields} 的类注释）。</p>
 *
 * <h2>★ 只读镜像，不在客户端算权威数据</h2>
 * <p>这里所有 {@code *FromSync()} 取的都是**引擎推过来的镜像值**（notes/259 定案：客户端不跑
 * {@code simulateMoving}）。面系统因此不可能画出"客户端自己算的、与服务端不一致的速度"。
 * 引擎没推的量（例如门的开合状态，它在 {@code VehicleExtraData} 里）就只能按四步曲补镜像，
 * 而不是在这儿"顺手从别的地方推一个出来"。</p>
 */
public final class MmtrVehicleFaceSource implements MmtrFaceSource {

	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
	/** 不认识的字段名只提示一次（每个名字一条），免得每帧刷屏。 */
	private static final Set<String> WARNED_FIELDS = ConcurrentHashMap.newKeySet();

	private final VehicleExtension vehicle;

	public MmtrVehicleFaceSource(VehicleExtension vehicle) {
		this.vehicle = vehicle;
	}

	@Override
	public Object raw(String field) {
		if (field == null) {
			return null;
		}
		return switch (field) {
			case "speed" -> vehicle.getSpeed();
			case "limitKmh" -> vehicle.getMmtrSpeedLimitKmhFromSync();
			case "clock" -> LocalTime.now().format(CLOCK);

			case "pid.service" -> vehicle.getMmtrPidServiceFromSync();
			case "pid.terminus" -> vehicle.getMmtrPidTerminusFromSync();
			case "pid.next" -> vehicle.getMmtrPidNextFromSync();

			case "job.id" -> vehicle.getMmtrJobIdFromSync();
			case "job.step" -> vehicle.getMmtrTaskStepFromSync();
			case "job.steps" -> vehicle.getMmtrTaskStepsFromSync();
			case "job.note" -> vehicle.getMmtrTaskNoteFromSync();
			case "mission.state" -> vehicle.getMmtrMissionStateFromSync();
			case "mission.executor" -> vehicle.getMmtrMissionExecutorFromSync();
			case "subtask.text" -> vehicle.getMmtrSubTasksFromSync();
			case "subtask.hint" -> vehicle.getMmtrSubTaskHintFromSync();
			case "subtask.revision" -> vehicle.getMmtrSubTaskRevisionFromSync();
			case "subtask.acks" -> vehicle.getMmtrSubTaskAcksFromSync();

			case "hold.reason" -> vehicle.getMmtrHoldReasonFromSync();
			case "mode" -> vehicle.getMmtrModeFromSync();
			case "driver" -> vehicle.getMmtrDriverFromSync();
			case "active" -> vehicle.isMmtrActiveFromSync();
			case "authorityTripped" -> vehicle.isMmtrAuthorityTripped();
			case "protection" -> vehicle.isMmtrProtectionFromSync();
			case "emergency" -> vehicle.isMmtrEmergencyFromSync();
			case "pinned" -> vehicle.isMmtrPinned();

			case "light.a" -> vehicle.getMmtrLightAFromSync();
			case "light.b" -> vehicle.getMmtrLightBFromSync();
			case "light.loco" -> vehicle.isMmtrLightLocoFromSync();

			case "aws.warning" -> vehicle.isMmtrAwsWarningPendingFromSync();
			case "aws.acknowledged" -> vehicle.isMmtrAwsWarningAcknowledgedFromSync();
			case "block.held" -> vehicle.isMmtrBlockHeldFromSync();
			case "lzb.supervising" -> vehicle.isMmtrLzbSupervisingFromSync();
			case "lzb.ceilingKmh" -> vehicle.getMmtrLzbCeilingKmhFromSync();
			case "lzb.targetKmh" -> vehicle.getMmtrLzbTargetKmhFromSync();
			case "lzb.targetM" -> vehicle.getMmtrLzbTargetDistanceMFromSync();
			case "shunt.authority" -> vehicle.getMmtrShuntAuthorityFromSync();
			case "shunt.limitKmh" -> vehicle.getMmtrShuntSpeedLimitKmhFromSync();
			case "shunt.remainingS" -> vehicle.getMmtrShuntRemainingSFromSync();

			case "handle.throttle" -> vehicle.getMmtrThrottleFromSync();
			case "handle.brake" -> vehicle.getMmtrBrakeFromSync();
			case "handle.reverser" -> vehicle.getMmtrReverserFromSync();
			case "handle.drive" -> vehicle.getMmtrDriveHandleFromSync();
			case "handle.cruiseKmh" -> vehicle.getMmtrCruiseKmhFromSync();
			case "handle.spec" -> vehicle.getMmtrHandleSpecFromSync();

			case "cab.end" -> vehicle.getMmtrCabEndFromSync();
			case "cab.carIndex" -> vehicle.getMmtrCabCarIndexFromSync();
			case "cab.arcM" -> vehicle.getMmtrCabArcMFromSync();
			case "cab.keyHolder" -> vehicle.getMmtrCabKeyHolderFromSync();
			case "cab.crew" -> vehicle.getMmtrCabCrewFromSync();
			case "cab.active" -> vehicle.getMmtrActiveCabFromSync();
			case "cab.name" -> vehicle.getMmtrCabNameFromSync();

			case "motor.forceN" -> vehicle.getMmtrMotorForceN();
			case "brake.pneumaticForceN" -> vehicle.getMmtrPneumaticBrakeForceN();

			default -> {
				if (WARNED_FIELDS.add(field)) {
					Init.LOGGER.warn("[MMTR] 面文档引用了不存在的字段「{}」—— 见 MmtrFaceFields 的字段表（四步曲：镜像 → 取值口 → 登记表 → 重生成 fields.json）", field);
				}
				yield null;
			}
		};
	}
}
