package org.mtr.mod.mmtr.face;

import java.util.List;

/**
 * 车辆动态面的**字段登记表**（notes/359 §数据接口）—— 这张表就是"游戏暴露给面系统的数据接口"，
 * 也是本系统唯一的真源：面文档能写哪些 {@code {字段}}、{@code MmtrFaceFieldsTests} 就盯哪些。
 *
 * <h2>为什么要有登记表（而不是让作者去翻 38 个 getter）</h2>
 * <p>水牌那一轮已经暴露了这个问题：能显示什么量散在 {@code Vehicle} 的 {@code getMmtr*FromSync()}
 * 里，作者无从知道，只能问人。于是：</p>
 * <ul>
 *   <li>每个量在这里一行（名字 / 类型 / 单位 / 来自哪个镜像字段 / 更新节拍 / 说明）；</li>
 *   <li>导出成 {@code tools/face-studio/fields.json}，web 工作室拿它做自动补全与字段面板；</li>
 *   <li>{@code MmtrFaceFieldsTests} 是**漂移守卫**：登记表 ↔ 取值口 ↔ 引擎同步白名单，
 *       三处必须对得上，对不上就红。</li>
 * </ul>
 *
 * <h2>加一个新量 = 四步曲（第 3 步就是往这张表里加一行）</h2>
 * <ol>
 *   <li><b>引擎侧</b>：算出来（或确认已有），并让它进镜像 ——
 *       {@code buildSrc/src/main/resources/schema/data/vehicle.json} 加字段、
 *       写镜像的地方标脏（如 {@code setMmtrPid} 那种"变了才标"）、
 *       若这个量每 tick 都会变，还要进 {@code VehicleSyncPatch.DYNAMIC_KEYS}
 *       （不进白名单 = 客户端只在"整份快照"那一刻看到它，现场表现就是"读数不实时"）；</li>
 *   <li><b>取值口</b>：{@code MmtrVehicleFaceSource} 的 switch 里加一个 {@code case}；</li>
 *   <li><b>登记表</b>：这里加一行（{@code mirror} 写引擎那个镜像字段名，{@code sync} 写它的节拍）；</li>
 *   <li><b>重生成</b> {@code fields.json}（用例不一致时会写出 {@code fields.json.actual}，拷过去即可）。</li>
 * </ol>
 *
 * <h2>节拍（{@code sync}）为什么值得写进接口</h2>
 * <p>它是作者最容易踩的坑：一个"每站都翻"的量（下一站）与一个"只在整份快照时更新"的量
 * （如 {@code cab.name}）在牌上看起来一样，但在**变化频率**与"为什么我改了它不刷新"上完全不同。
 * 与其让作者去猜，不如把节拍写进接口，并让用例去核对它等于引擎白名单里的真实情况。</p>
 */
public final class MmtrFaceFields {

	/** 值的类型（面文档里 {@code num} 可以直接参与算术，{@code str} 参与拼接）。 */
	public enum Type {

		NUM("num"), STR("str"), BOOL("bool");

		private final String json;

		Type(String json) {
			this.json = json;
		}

		public String json() {
			return json;
		}
	}

	/** 这个量是**问出来的**（镜像字段 / 客户端提供），还是面系统**算出来的**（派生量）。 */
	public enum Kind {

		RAW("raw"), DERIVED("derived");

		private final String json;

		Kind(String json) {
			this.json = json;
		}

		public String json() {
			return json;
		}
	}

	/** 更新节拍：{@code TICK} = 引擎白名单里（变了就随稀疏补丁出去）；{@code SNAPSHOT} = 只在整份快照里；{@code NA} = 引擎无关。 */
	public enum Sync {

		TICK("tick"), SNAPSHOT("snapshot"), NA("na");

		private final String json;

		Sync(String json) {
			this.json = json;
		}

		public String json() {
			return json;
		}
	}

	/**
	 * 一个字段。
	 *
	 * @param name   面文档里写的名字（点号分组，如 {@code pid.terminus}）
	 * @param type   类型
	 * @param kind   {@link Kind}
	 * @param mirror 引擎侧镜像字段名（与 {@code VehicleSyncPatch} 白名单同一个字符串）；
	 *               客户端自己提供的量（如时钟）或派生量为空串
	 * @param sync   更新节拍（见 {@link Sync}）
	 * @param unit   单位（{@code km/h}、{@code m}、{@code MPa}…；无量纲写空串）
	 * @param doc    人话说明（作者在工作室里看到的就是它）
	 */
	public record Spec(String name, Type type, Kind kind, String mirror, Sync sync, String unit, String doc) {
	}

	/** 导出文件的位置（相对工作区根；用例按用例的工作目录换算）。 */
	public static final String EXPORT_PATH = "mmtr/tools/face-studio/fields.json";

