// A small line diff (longest common subsequence) for sandbox definitions: at most 64 KiB each, so a plain dynamic
// programme over the lines that differ is fast enough; beyond a size cap it falls back to "all removed, all added".

export type DiffLine = { op: 'same' | 'add' | 'del'; text: string };

/** Above this many cells (lines × lines of the differing middle) the diff is not computed line by line. */
const MAX_CELLS = 4_000_000;

const lines = (s: string) => (s === '' ? [] : s.replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n'));

/** The lines of `after` against `before`: unchanged, removed (`del`) and added (`add`), in file order. */
export function lineDiff(before: string, after: string): DiffLine[] {
  const a = lines(before);
  const b = lines(after);
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--;
    endB--;
  }
  const out: DiffLine[] = a.slice(0, start).map((text) => ({ op: 'same', text }));
  const n = endA - start;
  const m = endB - start;
  if (n * m > MAX_CELLS) {
    for (let i = start; i < endA; i++) out.push({ op: 'del', text: a[i] });
    for (let j = start; j < endB; j++) out.push({ op: 'add', text: b[j] });
  } else {
    // lcs[i * (m + 1) + j]: length of the LCS of a[start + i ..] and b[start + j ..]
    const w = m + 1;
    const lcs = new Int32Array((n + 1) * w);
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        lcs[i * w + j] = a[start + i] === b[start + j] ? lcs[(i + 1) * w + j + 1] + 1 : Math.max(lcs[(i + 1) * w + j], lcs[i * w + j + 1]);
      }
    }
    let i = 0;
    let j = 0;
    while (i < n && j < m) {
      if (a[start + i] === b[start + j]) {
        out.push({ op: 'same', text: a[start + i] });
        i++;
        j++;
      } else if (lcs[(i + 1) * w + j] >= lcs[i * w + j + 1]) {
        out.push({ op: 'del', text: a[start + i++] });
      } else {
        out.push({ op: 'add', text: b[start + j++] });
      }
    }
    while (i < n) out.push({ op: 'del', text: a[start + i++] });
    while (j < m) out.push({ op: 'add', text: b[start + j++] });
  }
  for (let i = endA; i < a.length; i++) out.push({ op: 'same', text: a[i] });
  return out;
}

export type DiffHunk = { kind: 'lines'; lines: DiffLine[] } | { kind: 'skip'; count: number };

/** Groups a diff for display: runs of more than 2 × `context` unchanged lines are folded to a "skip". */
export function foldUnchanged(diff: DiffLine[], context = 3): DiffHunk[] {
  const out: DiffHunk[] = [];
  let i = 0;
  while (i < diff.length) {
    if (diff[i].op !== 'same') {
      const run: DiffLine[] = [];
      while (i < diff.length && diff[i].op !== 'same') run.push(diff[i++]);
      push(out, run);
      continue;
    }
    let j = i;
    while (j < diff.length && diff[j].op === 'same') j++;
    const same = diff.slice(i, j);
    const head = i === 0 ? 0 : context;
    const tail = j === diff.length ? 0 : context;
    if (same.length > head + tail + 1) {
      push(out, same.slice(0, head));
      out.push({ kind: 'skip', count: same.length - head - tail });
      push(out, same.slice(same.length - tail));
    } else push(out, same);
    i = j;
  }
  return out;
}

function push(out: DiffHunk[], lines: DiffLine[]) {
  if (!lines.length) return;
  const last = out[out.length - 1];
  if (last?.kind === 'lines') last.lines.push(...lines);
  else out.push({ kind: 'lines', lines: [...lines] });
}
