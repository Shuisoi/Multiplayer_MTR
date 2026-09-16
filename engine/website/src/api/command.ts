/**
 * **中控指令通道**（`POST /mtr/api/map/mmtr-command`）—— 地图页那些"按一下要对世界做一件事"的按钮走它。
 *
 * <h2>为什么按钮走指令通道，而不是每加一个按钮就加一条接口</h2>
 * <p>引擎侧的 `MmtrCommandDispatcher` 已经把名词/动词分好（`point lock|unlock|release|list|why`、
 * `train …`、`route …`、`server stop|restart`…），而且**回话本身就是给操作者看的**：
 * `lines[]` 里是「已解锁全部人工锁：清掉 3 把（含界面上没有对应进向的那些）」这种整句。
 * 界面照原话显示即可 —— 两边各写一遍说法，迟早会说岔。</p>
 *
 * <p>与 `mmtr-point-op` 那条路的分工：扳**一处**道岔是一个原子动作（有专门的接口，见
 * `topology.ts` 的 `setPointBranch`）；"把所有人工锁解掉"这类**批量/世界级**动作没有原子接口，
 * 就是一条指令。</p>
 *
 * <p>这个文件**刻意只依赖 `./client`**（不引 `@/…` 别名、不引领域模型）：指令这个词表是纯字符串，
 * 可以用假 fetch 直接把它钉住（`scripts/map-actions.test.ts`）—— 指令字符串写歪一个词，
 * 引擎只会回一句 usage，界面却会把它当成成功提示，这种错是静默的。</p>
 */
import {postJson} from "./client.ts";

/** 引擎对一条指令的回复。 */
export interface CommandResult {
	readonly ok: boolean;
	readonly namespace: string;
	readonly verb: string;
	/** 受影响对象的键（`point locks` 给的是 `x,y,z|via` 这种）。 */
	readonly affected: readonly string[];
	/** 引擎写给操作者的整句（第一行通常是结论）。 */
	readonly lines: readonly string[];
}

/** 下发一条中控指令（`command` 就是指令栏里那一行，例如 `point unlock --all`）。 */
export function runMmtrCommand(command: string): Promise<CommandResult> {
	return postJson<CommandResult>("map/mmtr-command", {command});
}

/**
 * **解锁所有人工锁岔**（`point unlock --all`）。
 *
 * <h3>为什么必须是"全解"而不是按界面逐行解</h3>
 * <p>人工扳一次岔会把那处道岔的**三条进向一起锁上**（用户 2026-09-14 的裁定：人工搬岔同时锁住、
 * 永久生效直到解锁），而 `/mmtr-points` 只列得出"够得上是一个岔口"的那些进向 —— 现场实测一处道岔
 * 引擎手里握着 **3 把**锁，而接口里**只有 1 行**报 `locked`（另外两把的进向在界面上根本没有行）。
 * 所以"界面上看没有锁"不等于"引擎手里没有锁"，按界面逐行解永远有死角（而且重启后那些锁会原样回来）。
 * 引擎因此专门留了这条"按自己持有的键全清"的入口，界面就照它调。</p>
 */
export function unlockAllPoints(): Promise<CommandResult> {
	return runMmtrCommand("point unlock --all");
}
