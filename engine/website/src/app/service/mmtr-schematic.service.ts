import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {LIVE_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

/** One square of the 区间图: a lattice cell, with the world's track nodes folded into it. */
export interface MmtrSchematicNode {
	id: number;
	cellX: number;
	cellZ: number;
	/** Drawn centre of the square, in diagram units (the engine computes it). */
	x: number;
	z: number;
	/** How many real track nodes snapped into this square. */
	merged: number;
	/** The block this square belongs to (a lamp key, or 无灯#... when no lamp guards it). */
	block: string;
}

/** One drawn line of the 区间图: every world rail between the same two squares folded into one edge. */
export interface MmtrSchematicRail {
	from: number;
	to: number;
	x1: number;
	z1: number;
	x2: number;
	z2: number;
	/** How many real rails this line stands for. */
	rails: number;
	length: number;
	/** The block owning each travel direction (an index into {@link MmtrSchematicData.blocks}), or -1. */
	forwardBlock: number;
	backwardBlock: number;
}

/** One 水闸区间 as the diagram draws it. */
export interface MmtrSchematicBlock {
	index: number;
	id: string;
	lamp: string;
	open: boolean;
	occupied: boolean;
	length: number;
	/** The diagram edges this block owns. */
	edges: number[];
	/** The squares this block owns. */
	squares: number[];
	/** Human-readable span list (rail short hex + arc window). */
	spans: string[];
}

/** The whole lattice diagram, straight from the engine. */
export interface MmtrSchematicData {
	cellSize: number;
	cellM: number;
	cellWidth: number;
	cellHeight: number;
	originCellX: number;
	originCellZ: number;
	worldWidthM: number;
	worldHeightM: number;
	nodes: MmtrSchematicNode[];
	rails: MmtrSchematicRail[];
	blocks: MmtrSchematicBlock[];
}

const EMPTY: MmtrSchematicData = {
	cellSize: 26, cellM: 16, cellWidth: 1, cellHeight: 1, originCellX: 0, originCellZ: 0, worldWidthM: 0, worldHeightM: 0,
	nodes: [], rails: [], blocks: [],
};

/**
 * 区间图 feed: polls {@code mmtr-schematic}, the block layer folded onto a 1x1 lattice.
 *
 * <p>The lattice, the cell coordinates and every block's edges/squares are computed by the ENGINE; this
 * service stores what it is sent. The console draws a diagram, it does not decide one.</p>
 */
@Injectable({providedIn: "root"})
export class MmtrSchematicService {
	public readonly data = signal<MmtrSchematicData>(EMPTY);
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
		this.httpClient.get<{data: MmtrSchematicData}>(this.mapUrl("mmtr-schematic")).subscribe({
			next: response => {
				this.data.set(response.data ?? EMPTY);
				this.loading.set(false);
				this.schedule();
			},
			error: error => {
				console.error("mmtr-schematic feed failed", error);
				this.loading.set(false);
				this.schedule();
			},
		});
	}

	private schedule() {
		clearTimeout(this.timeoutId);
		this.timeoutId = setTimeout(() => this.poll(), LIVE_REFRESH_INTERVAL_MILLIS) as unknown as number;
	}

	/** How many blocks the diagram shows (the number the layer card prints). */
	public blockCount(): number {
		return this.data().blocks.length;
	}

	/** How many of them hold a train right now. */
	public occupiedCount(): number {
		return this.data().blocks.filter(block => block.occupied).length;
	}

	/** The squares of the lattice actually covered by the network (the drawn figure's size). */
	public squareCount(): number {
		return this.data().nodes.length;
	}
}
