package org.mtr.core.mmtr.command;

import org.mtr.core.simulation.Simulator;

import java.util.List;
import java.util.Map;

/**
 * {@code rail …}：铺设轨道。
 *
 * <h3>为什么要单独有一族"铺轨"指令</h3>
 * <p>MTR 的轨道在磁盘上存成两半，而这两半过去只能靠两套完全不同的手段分别产生：</p>
 * <ul>
 *   <li><b>世界里的节点方块</b>（{@code mtr:rail}）—— 过去只能人在游戏里用轨道连接器放；</li>
 *   <li><b>引擎的轨道数据</b>（{@code rails/<hex>}）—— 过去只在 {@code Simulator} 构造时读一次磁盘。</li>
 * </ul>
 * <p>于是"从外部铺一段轨"必然要重启服务端（改文件不会重读），而且很容易做出
 * <b>方块和引擎对不上</b>的状态：文件写了、方块没放，或者反过来。实测踩过这个坑。</p>
 *
 * <h3>真正的运行时通路</h3>
 * <p>{@code UpdateDataRequest.update()}（{@code operation/UpdateDataRequest.java}）会在<b>运行时</b>
 * 建轨，并顺手调 {@code Rail.checkOrCreateSavedRailAndUpdateTiltAngles(...)} 创建站台 / 股道记录，
 * 最后 {@code data.sync()} 重建 {@code positionsToRail} 图 —— 玩家在游戏里放轨走的就是这条路。
 * 所以"热建轨"根本不需要新机制，只需要把它暴露出来。</p>
 *
 * <p>本族指令<b>不在引擎侧造轨</b>：造轨要同时放节点方块（只有游戏端能放），
 * 所以这里只做参数校验并把整条指令<b>转交游戏端执行器</b>（{@link Simulator#mmtrPushCommand}）。
 * 由游戏端一次调用同时完成"放方块 + 造数据 + 推给引擎"，两边因此不可能不一致。</p>
 *
 * <pre>
 *   rail add &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt; [--speed=300] [--platform] [--siding] [--angle1=deg --angle2=deg]
 *   rail remove &lt;x&gt; &lt;y&gt; &lt;z&gt;      ← 删掉挂在这个节点上的所有轨（等价于玩家敲掉该节点方块）
 *   rail list
 * </pre>
 *
 * <p>{@code --angle1=} / {@code --angle2=}（2026-09-26 加）：显式指定两端的**行进方向**角度。
 * 不给就只能画直线（两端朝向被钉在弦向上）；给了才能画转角与 S 弯。
 * 几何规则与实测半径见表见 skill {@code mmtr-track-building} §二。</p>
 */
final class MmtrRailCommands {

	private MmtrRailCommands() {
	}

	static MmtrCommandDispatcher.Result execute(Simulator simulator, String verb, List<String> positional, Map<String, String> options) {
		if (verb.equals("add")) {
			return add(simulator, positional, options);
		}
		if (verb.equals("remove")) {
			return remove(simulator, positional, options);
		}
		if (verb.equals("list")) {
			return list(simulator);
		}
		return MmtrCommandDispatcher.usage("rail 支持 add / remove / list");
	}

