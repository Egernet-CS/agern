import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import type { Run } from '../../api/types';
import { between } from '../../lib/format';
import { useNow } from '../../lib/useNow';
import type { NodeId, NodeView } from './model';

type Box = { x: number; y: number; w: number; h: number };
type Layout = { w: number; h: number; boxes: Record<NodeId, Box>; orient: 'h' | 'v' };

const H: Layout = {
  orient: 'h',
  w: 1400,
  h: 470,
  boxes: {
    issue: { x: 24, y: 168, w: 170, h: 96 },
    'spec-writer': { x: 234, y: 168, w: 170, h: 96 },
    builder: { x: 444, y: 168, w: 170, h: 96 },
    security: { x: 674, y: 24, w: 172, h: 82 },
    ux: { x: 674, y: 122, w: 172, h: 82 },
    'ui-design': { x: 674, y: 220, w: 172, h: 82 },
    code: { x: 674, y: 318, w: 172, h: 82 },
    gate: { x: 880, y: 176, w: 116, h: 80 },
    approver: { x: 1030, y: 168, w: 150, h: 96 },
    merge: { x: 1226, y: 40, w: 166, h: 76 },
    human: { x: 1226, y: 316, w: 166, h: 76 },
  },
};

const V: Layout = {
  orient: 'v',
  w: 380,
  h: 900,
  boxes: {
    issue: { x: 90, y: 12, w: 200, h: 84 },
    'spec-writer': { x: 90, y: 126, w: 200, h: 84 },
    builder: { x: 90, y: 240, w: 200, h: 84 },
    security: { x: 30, y: 362, w: 156, h: 78 },
    ux: { x: 198, y: 362, w: 156, h: 78 },
    'ui-design': { x: 30, y: 452, w: 156, h: 78 },
    code: { x: 198, y: 452, w: 156, h: 78 },
    gate: { x: 120, y: 568, w: 140, h: 64 },
    approver: { x: 90, y: 668, w: 200, h: 84 },
    merge: { x: 30, y: 800, w: 156, h: 76 },
    human: { x: 198, y: 800, w: 156, h: 76 },
  },
};

type EdgeState = 'idle' | 'done' | 'flow' | 'warn';
type Edge = { id: string; d: string; state: EdgeState; label?: string; lx?: number; ly?: number };

const rightMid = (b: Box) => [b.x + b.w, b.y + b.h / 2] as const;
const leftMid = (b: Box) => [b.x, b.y + b.h / 2] as const;
const topMid = (b: Box) => [b.x + b.w / 2, b.y] as const;
const botMid = (b: Box) => [b.x + b.w / 2, b.y + b.h] as const;

function curveH(a: readonly [number, number], b: readonly [number, number]) {
  const dx = Math.max(30, (b[0] - a[0]) / 2);
  return `M${a[0]},${a[1]} C${a[0] + dx},${a[1]} ${b[0] - dx},${b[1]} ${b[0] - 2},${b[1]}`;
}
function curveV(a: readonly [number, number], b: readonly [number, number]) {
  const dy = Math.max(20, (b[1] - a[1]) / 2);
  return `M${a[0]},${a[1]} C${a[0]},${a[1] + dy} ${b[0]},${b[1] - dy} ${b[0]},${b[1] - 2}`;
}

function edgeState(target: NodeView, source?: NodeView): EdgeState {
  if (target.state === 'running') return target.tone === 'escalate' ? 'warn' : 'flow';
  if (target.state === 'done' || target.state === 'failed' || target.state === 'stale') return 'done';
  if (source && source.state === 'done' && target.state === 'pending') return 'idle';
  return 'idle';
}

