import React from "react";
import type { ErRelationship, ErTable } from "./semanticGraph";
import { computeConnector, computeErLayout, ER_HEADER_HEIGHT, type ErLayoutConnector, type ErLayoutTable } from "./erDiagramLayout";

type ErDiagramViewProps = {
  tables: ErTable[];
  relationships: ErRelationship[];
};

type TablePosition = { x: number; y: number };

const LINE_COLOR = "#7c93b1";
const HEADER_FILL = "#163f86";
const BORDER_COLOR = "#d5e1ef";
const TEXT_COLOR = "#0e1a2b";
const MUTED_TEXT = "#5f7388";
// Extra room beyond the auto-layout's own bounds so a dragged table has somewhere to go.
const DRAG_MARGIN = 400;

export default function ErDiagramView({ tables, relationships }: ErDiagramViewProps): JSX.Element {
  const layout = React.useMemo(() => computeErLayout(tables, relationships), [tables, relationships]);

  // Drag state lives outside the layout: it overrides individual tables' positions without
  // recomputing the auto-layout (which would just snap everything back). Reset whenever the
  // underlying model changes (a fresh layout means the graph itself changed, so stale drag
  // offsets from a different set of tables/relationships would be meaningless).
  const [positions, setPositions] = React.useState<Record<string, TablePosition>>({});
  React.useEffect(() => {
    setPositions({});
  }, [layout]);

  const positionedTables = React.useMemo<ErLayoutTable[]>(
    () => layout.tables.map((table) => ({ ...table, ...(positions[table.id] ?? {}) })),
    [layout.tables, positions]
  );
  const tableById = React.useMemo(() => new Map(positionedTables.map((table) => [table.id, table])), [positionedTables]);

  const connectors = React.useMemo<ErLayoutConnector[]>(() => {
    const result: ErLayoutConnector[] = [];
    for (const relationship of relationships) {
      const from = tableById.get(relationship.fromTable);
      const to = tableById.get(relationship.toTable);
      if (from && to) {
        result.push(computeConnector(from, to, relationship));
      }
    }
    return result;
  }, [relationships, tableById]);

  const dragRef = React.useRef<{ tableId: string; pointerId: number; startClientX: number; startClientY: number; startX: number; startY: number } | null>(null);

  const handlePointerDown = React.useCallback(
    (event: React.PointerEvent<SVGGElement>, table: ErLayoutTable) => {
      event.currentTarget.setPointerCapture(event.pointerId);
      dragRef.current = {
        tableId: table.id,
        pointerId: event.pointerId,
        startClientX: event.clientX,
        startClientY: event.clientY,
        startX: table.x,
        startY: table.y
      };
    },
    []
  );
  const handlePointerMove = React.useCallback((event: React.PointerEvent<SVGGElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }
    const nextX = drag.startX + (event.clientX - drag.startClientX);
    const nextY = drag.startY + (event.clientY - drag.startClientY);
    setPositions((prev) => ({ ...prev, [drag.tableId]: { x: nextX, y: nextY } }));
  }, []);
  const handlePointerUp = React.useCallback((event: React.PointerEvent<SVGGElement>) => {
    if (dragRef.current?.pointerId === event.pointerId) {
      dragRef.current = null;
    }
  }, []);

  if (layout.tables.length === 0) {
    return <p className="authoring-preview-card__empty">No concepts to diagram yet.</p>;
  }

  return (
    <div className="authoring-er-diagram">
      <div className="authoring-er-diagram__scroll">
        <svg
          width={layout.width + DRAG_MARGIN}
          height={layout.height + DRAG_MARGIN}
          viewBox={`0 0 ${layout.width + DRAG_MARGIN} ${layout.height + DRAG_MARGIN}`}
          role="img"
          aria-label="Concept relationship diagram, tables are draggable"
        >
          <g>
            {connectors.map((connector) => (
              <ErConnector key={connector.id} connector={connector} />
            ))}
          </g>
          <g>
            {positionedTables.map((table) => (
              <ErTableBox
                key={table.id}
                table={table}
                onPointerDown={(event) => handlePointerDown(event, table)}
                onPointerMove={handlePointerMove}
                onPointerUp={handlePointerUp}
              />
            ))}
          </g>
        </svg>
      </div>
      <ErDiagramLegend />
    </div>
  );
}

