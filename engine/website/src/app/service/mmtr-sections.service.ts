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

/** One piece of the server-side 区间 partition: a slice of one rail owned by exactly one block. */
export interface MmtrSectionPiece {
	hex: string;
	from: number;
	to: number;
	/** The block (lamp key) this piece belongs to; empty on a piece that starts at a plain rail end. */
	section: string;
	/** The lamp standing at this piece's start - the boundary a driver sees. */
	lamp: string;
	/** Sampled world points along the piece, flattened [x, z, x, z, ...] - drawn as-is. */
	points: number[];
}

/**
 * 区间图层 feed (闭塞区间 v2): polls {@code mmtr-sections}.
 *
 * <p>The ENGINE decides everything here - which pieces the line is divided into, and which block each
 * piece belongs to. This service only stores and counts what it is sent: the console must not re-derive
 * division (the whole point of 信号 = 进路 × 闭塞 is that the engine is the single source of truth).</p>
 */
@Injectable({providedIn: "root"})
export class MmtrSectionsService {
	/** Each lamp's own block (what one lamp protects, walked lamp to lamp) - overlaps by design. */
	public readonly sections = signal<MmtrSectionEntry[]>([]);
	/** The line cut AT the lamps: one owner per piece, no overlaps - this is what the map draws. */
	public readonly pieces = signal<MmtrSectionPiece[]>([]);
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
		this.httpClient.get<{data: {sections: MmtrSectionEntry[], pieces: MmtrSectionPiece[], railCount: number}}>(this.mapUrl("mmtr-sections")).subscribe({
			next: response => {
				this.sections.set(response.data.sections ?? []);
				this.pieces.set(response.data.pieces ?? []);
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

	/** How many rails carry more than one span of the same section (a lamp split a rail). */
	public splitRailCount(): number {
		const perRail = new Map<string, number>();
		for (const section of this.sections()) {
			for (const span of section.spans) {
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

	/** How many sections are occupied right now (their lamp reads danger for that reason). */
	public occupiedCount(): number {
		return this.sections().filter(section => section.occupied).length;
	}
}
