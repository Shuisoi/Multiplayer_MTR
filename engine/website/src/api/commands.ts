import {postJson} from "./client";

/**
 * 指令通道：`/mtr/api/map/mmtr-command`。
 *
 * <h3>为什么单独一个文件</h3>
 * <p>它和拓扑 feed 走的是同一个 servlet，但**不是同一类东西**：拓扑是"把世界读出来画"，
 * 指令是"直接改世界"。放在一起的话，日后想给指令加权限、加审计、或者换个通道
 * （例如走 websocket 长连），就得从取数代码里把它抠出来。所以这里按能力分文件，
 * 而不是按 URL 分文件。</p>
 *
 * <h3>回复的形状</h3>
 * <p>服务端对每条指令回的是**可核对的事实**：`namespace`/`verb` 说明它认成了哪条指令，
 * `affected` 是被改动的对象 id 列表，`lines` 是给人看的分行详情。前端只负责显示，
 * 不做任何加工——「ok 就完事」式的回复已经被明确否决过。</p>
 */
export interface CommandResult {
	/** 指令是否被受理并成功执行。 */
	readonly ok: boolean;
	/** 服务端认出的名词，例如 `vehicle`；空串表示没认出。 */
	readonly namespace: string;
	/** 服务端认出的动词，例如 `spawn`；空串表示没认出。 */
	readonly verb: string;
	/** 被改动的对象 id（车辆 id / 股道 id / 信号灯 key 等）。 */
	readonly affected: readonly string[];
	/** 分行详情：成功时是"做了什么"，失败时通常直接是用法表。 */
	readonly lines: readonly string[];
	/** 解析/执行失败时的说明（例如"command is required"）。 */
	readonly error?: string;
	/** 最近的命令日志（含游戏端写进来的行）。 */
	readonly log?: readonly string[];
}

/**
 * 发一条指令。
 *
 * @param command 名词打头的指令原文，例如 `vehicle list` / `server restart`
 */
export function sendCommand(command: string): Promise<CommandResult> {
	return postJson<CommandResult>("map/mmtr-command", {command});
}

/**
 * 只读命令日志（`command` 为空时服务端不会执行任何东西，只回日志）。
 *
 * <p>日志里同时有网页发的指令与**游戏端**写的行（例如 `[signals] 扫描完成…`），
 * 所以它是两边共用的同一份历史，指令栏直接显示它即可，不必自己记一份。
 */
export function fetchCommandLog(): Promise<CommandResult> {
	return postJson<CommandResult>("map/mmtr-command", {command: ""});
}

/**
 * 点选绑定：把这盏灯的守轨列表整体设为 {@code rails}。
 *
 * <p>整体替换而不是"加一条 / 删一条"：界面持有完整列表（刚从接口拿到），一次替换没有并发歧义，
 * 也不会出现"同一根轨点两次变成重复项"这类脏数据。空列表 = 清掉人工绑定，回到引擎按站位与朝向推断。</p>
 *
 * <p>方向**不用传**：列车必然从灯所站的那一端进入，方向由绑定的几何关系决定 ——
 * 这正是点选方案能成立的关键（否则界面上还要多问一次"往哪边"）。</p>
 */
export function bindSignalRails(signal: {x: number; y: number; z: number}, rails: readonly string[]): Promise<CommandResult> {
	const list = rails.length === 0 ? " --clear" : ` --rail=${rails.join(",")}`;
	return sendCommand(`signal bind ${signal.x} ${signal.y} ${signal.z}${list}`);
}

/**
 * 点选绑定：让**引擎**把这条轨的绑定状态取反（守着就解绑，没守就绑上）。
 *
 * <h3>为什么把"加还是减"交给引擎</h3>
 * <p>界面手里的绑定列表可能比画面旧一瞬（刚点完、数据刚重取），据此判断"这次是绑还是解绑"
 * 会把解绑下成再绑一次 —— 实测同一条轨越点越多，而且从画面上看不出哪里错了。
 * 引擎自己知道现在守什么，让它按事实取反，调用方就不必持有最新状态。</p>
 *
 * <p>方向**不用传**：列车必然从灯所站的那一端进入，方向由绑定的几何关系决定 ——
 * 这正是点选方案能成立的关键（否则界面上还要多问一次"往哪边"）。</p>
 */
export function toggleSignalRail(signal: {x: number; y: number; z: number}, railHex: string): Promise<CommandResult> {
	return sendCommand(`signal bind ${signal.x} ${signal.y} ${signal.z} --toggle=${railHex}`);
}