function buildEdges(L: Layout, n: Record<NodeId, NodeView>, run: Run): Edge[] {
  const B = L.boxes;
  const c = L.orient === 'h' ? curveH : curveV;
  const out = L.orient === 'h' ? rightMid : botMid;
  const inn = L.orient === 'h' ? leftMid : topMid;
  const edges: Edge[] = [
    { id: 'issue-spec', d: c(out(B.issue), inn(B['spec-writer'])), state: edgeState(n['spec-writer']) },
    { id: 'spec-builder', d: c(out(B['spec-writer']), inn(B.builder)), state: edgeState(n.builder) },
  ];
  for (const r of ['security', 'ux', 'ui-design', 'code'] as const) {
    edges.push({ id: `b-${r}`, d: c(out(B.builder), inn(B[r])), state: edgeState(n[r]) });
    edges.push({ id: `${r}-gate`, d: c(out(B[r]), inn(B.gate)), state: n[r].state === 'done' ? (n.gate.state === 'done' ? 'done' : 'flow') : 'idle' });
  }
  edges.push({ id: 'gate-approver', d: c(out(B.gate), inn(B.approver)), state: edgeState(n.approver) });
  if (L.orient === 'h') {
    const a = B.approver;
    const m = B.merge;
    const h = B.human;
    const from = rightMid(a);
    const lx = (a.x + a.w + m.x) / 2 - 4;
    edges.push({ id: 'approver-merge', d: curveH(from, leftMid(m)), state: edgeState(n.merge), label: 'APPROVE', lx, ly: a.y - 14 });
    edges.push({ id: 'approver-human', d: curveH(from, leftMid(h)), state: n.human.state === 'running' ? 'warn' : edgeState(n.human), label: 'ESCALATE', lx, ly: a.y + a.h + 24 });
  } else {
    edges.push({ id: 'approver-merge', d: c(botMid(B.approver), topMid(B.merge)), state: edgeState(n.merge), label: 'APPROVE', lx: B.merge.x + 34, ly: B.merge.y - 26 });
    edges.push({ id: 'approver-human', d: c(botMid(B.approver), topMid(B.human)), state: n.human.state === 'running' ? 'warn' : edgeState(n.human), label: 'ESCALATE', lx: B.human.x + 120, ly: B.human.y - 26 });
  }
  // loop-back: any CHANGES → resume builder
  const looped = run.stages.some((s) => s.role === 'builder' && s.round > 0);
  const loopActive = n.builder.state === 'running' && (n.builder.round ?? 0) > 0;
  const loopLabel = `any CHANGES → resume builder · max ${run.maxRounds} rounds`;
  if (L.orient === 'h') {
    const g = B.gate;
    const b = B.builder;
    const yLow = 436;
    edges.push({ id: 'loop', d: `M${g.x + g.w / 2},${g.y + g.h} L${g.x + g.w / 2},${yLow - 14} Q${g.x + g.w / 2},${yLow} ${g.x + g.w / 2 - 14},${yLow} L${b.x + b.w / 2 + 14},${yLow} Q${b.x + b.w / 2},${yLow} ${b.x + b.w / 2},${yLow - 14} L${b.x + b.w / 2},${b.y + b.h + 2}`, state: loopActive ? 'flow' : looped ? 'done' : 'idle', label: loopLabel, lx: (g.x + b.x + b.w) / 2 + 30, ly: yLow + 18 });
  } else {
    const g = B.gate;
    const b = B.builder;
    const xL = 10;
    edges.push({ id: 'loop', d: `M${g.x},${g.y + g.h / 2} L${xL + 12},${g.y + g.h / 2} Q${xL},${g.y + g.h / 2} ${xL},${g.y + g.h / 2 - 12} L${xL},${b.y + b.h / 2 + 12} Q${xL},${b.y + b.h / 2} ${xL + 12},${b.y + b.h / 2} L${b.x - 2},${b.y + b.h / 2}`, state: loopActive ? 'flow' : looped ? 'done' : 'idle', label: '↺ any CHANGES', lx: 16, ly: g.y + g.h / 2 + 22 });
  }
  return edges;
}

const STATE_ICON: Record<string, string> = { pending: '○', running: '●', done: '✓', failed: '✗', skipped: '–', stale: '◌' };
const STATE_TEXT: Record<string, string> = { pending: 'pending', running: 'running', done: 'done', failed: 'failed', skipped: 'skipped', stale: 'waiting' };

