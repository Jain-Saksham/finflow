import { useLayoutEffect, useRef, useState } from 'react';
import type { DailyPoint } from '../api/types';

// Outcomes are states, so they use the reserved status colors (always paired with text labels).
const SERIES = [
  { key: 'succeeded', label: 'Succeeded', color: 'var(--good)' },
  { key: 'failed', label: 'Declined / failed', color: 'var(--critical)' },
  { key: 'pending', label: 'Unresolved (timed out)', color: 'var(--warning)' },
] as const;

const HEIGHT = 220;
const M = { top: 10, right: 8, bottom: 24, left: 30 };
const GAP = 2;
const RADIUS = 4;

function niceStep(max: number): number {
  const raw = Math.max(max, 1) / 4;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= raw) ?? raw;
  return Math.max(1, step);
}

function dayLabel(date: string): string {
  return new Date(`${date}T00:00:00Z`).toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
}

/** Bar with a rounded data-end (top) and a square end anchored to the baseline. */
function topRoundedBar(x: number, y: number, w: number, h: number, r: number): string {
  const rr = Math.min(r, h, w / 2);
  return `M${x},${y + h}V${y + rr}Q${x},${y} ${x + rr},${y}H${x + w - rr}Q${x + w},${y} ${x + w},${y + rr}V${y + h}Z`;
}

export function DailyOutcomesChart({ data }: { data: DailyPoint[] }) {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(600);
  const [hover, setHover] = useState<number | null>(null);
  const [asTable, setAsTable] = useState(false);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(280, entry.contentRect.width)));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const totals = data.map((d) => d.succeeded + d.failed + d.pending);
  const step = niceStep(Math.max(...totals, 0));
  const yMax = Math.max(step, Math.ceil(Math.max(...totals, 1) / step) * step);
  const ticks = Array.from({ length: Math.round(yMax / step) + 1 }, (_, i) => i * step);
  const plotW = width - M.left - M.right;
  const plotH = HEIGHT - M.top - M.bottom;
  const band = plotW / Math.max(data.length, 1);
  const barW = Math.min(28, band * 0.62);
  const y = (v: number) => M.top + plotH - (v / yMax) * plotH;
  const labelEvery = band < 34 ? 2 : 1;

  return (
    <div>
      <div className="row spread" style={{ marginBottom: 10 }}>
        <div className="legend" aria-label="Legend">
          {SERIES.map((s) => (
            <span key={s.key}>
              <i className="swatch" style={{ background: s.color }} />
              {s.label}
            </span>
          ))}
        </div>
        <button className="btn btn-sm" onClick={() => setAsTable((v) => !v)} aria-pressed={asTable}>
          {asTable ? 'Show chart' : 'Show table'}
        </button>
      </div>

      {asTable ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Day (UTC)</th>
                {SERIES.map((s) => <th key={s.key} className="right">{s.label}</th>)}
                <th className="right">Total</th>
              </tr>
            </thead>
            <tbody>
              {data.map((d, i) => (
                <tr key={d.date}>
                  <td>{dayLabel(d.date)}</td>
                  {SERIES.map((s) => <td key={s.key} className="right num">{d[s.key]}</td>)}
                  <td className="right num">{totals[i]}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="chart" ref={ref} onMouseLeave={() => setHover(null)}>
          <svg width={width} height={HEIGHT} role="img" aria-label="Payments per day by outcome, last 14 days">
            {ticks.map((t) => (
              <g key={t}>
                <line x1={M.left} x2={width - M.right} y1={y(t)} y2={y(t)} stroke="var(--grid)" strokeWidth={1} />
                <text x={M.left - 8} y={y(t)} dy="0.32em" textAnchor="end">{t}</text>
              </g>
            ))}
            {data.map((d, i) => {
              const cx = M.left + band * i + band / 2;
              const x = cx - barW / 2;
              let base = 0;
              const segments = SERIES.map((s) => ({ ...s, value: d[s.key] })).filter((s) => s.value > 0);
              return (
                <g key={d.date}>
                  {hover === i && (
                    <rect x={M.left + band * i} y={M.top} width={band} height={plotH} fill="var(--surface-2)" />
                  )}
                  {segments.map((s, si) => {
                    const y0 = y(base);
                    base += s.value;
                    const y1 = y(base);
                    // 2px surface gap between stacked segments; only the top segment gets rounded corners.
                    const h = Math.max(1, y0 - y1 - (si > 0 ? GAP : 0));
                    const top = si === segments.length - 1;
                    return top ? (
                      <path key={s.key} d={topRoundedBar(x, y1, barW, h, RADIUS)} fill={s.color} />
                    ) : (
                      <rect key={s.key} x={x} y={y1} width={barW} height={h} fill={s.color} />
                    );
                  })}
                  {i % labelEvery === 0 && (
                    <text x={cx} y={HEIGHT - 6} textAnchor="middle">{dayLabel(d.date)}</text>
                  )}
                  {/* Hit target spans the full column, larger than the bar itself. */}
                  <rect
                    x={M.left + band * i} y={M.top} width={band} height={plotH} fill="transparent"
                    tabIndex={0} aria-label={`${dayLabel(d.date)}: ${totals[i]} payments`}
                    onMouseEnter={() => setHover(i)} onFocus={() => setHover(i)} onBlur={() => setHover(null)}
                  />
                </g>
              );
            })}
            <line x1={M.left} x2={width - M.right} y1={y(0)} y2={y(0)} stroke="var(--border-strong)" />
          </svg>
          {hover !== null && data[hover] && (
            <div
              className="tooltip"
              style={{
                left: Math.min(Math.max(M.left + band * hover + band / 2 - 80, 0), width - 170),
                top: 0,
              }}
            >
              <div style={{ fontWeight: 650, marginBottom: 4 }}>{dayLabel(data[hover].date)}</div>
              {SERIES.map((s) => (
                <div className="t-row" key={s.key}>
                  <span><i className="swatch" style={{ background: s.color }} />{s.label}</span>
                  <span className="num">{data[hover][s.key]}</span>
                </div>
              ))}
              <div className="t-row" style={{ borderTop: '1px solid var(--border)', marginTop: 4, paddingTop: 4 }}>
                <span>Total</span>
                <span className="num">{totals[hover]}</span>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