	private static final List<Spec> SPECS = List.of(
		// —— 走行 ——
		raw("speed", Type.NUM, "speed", Sync.TICK, "m/ms", "当前速度（引擎单位：米/毫秒）—— 要 km/h 用 speedKmh"),
		derived("speedKmh", Type.NUM, "km/h", "车速度，km/h（= speed × 3600）；排版时用 {speedKmh|int} 取整"),
		raw("limitKmh", Type.NUM, "mmtrSpeedLimitKmh", Sync.TICK, "km/h", "当前限速；0 = 没有限速信息"),
		raw("clock", Type.STR, "", Sync.NA, "", "客户端时钟 HH:MM（不来自引擎，按本机时间画）"),

		// —— 水牌 / 交路 ——
		raw("pid.service", Type.STR, "mmtrPidService", Sync.TICK, "", "班次号（= 作业单号，如 00101）；空 = 这台车不在任何在跑的作业单上"),
		raw("pid.terminus", Type.STR, "mmtrPidTerminus", Sync.TICK, "", "本趟终点（到下一次换端为止的最后一个站台停车站）；回库趟为空"),
		raw("pid.next", Type.STR, "mmtrPidNext", Sync.TICK, "", "下一站（当前位置之后第一个站台停车站，可跨换端）"),

		// —— 作业 / 任务 ——
		raw("job.id", Type.STR, "mmtrJobId", Sync.TICK, "", "作业单号"),
		raw("job.step", Type.NUM, "mmtrTaskStep", Sync.TICK, "", "当前第几步（从 1 起；0 = 不在执行）"),
		raw("job.steps", Type.NUM, "mmtrTaskSteps", Sync.TICK, "", "这份作业单共几步"),
		raw("job.note", Type.STR, "mmtrTaskNote", Sync.TICK, "", "这一步的人话说明"),
		raw("mission.state", Type.STR, "mmtrMissionState", Sync.TICK, "", "任务态"),
		raw("mission.executor", Type.STR, "mmtrMissionExecutor", Sync.TICK, "", "谁在执行（人 / 自动）"),
		raw("subtask.text", Type.STR, "mmtrSubTasks", Sync.TICK, "", "站台作业子任务清单"),
		raw("subtask.hint", Type.STR, "mmtrSubTaskHint", Sync.TICK, "", "现在该做什么（站台作业提示）"),
		raw("subtask.revision", Type.NUM, "mmtrSubTaskRevision", Sync.TICK, "", "子任务版本号（变了 = 清单变了）"),
		raw("subtask.acks", Type.NUM, "mmtrSubTaskAcks", Sync.TICK, "", "已确认几项"),

		// —— 为什么不动 / 谁在开 ——
		raw("hold.reason", Type.STR, "mmtrHoldReason", Sync.TICK, "", "被扣车的理由（空 = 没被扣）—— 调试与乘客提示都用它"),
		raw("mode", Type.STR, "mmtrMode", Sync.TICK, "", "车辆模式"),
		raw("driver", Type.STR, "mmtrDriver", Sync.TICK, "", "司机名（空 = 无人）"),
		raw("active", Type.BOOL, "mmtrActive", Sync.TICK, "", "这台车当前是否活跃（有司机/有任务）"),
		raw("authorityTripped", Type.BOOL, "mmtrAuthorityTripped", Sync.SNAPSHOT, "", "行车许可被触发/切除（整份快照节拍）"),
		raw("protection", Type.BOOL, "mmtrProtection", Sync.TICK, "", "保护制动中"),
		raw("emergency", Type.BOOL, "mmtrEmergency", Sync.TICK, "", "紧急制动中"),
		raw("pinned", Type.BOOL, "mmtrPinned", Sync.TICK, "", "停放钉住（无司机 + 无任务 + 拉不动）"),

		// —— 灯 ——
		raw("light.a", Type.NUM, "mmtrLightA", Sync.TICK, "", "A 端灯档（0/1/2）"),
		raw("light.b", Type.NUM, "mmtrLightB", Sync.TICK, "", "B 端灯档（0/1/2）"),
		raw("light.loco", Type.BOOL, "mmtrLightLoco", Sync.TICK, "", "本车有没有「关闭」档（机车多一档）"),

		// —— 信号 / 防护 ——
		raw("aws.warning", Type.BOOL, "mmtrAwsWarningPending", Sync.TICK, "", "AWS 报警待确认"),
		raw("aws.acknowledged", Type.BOOL, "mmtrAwsWarningAcknowledged", Sync.TICK, "", "AWS 报警已确认"),
		raw("block.held", Type.BOOL, "mmtrBlockHeld", Sync.TICK, "", "被闭塞/进路按住"),
		raw("lzb.supervising", Type.BOOL, "mmtrLzbSupervising", Sync.TICK, "", "LZB 正在监督"),
		raw("lzb.ceilingKmh", Type.NUM, "mmtrLzbCeilingKmh", Sync.TICK, "km/h", "LZB 顶棚速度"),
		raw("lzb.targetKmh", Type.NUM, "mmtrLzbTargetKmh", Sync.TICK, "km/h", "LZB 目标速度"),
		raw("lzb.targetM", Type.NUM, "mmtrLzbTargetDistanceM", Sync.TICK, "m", "到 LZB 目标的距离"),
		raw("shunt.authority", Type.STR, "mmtrShuntAuthority", Sync.TICK, "", "调车授权"),
		raw("shunt.limitKmh", Type.NUM, "mmtrShuntSpeedLimitKmh", Sync.TICK, "km/h", "调车限速"),
		raw("shunt.remainingS", Type.NUM, "mmtrShuntRemainingS", Sync.TICK, "s", "调车授权剩余时间"),

		// —— 司机控制 ——
		raw("handle.throttle", Type.NUM, "mmtrThrottleNotch", Sync.TICK, "", "油门手柄位置"),
		raw("handle.brake", Type.NUM, "mmtrBrakeNotch", Sync.TICK, "", "制动手柄位置"),
		raw("handle.reverser", Type.NUM, "mmtrReverser", Sync.TICK, "", "换向器（-1/0/1）"),
		raw("handle.drive", Type.NUM, "mmtrDriveHandle", Sync.TICK, "", "单手柄位置（单手柄车底）"),
		raw("handle.cruiseKmh", Type.NUM, "mmtrCruiseKmh", Sync.TICK, "km/h", "定速巡航设定值（0 = 没开）"),
		raw("handle.spec", Type.STR, "mmtrHandleSpec", Sync.TICK, "", "手柄规格字符串（这车有几个手柄、几档）"),

		// —— 驾驶室 ——
		raw("cab.end", Type.STR, "mmtrCabEnd", Sync.TICK, "", "当前占用哪一端（A/B）"),
		raw("cab.carIndex", Type.NUM, "mmtrCabCarIndex", Sync.TICK, "", "驾驶室在第几节车"),
		raw("cab.arcM", Type.NUM, "mmtrCabArcM", Sync.TICK, "m", "驾驶室沿编组的弧长位置"),
		raw("cab.keyHolder", Type.STR, "mmtrCabKeyHolder", Sync.TICK, "", "钥匙在谁手里"),
		raw("cab.crew", Type.STR, "mmtrCabCrew", Sync.TICK, "", "驾驶室乘务人员"),
		raw("cab.active", Type.STR, "mmtrActiveCab", Sync.TICK, "", "哪一端是活动驾驶室"),
		raw("cab.name", Type.STR, "mmtrCabName", Sync.SNAPSHOT, "", "驾驶室名字（整份快照节拍）"),

		// —— 机械读数 ——
		raw("motor.forceN", Type.NUM, "mmtrMotorForceN", Sync.TICK, "N", "电机出力"),
		raw("brake.pneumaticForceN", Type.NUM, "mmtrPneumaticBrakeForceN", Sync.TICK, "N", "整列气制动力")
	);