	/**
	 * {@code rail remove <x> <y> <z>} —— 删掉**挂在这个节点上的所有轨**，并把节点方块清成空气。
	 *
	 * <h3>为什么需要它</h3>
	 * <p>在此之前**删轨做不到**：{@code rail} 只有 {@code add}/{@code list}，
	 * {@code /setblock … air} 实测<b>不触发</b> {@code BlockNode.onBreak2}（notes/306），
	 * 所以只能用<b>人在游戏里手动敲节点</b>——改一段标高、切一段分段都要人肉敲几十下
	 * （notes/307、notes/315 都卡在这里）。本指令把那条通路暴露出来。</p>
	 *
	 * <p>它做的两件事与玩家敲掉节点方块<b>完全一致</b>：
	 * 节点方块换空气 + {@code PacketDeleteData.sendDirectlyToServerRailNodePosition}
	 * ⇒ 引擎删掉挂在该节点上的轨（{@code DeleteDataRequest.addRailNodePosition}）。</p>
	 *
	 * <p><b>注意</b>：删一个节点会连带删掉**所有**以它为端点的轨（比如删中间节点会同时删掉左右两段）。
	 * 这与玩家敲方块的行为一致，是刻意的。</p>
	 */
	private static MmtrCommandDispatcher.Result remove(Simulator simulator, List<String> positional, Map<String, String> options) {
		final long[] xyz = parseThree(positional);
		if (xyz == null) {
			return MmtrCommandDispatcher.usage("rail remove <x> <y> <z>（也接受一个 \"x,y,z\"）");
		}
		simulator.mmtrPushCommand("rail remove " + xyz[0] + " " + xyz[1] + " " + xyz[2]);

		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "rail", "remove");
		result.line("已把删轨交给游戏端执行（与玩家敲掉节点方块同一条通路）：");
		result.line("  rail remove " + xyz[0] + " " + xyz[1] + " " + xyz[2]);
		result.line("游戏端会在同一 tick 内：把该节点方块换成空气 → 发 PacketDeleteData → 引擎删掉挂在这个节点上的所有轨。");
		result.line("⚠ 以该节点为端点的轨**全部**会被删（含左右两段）。用 rail list 核对条数。");
		return result;
	}

	private static MmtrCommandDispatcher.Result add(Simulator simulator, List<String> positional, Map<String, String> options) {
		// 六个坐标，或 "x,y,z x,y,z" 两个三元组
		final long[] xyz = parseSix(positional);
		if (xyz == null) {
			return MmtrCommandDispatcher.usage("rail add <x1> <y1> <z1> <x2> <y2> <z2> [--speed=300] [--platform] [--siding] [--angle1=deg --angle2=deg]");
		}
		if (xyz[0] == xyz[3] && xyz[1] == xyz[4] && xyz[2] == xyz[5]) {
			return MmtrCommandDispatcher.usage("rail add：两个端点相同，无法构成轨道");
		}

		final boolean isPlatform = options.containsKey("platform") || options.containsKey("站台");
		final boolean isSiding = options.containsKey("siding") || options.containsKey("股道");
		if (isPlatform && isSiding) {
			return MmtrCommandDispatcher.usage("rail add：--platform 与 --siding 互斥");
		}

		/*
		 * 站台轨说明（用户 2026-09-24 问"MTR 里是有站台用的轨道的"）：
		 *
		 * MTR 游戏端有专门的 rail_connector_platform，它对应 RailType.PLATFORM，**限速固定 80**。
		 * 但"是不是站台轨"（isPlatform）与"限速多少"在引擎里是两个独立参数
		 * （Rail.newRail(..., speedLimit1, speedLimit2, isPlatform, isSiding, ...)），
		 * 所以可以做出"160 限速的站台轨"：--platform 只把 isPlatform 置真，speed 仍然生效。
		 *
		 * 这里因此**不再**提示"限速由类型定死"——那只对 --siding 成立。
		 */
		final long speed = parseSpeed(options.getOrDefault("speed", "300"));
		if (speed <= 0) {
			return MmtrCommandDispatcher.usage("rail add：--speed 必须是正数（公里/小时）");
		}

		/*
		 * 显式朝向 --angle1= / --angle2=（2026-09-26 加，notes/322）。
		 *
		 * 不给这两个参数时，游戏端会把**两端节点的朝向都钉在这条弦的方向上**
		 * （MmtrCommandExecutor.resolveNodeState 的目标方位就是这么来的），
		 * 于是 RailMath 永远走 case 1.a「平行且共线」⇒ rail add 只会画直线。
		 * 实机证据：16 条侧线，量出来精确 220 m。
		 *
		 * 给了之后两种形状都出来了（几何规则与实测见 skill mmtr-track-building §二）：
		 *   两端朝向**不同**  ⇒ 转角（差 90° 且 along=lateral 时是干净的纯圆弧）
		 *   两端朝向**相同**但横向错开 ⇒ S 弯（两段圆弧）
		 *
		 * 角度口径与节点方块一致：**行进方向**，0=东、90=南、180=西、270=北；
		 * 节点朝向空间本身是 mod 180（notes/288），所以只需 mod 180 有意义。
		 */
		final boolean hasAngle1 = options.containsKey("angle1");
		final boolean hasAngle2 = options.containsKey("angle2");
		if (hasAngle1 != hasAngle2) {
			return MmtrCommandDispatcher.usage("rail add：--angle1 与 --angle2 必须成对给出（只给一个时另一端的朝向无从确定）");
		}
		final Double angle1 = hasAngle1 ? parseAngle(options.get("angle1")) : null;
		final Double angle2 = hasAngle2 ? parseAngle(options.get("angle2")) : null;
		if (hasAngle1 && (angle1 == null || angle2 == null)) {
			return MmtrCommandDispatcher.usage("rail add：--angle1/--angle2 必须是度数（-360..360），收到 --angle1="
				+ options.get("angle1") + " --angle2=" + options.get("angle2"));
		}

		final StringBuilder command = new StringBuilder("rail add ")
			.append(xyz[0]).append(' ').append(xyz[1]).append(' ').append(xyz[2]).append(' ')
			.append(xyz[3]).append(' ').append(xyz[4]).append(' ').append(xyz[5]);
		if (angle1 != null && angle2 != null) {
			command.append(" --angle1=").append(angle1).append(" --angle2=").append(angle2);
		}
		if (isPlatform) {
			command.append(" --platform");
		}
		if (isSiding) {
			command.append(" --siding");
		}
		if (!isSiding) {
			command.append(" --speed=").append(speed);
		}

		simulator.mmtrPushCommand(command.toString());

		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "rail", "add");
		result.line("已把铺轨交给游戏端执行（造轨必须同时放节点方块，只有游戏端能做）：");
		result.line("  " + command);
		result.line("游戏端会在同一 tick 内：放置两端 mtr:rail 节点方块 → 构造轨道 → 推给引擎触发热建。");
		result.line("执行结果写进指令日志，可用 query node <x>,<y>,<z> 核对图结构。");
		if (angle1 != null && angle2 != null) {
			final double turn = ((angle2 - angle1) % 360 + 360) % 360;
			result.line("显式朝向：起点行进方向 " + angle1 + "°、终点 " + angle2 + "°（转过 " + turn + "°）"
				+ " ⇒ 这一次**不保证是直线**：核对时看 rail list 的**长度**，直线=弦长，曲线>弦长。");
		}
		if (isPlatform) {
			result.line("站台轨：isPlatform=true，限速仍按 --speed=" + speed + "（站台轨限速与类型无关）。");
		}
		if (isSiding) {
			result.line("注意：股道轨限速固定 40（RailType.SIDING），--speed 不生效。");
		}
		return result;
	}

	private static MmtrCommandDispatcher.Result list(Simulator simulator) {
		final MmtrCommandDispatcher.Result result = new MmtrCommandDispatcher.Result(true, "rail", "list");
		if (simulator.rails.isEmpty()) {
			result.line("（没有任何轨道）");
			return result;
		}
		result.line("共 " + simulator.rails.size() + " 条轨道：");
		for (final org.mtr.core.data.Rail rail : simulator.rails) {
			final org.mtr.core.data.Position[] ends = rail.mmtrOrderedPositions();
			final String kind = rail.isPlatform() ? "站台" : rail.isSiding() ? "股道" : rail.canTurnBack() ? "折返" : "普通";
			result.add(rail.getHexId());
			result.line("  (" + ends[0].getX() + "," + ends[0].getY() + "," + ends[0].getZ() + ")→("
				+ ends[1].getX() + "," + ends[1].getY() + "," + ends[1].getZ() + ")"
				+ "  " + kind
				+ "  限速 " + rail.getSpeedLimitKilometersPerHour(false) + "/" + rail.getSpeedLimitKilometersPerHour(true)
				+ "  长 " + Math.round(rail.railMath.getLength()) + " m");
		}
		return result;
	}

	/** 三个坐标：{@code x y z} 或一个 {@code x,y,z}。 */
	private static long @org.jspecify.annotations.Nullable [] parseThree(List<String> positional) {
		if (positional.size() >= 3) {
			try {
				return new long[]{
					Long.parseLong(positional.get(0)), Long.parseLong(positional.get(1)), Long.parseLong(positional.get(2))
				};
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		if (positional.size() == 1) {
			final String[] a = positional.get(0).split(",");
			if (a.length >= 3) {
				try {
					return new long[]{
						Long.parseLong(a[0].trim()), Long.parseLong(a[1].trim()), Long.parseLong(a[2].trim())
					};
				} catch (NumberFormatException ignored) {
					return null;
				}
			}
		}
		return null;
	}

	/** 六个坐标：{@code x y z x y z} 或两个 {@code x,y,z} 三元组。 */
	private static long @org.jspecify.annotations.Nullable [] parseSix(List<String> positional) {
		if (positional.size() >= 6) {
			try {
				return new long[]{
					Long.parseLong(positional.get(0)), Long.parseLong(positional.get(1)), Long.parseLong(positional.get(2)),
					Long.parseLong(positional.get(3)), Long.parseLong(positional.get(4)), Long.parseLong(positional.get(5))
				};
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		if (positional.size() == 2) {
			final String[] a = positional.get(0).split(",");
			final String[] b = positional.get(1).split(",");
			if (a.length >= 3 && b.length >= 3) {
				try {
					return new long[]{
						Long.parseLong(a[0].trim()), Long.parseLong(a[1].trim()), Long.parseLong(a[2].trim()),
						Long.parseLong(b[0].trim()), Long.parseLong(b[1].trim()), Long.parseLong(b[2].trim())
					};
				} catch (NumberFormatException ignored) {
					return null;
				}
			}
		}
		return null;
	}

	private static long parseSpeed(String raw) {
		try {
			return Long.parseLong(raw.trim());
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	/**
	 * 解析 {@code --angle1=} / {@code --angle2=} 的度数。
	 *
	 * <p>返回 {@code null} 表示"给是给了，但不是个能用的度数"（例如写成裸 {@code --angle1}
	 * 时选项值是 {@code "true"}）—— 调用方据此报错，而不是静默退回弦向。
	 * <b>静默退回是最坏的结果</b>：脚本以为画了曲线，实际得到的是一条直线，
	 * 而 {@code rail add} 照样回 ok（铁律 0）。</p>
	 */
	private static @org.jspecify.annotations.Nullable Double parseAngle(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			final double value = Double.parseDouble(raw.trim());
			return value >= -360 && value <= 360 ? value : null;
		} catch (NumberFormatException ignored) {
			return null;
		}
	}
}
