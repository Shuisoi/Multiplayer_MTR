/*
 * 时刻换算的用例（纯 Node，不需要浏览器与引擎）。
 *
 * 跑法：`npm run test:plan`（= `node --experimental-strip-types --test scripts/*.test.ts`）。
 *
 * 为什么值得单独一条：这一组函数是"引擎的当日毫秒"与"人看的 HH:MM"之间唯一的桥。
 * 差一个 60（分钟/秒）或 1000（秒/毫秒），页面上仍然是一个"看起来很正常"的时刻 ——
 * 只有拿引擎那边的常量（07:00 = 25_200_000）钉住，错了才会当场红。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {durationText, hhmm, hhmmss, parseHhmm} from "../src/api/planTime.ts";

test("引擎口径：07:00 就是 25200000", () => {
	assert.equal(parseHhmm("07:00"), 25_200_000);
	assert.equal(hhmm(25_200_000), "07:00");
	assert.equal(parseHhmm("00:00"), 0);
	assert.equal(parseHhmm("23:59"), 86_340_000);
});

test("往返一致（分钟精度）", () => {
	for (const text of ["00:00", "05:30", "07:00", "12:34", "18:45", "23:59"]) {
		const millis = parseHhmm(text);
		assert.notEqual(millis, null, `${text} 应该解析得出来`);
		assert.equal(hhmm(millis as number), text, `${text} 往返必须回到自己`);
	}
});

test("秒也给得出来（事件条与步计划用的是秒）", () => {
	assert.equal(hhmmss(25_200_000 + 61_000), "07:01:01");
});

test("写错的时间返回 null，而不是悄悄当午夜", () => {
	for (const bad of ["", "7", "25:00", "07:60", "abc", "07:00:00"]) {
		assert.equal(parseHhmm(bad), null, `「${bad}」应该判为写错`);
	}
	// 全角冒号与多余空格是手输时的常态，要认
	assert.equal(parseHhmm(" 7：5 "), 7 * 3_600_000 + 5 * 60_000);
});

test("时长文案", () => {
	assert.equal(durationText(45_000), "45 秒");
	assert.equal(durationText(150_000), "2 分 30 秒");
	assert.equal(durationText(3_600_000), "60 分 0 秒");
});