	private MmtrFaceFields() {
	}

	/** 全部字段（顺序就是导出顺序，也是工作室里的展示顺序）。 */
	public static List<Spec> specs() {
		return SPECS;
	}

	/** 按名字取一行；没有就是 {@code null}（面文档引用了不存在的字段 ⇒ 取值为空，不是崩）。 */
	public static Spec spec(String name) {
		for (final Spec spec : SPECS) {
			if (spec.name().equals(name)) {
				return spec;
			}
		}
		return null;
	}

	/** 只关心"取值口必须认得的"那些名字（{@link Kind#RAW}）。 */
	public static List<Spec> rawSpecs() {
		return SPECS.stream().filter(spec -> spec.kind() == Kind.RAW).toList();
	}

	/**
	 * 导出成 {@code fields.json} 的文本（**确定性**：同一份登记表永远给同一段文本，
	 * 所以用例可以逐字比对 —— 这就是漂移守卫的另一半）。
	 */
	public static String exportJson() {
		final StringBuilder builder = new StringBuilder();
		builder.append("{\n");
		builder.append("  \"version\": 1,\n");
		builder.append("  \"note\": \"MMTR 车辆动态面 · 字段表。由 MmtrFaceFields.exportJson() 生成，用例 MmtrFaceFieldsTests 逐字比对 —— 不要手改；缺字段时用例会写出 fields.json.actual，拷过来即可。\",\n");
		builder.append("  \"fields\": [\n");
		for (int i = 0; i < SPECS.size(); i++) {
			final Spec spec = SPECS.get(i);
			builder.append("    { \"name\": \"").append(escape(spec.name()))
				.append("\", \"type\": \"").append(spec.type().json())
				.append("\", \"kind\": \"").append(spec.kind().json())
				.append("\", \"mirror\": \"").append(escape(spec.mirror()))
				.append("\", \"sync\": \"").append(spec.sync().json())
				.append("\", \"unit\": \"").append(escape(spec.unit()))
				.append("\", \"doc\": \"").append(escape(spec.doc()))
				.append("\" }").append(i + 1 < SPECS.size() ? "," : "").append('\n');
		}
		builder.append("  ]\n");
		builder.append("}\n");
		return builder.toString();
	}

	private static Spec raw(String name, Type type, String mirror, Sync sync, String unit, String doc) {
		return new Spec(name, type, Kind.RAW, mirror, sync, unit, doc);
	}

	private static Spec derived(String name, Type type, String unit, String doc) {
		return new Spec(name, type, Kind.DERIVED, "", Sync.NA, unit, doc);
	}

	/** JSON 字符串转义（这里只需要处理引号与反斜杠；说明里刻意不写控制字符）。 */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