function wrap2(s: string, n: number): string[] {
  if (s.length <= n) return [s];
  const cut = s.lastIndexOf(' ', n);
  const first = s.slice(0, cut > 8 ? cut : n);
  return [first, trunc(s.slice(first.length).trim(), n)];
}

function trunc(s: string, n: number) {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}

function Node({ n, box, selected, onSelect, selectable, now, compact }: { n: NodeView; box: Box; selected: boolean; onSelect: () => void; selectable: boolean; now: number; compact: boolean }) {
  const { x, y, w, h } = box;
  const tone = n.tone ?? '';
  const cls = `pn pn--${n.state} ${tone ? `pn--tone-${tone}` : ''} ${selected ? 'is-selected' : ''} ${selectable ? 'is-selectable' : ''} pn--${n.id}`;
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onSelect();
    }
  };
  const isOutcome = n.id === 'merge' || n.id === 'human';
  const isGate = n.id === 'gate';
  const timeText = n.state === 'running' && n.startedAt ? between(n.startedAt, null, now) : n.endedAt && n.startedAt ? between(n.startedAt, n.endedAt) : null;
  const label = `${n.title}: ${STATE_TEXT[n.state]}${n.verdict ? `, verdict ${n.verdict}` : ''}${n.model ? `, model ${n.model}` : ''}`;
  const pad = 14;
  const maxChars = Math.floor((w - pad * 2) / 7.6);
  return (
    <g
      className={cls}
      transform={`translate(${x},${y})`}
      role={selectable ? 'button' : 'img'}
      tabIndex={selectable ? 0 : -1}
      aria-label={label}
      aria-pressed={selectable ? selected : undefined}
      onClick={selectable ? onSelect : undefined}
      onKeyDown={selectable ? onKey : undefined}
    >
      <title>{label}</title>
      <rect className="pn-glow" x={-6} y={-6} width={w + 12} height={h + 12} rx={16} />
      <rect className="pn-box" width={w} height={h} rx={isGate ? h / 2 : 11} />
      {n.state === 'running' && !isGate && !isOutcome && (
        <rect className="pn-scan" x={1} y={h - 4} width={w - 2} height={3} rx={1.5} clipPath={undefined} />
      )}
      {isGate ? (
        <>
          <text className="pn-role" x={w / 2} y={h / 2 - 8} textAnchor="middle">wait for all 4</text>
          <text className="pn-status" x={w / 2} y={h / 2 + 12} textAnchor="middle">
            {n.verdict ?? n.sub}
          </text>
        </>
      ) : (
        <>
          <text className="pn-role" x={pad} y={22}>
            {n.id === 'issue' ? (n.note ?? 'issue').toUpperCase() : n.title.toUpperCase()}
          </text>
          {n.model && (
            <text className="pn-model" x={w - pad} y={22} textAnchor="end">
              {n.model}
            </text>
          )}
          {n.id === 'issue' ? (
            <>
              <text className="pn-main" x={pad} y={46}>{n.title}</text>
              {wrap2(n.sub ?? '', maxChars).map((line, i) => (
                <text key={i} className="pn-sub" x={pad} y={68 + i * 15}>{line}</text>
              ))}
            </>
          ) : isOutcome ? (
            <>
              <text className="pn-status" x={pad} y={48}>
                <tspan className="pn-icon">{STATE_ICON[n.state]}</tspan> {n.verdict ?? STATE_TEXT[n.state]}
              </text>
              {/* The needs-human states ("change not reviewed") are squeezed to fit rather than cut off. */}
              {n.sub && (n.sub.length > maxChars && n.sub.length <= maxChars + 5 ? (
                <text className="pn-sub" x={pad} y={67} textLength={w - pad * 2} lengthAdjust="spacingAndGlyphs">{n.sub}</text>
              ) : (
                <text className="pn-sub" x={pad} y={67}>{trunc(n.sub, maxChars)}</text>
              ))}
            </>
          ) : (
            <>
              <text className="pn-status" x={pad} y={compact ? 46 : 50}>
                <tspan className="pn-icon">{STATE_ICON[n.state]}</tspan> {STATE_TEXT[n.state]}
                {timeText && <tspan className="pn-time"> {timeText}</tspan>}
              </text>
              {n.note && (
                <text className="pn-sub" x={pad} y={compact ? 66 : 72}>{trunc(n.note, maxChars)}</text>
              )}
              {n.verdict && (
                <g className={`pn-verdict pn-verdict--${tone}`} transform={`translate(${w - pad},${h - (compact ? 22 : 26)})`}>
                  <rect x={-(n.verdict.length * 7.4 + 14)} y={-2} width={n.verdict.length * 7.4 + 14} height={18} rx={9} />
                  <text x={-7} y={11} textAnchor="end">{n.verdict}</text>
                </g>
              )}
            </>
          )}
        </>
      )}
    </g>
  );
}

