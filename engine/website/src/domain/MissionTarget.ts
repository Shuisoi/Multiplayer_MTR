/*
 * **任务目标**：把 `mission.targetSidingId` 那个 id 换成写给人看的一行字。
 *
 * <h2>为什么"目标股道"这个名字是骗人的</h2>
 * <p>引擎的任务里那个字段叫 `targetSidingId`，但它装的**不一定是一条股道**：
 * `MmtrJobScheduler` 派任务时，目标要么是作业单步骤指的**股道**（调车/回段），要么是**站台**
 * （客运任务就是在某个站台停一次）—— 两条都由同一个 id 字段带出来
 * （现场实测：一辆车 `targetSidingId = 954364252968674217`，它在 `/mmtr-platforms` 里，
 * 是「站 3 · 站台 1 · 南行 · 20 米」；同一辆车的 `startSidingId` 才是真股道）。</p>
 *
 * <p>所以这里**两个清单都查**：股道优先（调车/回段是它），查不到再当站台（客运是它），
 * 两个都没有就退回 id 原文 —— 宁可难看也不要写"未知"：id 至少能拿去对数据。</p>
 *
 * <p>纯函数放这里（而不是写在卡片组件里）是为了能用例钉住：查哪一档、查不到写什么、
 * 站名/站台号为空时怎么退化，这三件事都会"错了也显示得出来"。</p>
 */
import type {Platform} from "./Platform.ts";
import {sidingById, sidingLabel, type Siding} from "./Siding.ts";

/** 目标是什么东西。 */
export type MissionTargetKind = "siding" | "platform" | "unknown";

/** 解析出来的任务目标。 */
export interface MissionTarget {
	readonly kind: MissionTargetKind;
	/** 卡片上写的那一行字。 */
	readonly text: string;
	/** 原始 id（查不到时它就是唯一线索，所以一直留着）。 */
	readonly id: string;
}

/** 站台怎么写：`站 3 · 站台 1（南行）`；站名缺失就只写站台号。 */
export function platformLabel(platform: Platform): string {
	const parts: string[] = [];
	if (platform.stationName !== "") {
		parts.push(`站 ${platform.stationName}`);
	}
	parts.push(platform.platformName === "" ? `站台 ${platform.platformId}` : `站台 ${platform.platformName}`);
	// 方向是**同一个站台的限定语**，所以紧跟在站台号后面（不另起一段，免得读成第三个东西）
	const head = parts.join(" · ");
	return platform.directionLabel === "" ? head : `${head}（${platform.directionLabel}）`;
}

/**
 * 目标 id → 一行字。
 *
 * @param id        引擎给的 `mission.targetSidingId`（**可能是股道，也可能是站台**）
 * @param sidings   股道清单（`/mmtr-trains` 的 `sidings[]`）
 * @param platforms 站台清单（`/mmtr-platforms`）
 */
export function missionTarget(id: string, sidings: readonly Siding[], platforms: readonly Platform[]): MissionTarget {
	if (id === "") {
		return {kind: "unknown", text: "—", id};
	}
	const siding = sidingById(sidings, id);
	if (siding !== null) {
		return {kind: "siding", text: sidingLabel(siding), id};
	}
	const platform = platforms.find(candidate => candidate.platformId === id) ?? null;
	if (platform !== null) {
		return {kind: "platform", text: platformLabel(platform), id};
	}
	return {kind: "unknown", text: `目标 ${id}`, id};
}
