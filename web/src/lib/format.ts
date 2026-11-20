export function timeAgo(isoTs: string | null | undefined, now = Date.now()): string {
  if (!isoTs) return '—';
  const s = Math.max(0, Math.round((now - Date.parse(isoTs)) / 1000));
  if (s < 10) return 'just now';
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 30) return `${d}d ago`;
  return new Date(isoTs).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function duration(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${String(s % 60).padStart(2, '0')}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${String(m % 60).padStart(2, '0')}m`;
}

export function between(a: string | null, b: string | null, now = Date.now()): string {
  if (!a) return '—';
  return duration((b ? Date.parse(b) : now) - Date.parse(a));
}

export const cost = (usd: number | null | undefined) =>
  usd == null ? '—' : usd < 0.01 ? `$${usd.toFixed(4)}` : `$${usd.toFixed(2)}`;

export const shortId = (id: string) => id.slice(0, 8);

/** "claude-opus-4-5" → "opus 4.5"; aliases pass through. */
export function modelLabel(m: string | null | undefined): string {
  if (!m) return '—';
  const x = /claude-(\w+)-(\d+)(?:-(\d+))?/.exec(m);
  if (!x) return m;
  return `${x[1]} ${x[2]}${x[3] && x[3].length < 3 ? `.${x[3]}` : ''}`;
}

/** 1536 → "1.5 KB" (binary units, like the server's messages). */
export function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

/** Date and time in the browser's locale and time zone, e.g. "20 Nov 2026, 22:00". */
export function dateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** A `Date` as the value of an `<input type="datetime-local">` in local time ("2026-11-20T22:00"). */
export function toDateTimeLocal(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}