function ErTableBox({
  table,
  onPointerDown,
  onPointerMove,
  onPointerUp
}: {
  table: ErLayoutTable;
  onPointerDown: (event: React.PointerEvent<SVGGElement>) => void;
  onPointerMove: (event: React.PointerEvent<SVGGElement>) => void;
  onPointerUp: (event: React.PointerEvent<SVGGElement>) => void;
}): JSX.Element {
  return (
    <g
      transform={`translate(${table.x}, ${table.y})`}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
      style={{ cursor: "grab", touchAction: "none" }}
    >
      <rect width={table.width} height={table.height} rx={10} fill="#ffffff" stroke={BORDER_COLOR} strokeWidth={1.5} />
      <path
        d={`M 0 10 A 10 10 0 0 1 10 0 L ${table.width - 10} 0 A 10 10 0 0 1 ${table.width} 10 L ${table.width} ${ER_HEADER_HEIGHT} L 0 ${ER_HEADER_HEIGHT} Z`}
        fill={HEADER_FILL}
      />
      <text x={12} y={ER_HEADER_HEIGHT / 2 + 4} fill="#ffffff" fontWeight={700} fontSize={13} style={{ userSelect: "none" }}>
        {table.name}
      </text>
      {table.columns.map((column) => (
        <g key={column.name} transform={`translate(0, ${column.y - 11})`}>
          {column.isPrimaryKey ? (
            <circle cx={14} cy={11} r={3.5} fill={HEADER_FILL} />
          ) : column.isForeignKey ? (
            <circle cx={14} cy={11} r={3.5} fill="none" stroke={MUTED_TEXT} strokeWidth={1.3} />
          ) : null}
          <text x={26} y={15} fontSize={12} fontWeight={column.isPrimaryKey ? 700 : 400} fill={TEXT_COLOR} style={{ userSelect: "none" }}>
            {column.name}
          </text>
          <text x={table.width - 10} y={15} fontSize={11} fill={MUTED_TEXT} textAnchor="end" style={{ userSelect: "none" }}>
            {column.type}
            {column.required ? "" : "?"}
          </text>
        </g>
      ))}
    </g>
  );
}

function ErConnector({ connector }: { connector: ErLayoutConnector }): JSX.Element {
  return (
    <g>
      <path d={connector.path} fill="none" stroke={LINE_COLOR} strokeWidth={1.5} />
      <CardinalityMarker x={connector.startX} y={connector.startY} direction={connector.startDirection} kind="many" />
      <CardinalityMarker
        x={connector.endX}
        y={connector.endY}
        direction={connector.endDirection}
        kind={connector.manyToMany ? "many" : "one"}
      />
    </g>
  );
}

/** Crow's-foot notation: a fork means "many", a double perpendicular tick means "one". */
function CardinalityMarker({
  x,
  y,
  direction,
  kind
}: {
  x: number;
  y: number;
  direction: 1 | -1;
  kind: "one" | "many";
}): JSX.Element {
  if (kind === "one") {
    const tick1X = x - direction * 6;
    const tick2X = x - direction * 11;
    return (
      <g stroke={LINE_COLOR} strokeWidth={1.5}>
        <line x1={tick1X} y1={y - 6} x2={tick1X} y2={y + 6} />
        <line x1={tick2X} y1={y - 6} x2={tick2X} y2={y + 6} />
      </g>
    );
  }
  const baseX = x - direction * 14;
  return (
    <g stroke={LINE_COLOR} strokeWidth={1.5} fill="none">
      <line x1={baseX} y1={y - 7} x2={x} y2={y} />
      <line x1={baseX} y1={y} x2={x} y2={y} />
      <line x1={baseX} y1={y + 7} x2={x} y2={y} />
    </g>
  );
}

function ErDiagramLegend(): JSX.Element {
  return (
    <div className="authoring-er-diagram__legend">
      <span>
        <svg width={12} height={12} aria-hidden="true">
          <circle cx={6} cy={6} r={3.5} fill={HEADER_FILL} />
        </svg>
        Primary key
      </span>
      <span>
        <svg width={12} height={12} aria-hidden="true">
          <circle cx={6} cy={6} r={3.5} fill="none" stroke={MUTED_TEXT} strokeWidth={1.3} />
        </svg>
        Foreign key
      </span>
      <span>
        <svg width={20} height={12} aria-hidden="true">
          <g stroke={LINE_COLOR} strokeWidth={1.5} fill="none">
            <line x1={2} y1={2} x2={16} y2={6} />
            <line x1={2} y1={6} x2={16} y2={6} />
            <line x1={2} y1={10} x2={16} y2={6} />
          </g>
        </svg>
        Many
      </span>
      <span>
        <svg width={20} height={12} aria-hidden="true">
          <g stroke={LINE_COLOR} strokeWidth={1.5}>
            <line x1={8} y1={2} x2={8} y2={10} />
            <line x1={13} y1={2} x2={13} y2={10} />
          </g>
        </svg>
        One
      </span>
      <span>Drag any table to reposition it</span>
    </div>
  );
}