export function PipelineDiagram({ run, nodes, selected, onSelect }: { run: Run; nodes: Record<NodeId, NodeView>; selected: NodeId | null; onSelect: (id: NodeId) => void }) {
  const wrap = useRef<HTMLDivElement>(null);
  const [narrow, setNarrow] = useState(false);
  useEffect(() => {
    const el = wrap.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setNarrow(e.contentRect.width < 720));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const anyRunning = Object.values(nodes).some((n) => n.state === 'running');
  const now = useNow(1000, anyRunning);
  const L = narrow ? V : H;
  const edges = buildEdges(L, nodes, run);
  const order: NodeId[] = ['issue', 'spec-writer', 'builder', 'security', 'ux', 'ui-design', 'code', 'gate', 'approver', 'merge', 'human'];
  // The needs-human node opens the run's conversation with its assistant (while waiting, or once one exists).
  const selectable = (id: NodeId) =>
    !!nodes[id].stage || id === 'issue' || (id === 'human' && (run.status === 'needs_human' || !!run.assistantSessionId));

  return (
    <div className={`pipeline ${narrow ? 'pipeline--v' : ''}`} ref={wrap}>
      <svg viewBox={`0 0 ${L.w} ${L.h}`} role="group" aria-label="Pipeline diagram" preserveAspectRatio="xMidYMin meet">
        <defs>
          <pattern id="pgrid" width="24" height="24" patternUnits="userSpaceOnUse">
            <circle cx="1" cy="1" r="1" className="pgrid-dot" />
          </pattern>
          {(['idle', 'done', 'flow', 'warn'] as const).map((s) => (
            <marker key={s} id={`arrow-${s}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" className={`parrow parrow--${s}`} />
            </marker>
          ))}
          <filter id="psoft" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="6" />
          </filter>
        </defs>
        <rect width={L.w} height={L.h} fill="url(#pgrid)" className="pgrid" />
        <g className="pedges">
          {edges.map((e) => (
            <g key={e.id} className={`pe pe--${e.state} ${e.id === 'loop' ? 'pe--loop' : ''}`}>
              <path d={e.d} className="pe-track" />
              {e.state === 'flow' || e.state === 'warn' ? <path d={e.d} className="pe-glow" filter="url(#psoft)" /> : null}
              <path d={e.d} className="pe-line" markerEnd={`url(#arrow-${e.state})`} />
              {e.label && (
                <text className="pe-label" x={e.lx} y={e.ly} textAnchor={L.orient === 'h' && e.id.startsWith('approver-') ? 'end' : 'start'}>
                  {e.label}
                </text>
              )}
            </g>
          ))}
        </g>
        {L.orient === 'h' && (
          <text className="pe-label pe-label--round" x={L.boxes.builder.x + L.boxes.builder.w / 2 + 16} y={418}>
            {run.round === 0 ? 'build' : `fix round ${run.round} of ${run.maxRounds}`}
          </text>
        )}
        {order.map((id) => (
          <Node key={id} n={nodes[id]} box={L.boxes[id]} selected={selected === id} selectable={selectable(id)} onSelect={() => onSelect(id)} now={now} compact={narrow} />
        ))}
      </svg>
    </div>
  );
}
