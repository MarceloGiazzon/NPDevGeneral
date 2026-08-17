import type { ErRelationship, ErTable } from "./semanticGraph";

export type ErLayoutColumn = {
  name: string;
  type: string;
  isPrimaryKey: boolean;
  isForeignKey: boolean;
  required: boolean;
  y: number;
};

export type ErLayoutTable = {
  id: string;
  name: string;
  x: number;
  y: number;
  width: number;
  height: number;
  columns: ErLayoutColumn[];
};

export type ErLayoutConnector = {
  id: string;
  path: string;
  manyToMany: boolean;
  startX: number;
  startY: number;
  endX: number;
  endY: number;
  /** Horizontal direction the line travels AT each end, for orienting the crow's-foot/tick marker. */
  startDirection: 1 | -1;
  endDirection: 1 | -1;
  // The relationship this connector renders -- carried through (not just baked into the geometry
  // above) so a consumer can look up "which connectors touch this table" and recompute their
  // geometry via recomputeConnector() after that table's position changes (drag-and-drop).
  fromTable: string;
  fromColumn: string;
  toTable: string;
  toColumn: string;
};

export type ErDiagramLayout = {
  tables: ErLayoutTable[];
  connectors: ErLayoutConnector[];
  width: number;
  height: number;
};

export const ER_HEADER_HEIGHT = 32;
export const ER_ROW_HEIGHT = 22;
export const ER_TABLE_WIDTH = 220;

const HEADER_HEIGHT = ER_HEADER_HEIGHT;
const ROW_HEIGHT = ER_ROW_HEIGHT;
const TABLE_WIDTH = ER_TABLE_WIDTH;
const LAYER_GAP_X = 100;
const TABLE_GAP_Y = 32;
const PADDING = 24;

/**
 * Pure connector geometry for one relationship, given its two tables' CURRENT positions. Used both
 * at initial layout time and to redraw a connector live while its table is being dragged -- same
 * function either way, so a dragged diagram's lines can never draw differently than a fresh layout
 * would for the same table positions.
 */
export function computeConnector(from: ErLayoutTable, to: ErLayoutTable, relationship: ErRelationship): ErLayoutConnector {
  const fromColumn = from.columns.find((column) => column.name === relationship.fromColumn);
  const toColumn = to.columns.find((column) => column.name === relationship.toColumn) ?? to.columns[0];
  const fromY = from.y + (fromColumn ? fromColumn.y : ER_HEADER_HEIGHT / 2);
  const toY = to.y + (toColumn ? toColumn.y : ER_HEADER_HEIGHT / 2);
  const goingRight = to.x >= from.x;
  const startX = goingRight ? from.x + from.width : from.x;
  const endX = goingRight ? to.x : to.x + to.width;
  const midX = (startX + endX) / 2;
  return {
    id: relationship.id,
    path: `M ${startX} ${fromY} C ${midX} ${fromY}, ${midX} ${toY}, ${endX} ${toY}`,
    manyToMany: relationship.manyToMany,
    startX,
    startY: fromY,
    endX,
    endY: toY,
    // The bezier's control points share each endpoint's Y, so the tangent at both ends is
    // purely horizontal -- direction is just "which way the line travels leaving that point".
    startDirection: goingRight ? 1 : -1,
    endDirection: goingRight ? 1 : -1,
    fromTable: relationship.fromTable,
    fromColumn: relationship.fromColumn,
    toTable: relationship.toTable,
    toColumn: relationship.toColumn
  };
}

/**
 * Layered auto-layout: a table's layer is the longest chain of outgoing reference fields it owns
 * (a table with no outgoing references is a layer-0 "leaf" target; a table pointing at one is one
 * layer further out). Hand-rolled rather than pulling in a layout library -- the model is small
 * (tens of concepts, not thousands) and a longest-path layering reads cleanly for an ER diagram
 * without adding a new dependency for it.
 */
export function computeErLayout(tables: ErTable[], relationships: ErRelationship[]): ErDiagramLayout {
  const byId = new Map(tables.map((table) => [table.id, table]));
  const outgoing = new Map<string, string[]>();
  tables.forEach((table) => outgoing.set(table.id, []));
  relationships.forEach((relationship) => {
    if (byId.has(relationship.toTable) && relationship.toTable !== relationship.fromTable) {
      outgoing.get(relationship.fromTable)?.push(relationship.toTable);
    }
  });

  const layerOf = new Map<string, number>();
  const layerFor = (id: string, visiting: Set<string>): number => {
    const cached = layerOf.get(id);
    if (cached !== undefined) {
      return cached;
    }
    if (visiting.has(id)) {
      return 0; // cycle guard: break the recursion, treat as a leaf at this point
    }
    visiting.add(id);
    const targets = outgoing.get(id) ?? [];
    const layer = targets.length === 0 ? 0 : 1 + Math.max(...targets.map((target) => layerFor(target, visiting)));
    visiting.delete(id);
    layerOf.set(id, layer);
    return layer;
  };
  tables.forEach((table) => layerFor(table.id, new Set()));

  const layers = new Map<number, ErTable[]>();
  tables.forEach((table) => {
    const layer = layerOf.get(table.id) ?? 0;
    const bucket = layers.get(layer) ?? [];
    bucket.push(table);
    layers.set(layer, bucket);
  });
  const maxLayer = Math.max(0, ...Array.from(layers.keys()));

  const layoutTables: ErLayoutTable[] = [];
  const byLayoutId = new Map<string, ErLayoutTable>();
  for (let layer = 0; layer <= maxLayer; layer += 1) {
    const entries = layers.get(layer) ?? [];
    let cursorY = PADDING;
    const x = PADDING + layer * (TABLE_WIDTH + LAYER_GAP_X);
    for (const table of entries) {
      const height = HEADER_HEIGHT + Math.max(1, table.columns.length) * ROW_HEIGHT;
      const layoutTable: ErLayoutTable = {
        id: table.id,
        name: table.name,
        x,
        y: cursorY,
        width: TABLE_WIDTH,
        height,
        columns: table.columns.map((column, index) => ({
          ...column,
          y: HEADER_HEIGHT + index * ROW_HEIGHT + ROW_HEIGHT / 2
        }))
      };
      layoutTables.push(layoutTable);
      byLayoutId.set(table.id, layoutTable);
      cursorY += height + TABLE_GAP_Y;
    }
  }

  const connectors: ErLayoutConnector[] = [];
  for (const relationship of relationships) {
    const from = byLayoutId.get(relationship.fromTable);
    const to = byLayoutId.get(relationship.toTable);
    if (!from || !to) {
      continue;
    }
    connectors.push(computeConnector(from, to, relationship));
  }

  const width = PADDING * 2 + (maxLayer + 1) * TABLE_WIDTH + maxLayer * LAYER_GAP_X;
  const tallestLayerHeight = Math.max(
    1,
    ...Array.from(layers.values()).map((entries) =>
      entries.reduce((sum, table) => sum + HEADER_HEIGHT + Math.max(1, table.columns.length) * ROW_HEIGHT + TABLE_GAP_Y, 0)
    )
  );
  const height = PADDING * 2 + tallestLayerHeight;

  return { tables: layoutTables, connectors, width, height };
}
