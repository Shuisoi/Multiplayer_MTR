import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {LIVE_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

/** One rail slice of a section, in the rail's own arc space (ordered-position-1 metres). */
export interface MmtrSectionSpan {
	hex: string;
	from: number;
	to: number;
	/** Sampled world points along the slice, flattened [x, z, x, z, ...] - drawn as-is. */
	points: number[];
}

/**
 * 闭塞区间 v2: one directional block section - the stretch ONE lamp protects, walked lamp to lamp.
 * `id` / `exitSignal` / `next` are the lamp keys that begin and end it (empty exit = the walk ran out).
 */
export interface MmtrSectionEntry {
	id: string;
	exitSignal: string;
	next: string;
	aspect: string;
	occupied: boolean;
	length: number;
	spans: MmtrSectionSpan[];
}

/**
 * 水闸区间: one cell of the BLOCK layer - a stretch of line bounded by the lamps that face into it.
 *
 * <p>The cell is the engine's own unit of "一区段一车", so it can span several rails (the node between
 * two rails belongs to the TRACK layer, not this one) or be a piece of a single rail when a lamp stands
 * mid-rail. A cell with no `lamp` is one nobody guards - the end of the line, a plain siding.</p>
 */
export interface MmtrBlockEntry {
	/** The lamp that opens this block; for an unguarded block, a stable synthetic id. */
	id: string;
	/** The lamp that opens this block (empty = nobody guards it, so it has no light to read). */
	lamp: string;
	/** True when the walk ran out (dead end) instead of closing on the next lamp. */
	open: boolean;
	occupied: boolean;
	/** What the block's own entry lamp shows right now (empty for an unguarded block). */
	aspect: string;
	length: number;
	spans: MmtrSectionSpan[];
}

/**
 * 区间图层 feed (闭塞区间 v2): polls {@code mmtr-sections}.
 *
 * <p>The ENGINE decides everything here - which cells the line is divided into, what each spans, and
 * what its lamp reads. This service only stores and counts what it is sent: the console must not
 * re-derive division (the whole point is that the engine is the single source of truth, and the web page
 * cannot make judgements of its own).</p>
 */
@Injectable({providedIn: "root"})
export class MmtrSectionsService {
	/** Each lamp's own section (what one lamp protects, walked lamp to lamp) - overlaps by design. */
	public readonly sections = signal<MmtrSectionEntry[]>([]);
	/** The BLOCK layer: 水闸区间, the cells the engine actually holds trains with - this is what the map draws. */
	public readonly blocks = signal<MmtrBlockEntry[]>([]);
	public readonly loading = signal(true);

	private readonly httpClient = inject(HttpClient);
	private readonly destroyRef = inject(DestroyRef);
	private timeoutId = 0;

	constructor() {
		this.poll();
		this.destroyRef.onDestroy(() => clearTimeout(this.timeoutId));
	}

	private mapUrl(path: string) {
		return `${document.location.origin}${document.location.pathname}mtr/api/map/${path}`;
	}

	private poll() {
		this.httpClient.get<{data: {sections: MmtrSectionEntry[], blocks: MmtrBlockEntry[], railCount: number}}>(this.mapUrl("mmtr-sections")).subscribe({
			next: response => {
				this.sections.set(response.data.sections ?? []);
				this.blocks.set(response.data.blocks ?? []);
				this.loading.set(false);
				this.schedule();
			},
			error: error => {
				console.error("mmtr-sections feed failed", error);
				this.loading.set(false);
				this.schedule();
			},
		});
	}

	private schedule() {
		clearTimeout(this.timeoutId);
		this.timeoutId = setTimeout(() => this.poll(), LIVE_REFRESH_INTERVAL_MILLIS) as unknown as number;
	}

	/** How many rails carry more than one block piece (a lamp split a rail mid-way). */
	public splitRailCount(): number {
		const perRail = new Map<string, number>();
		for (const block of this.blocks()) {
			for (const span of block.spans) {
				perRail.set(span.hex, (perRail.get(span.hex) ?? 0) + 1);
			}
		}
		let count = 0;
		perRail.forEach(value => {
			if (value > 1) {
				count++;
			}
		});
		return count;
	}

	/** How many blocks are occupied right now - 一区段一车. */
	public occupiedCount(): number {
		return this.blocks().filter(block => block.occupied).length;
	}

	/** How many blocks the engine's division has (the number the layer switch shows). */
	public blockCount(): number {
		return this.blocks().length;
	}
}
