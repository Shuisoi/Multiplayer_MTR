import {ref} from "vue";

/**
 * 一条"要送进指令栏"的指令：地图上的节点/灯写进来，指令栏取走。
 *
 * <h3>为什么要有这条通道（而不是让用户复制坐标再粘贴）</h3>
 * <p>在地图上点一盏灯、想知道"它为什么是这个颜色"，本来要做的事是：复制坐标 → 打开指令栏 →
 * 粘进 {@code signal why -70 -59 -167} → 回车。实测这条路上有两处会白费：</p>
 * <ol>
 *   <li><b>剪贴板会被浏览器拒绝</b>（{@code NotAllowedError: Write permission denied}，见
 *       {@code domain/clipboard.ts}）—— 于是"复制"变成弹一个让你手动复制的弹窗，比不打这个功能还慢；</li>
 *   <li><b>坐标的写法不对</b>：界面上显示的是 {@code -70, -59, -167}（给人读的），而指令要的是
 *       {@code -70 -59 -167}（空格分隔）—— 粘进去是解析失败，还得手改两个逗号。</li>
 * </ol>
 *
 * <p>所以地图上凡是要"用到这个坐标"的地方，都直接**把整条指令送进指令栏**，一个键都不用按。
 * 这里只转发**文本**，不在指令栏里自动执行：{@code signal bind} 这类会改世界的指令必须先让人看一眼。</p>
 */

/** 待送入指令栏的指令；空串表示没有。指令栏取走后会清空。 */
export const pendingCommand = ref("");

/** 把一条指令送到指令栏（指令栏会自己打开并把光标放到输入行）。 */
export function sendToConsole(command: string): void {
	pendingCommand.value = command;
}

/** 取走待送入的指令（取走即清空，避免同一条被送两次）。 */
export function takePendingCommand(): string {
	const command = pendingCommand.value;
	pendingCommand.value = "";
	return command;
}
