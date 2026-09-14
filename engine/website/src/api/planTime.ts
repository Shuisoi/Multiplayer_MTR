/**
 * 当日毫秒 ↔ `HH:MM` 的换算（**只放纯函数**，一个 import 都没有）。
 *
 * <p>为什么单独一个文件：这是前后端之间**最容易错、又最不容易看出来**的一条口径。
 * 引擎里 07:00 是 `25_200_000`（当日毫秒），不是纪元毫秒；界面上人只认 `07:00`。
 * 换算只要差一个 60 或 1000，页面就会显示一个"看起来很正常"的时刻 —— 07:00 变成 07:00:00
 * 或者 04:12，没人会立刻发现。所以这一组函数不依赖浏览器也不依赖网络，
 * 可以被 `node --experimental-strip-types --test scripts/plan-time.test.ts` 直接跑
 * （见 `package.json` 的 `test:plan`），坏了就是红的。</p>
 */

/** 当日毫秒 → `HH:MM`。 */
export function hhmm(dayTimeMillis: number): string {
	const totalMinutes = Math.floor(dayTimeMillis / 60000);
	const hours = Math.floor(totalMinutes / 60) % 24;
	const minutes = totalMinutes % 60;
	return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
}

/** 当日毫秒 → `HH:MM:SS`。 */
export function hhmmss(dayTimeMillis: number): string {
	const totalSeconds = Math.floor(dayTimeMillis / 1000);
	return `${hhmm(dayTimeMillis)}:${String(totalSeconds % 60).padStart(2, "0")}`;
}

/**
 * `HH:MM` → 当日毫秒。
 *
 * <p>解析不了**返回 null**，不返回 0：0 是"午夜"这个合法时刻，
 * 拿它当"填错了"的替身会让"密度段从 00:00 开始"和"时间写错了"变成同一件事。</p>
 */
export function parseHhmm(text: string): number | null {
	const match = /^\s*(\d{1,2})\s*[:：]\s*(\d{1,2})\s*$/.exec(text ?? "");
	if (match == null) {
		return null;
	}
	const hours = Number(match[1]);
	const minutes = Number(match[2]);
	if (hours > 23 || minutes > 59) {
		return null;
	}
	return (hours * 60 + minutes) * 60000;
}

/** 秒 → `m 分 s 秒`（读数用）。 */
export function durationText(millis: number): string {
	const totalSeconds = Math.round(millis / 1000);
	const minutes = Math.floor(totalSeconds / 60);
	const seconds = totalSeconds % 60;
	return minutes > 0 ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`;
}
