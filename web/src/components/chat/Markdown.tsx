// Small, safe markdown renderer (no HTML injection): fenced code, headings, lists, tables,
// quotes, rules, paragraphs; inline code, bold, italics, links. Streaming-tolerant.
import { Fragment, memo, type ReactNode } from 'react';

type Block =
  | { t: 'code'; lang: string; text: string; open: boolean }
  | { t: 'h'; level: number; text: string }
  | { t: 'ul' | 'ol'; items: { text: string; checked: boolean | null }[] }
  | { t: 'table'; head: string[]; rows: string[][] }
  | { t: 'quote'; text: string }
  | { t: 'hr' }
  | { t: 'p'; text: string };

function parse(src: string): Block[] {
  const lines = src.replace(/\r\n?/g, '\n').split('\n');
  const out: Block[] = [];
  let i = 0;
  const isTableSep = (l: string) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(l);
  const cells = (l: string) => l.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
  while (i < lines.length) {
    const l = lines[i];
    const fence = /^\s*(```+|~~~+)\s*([\w+-]*)/.exec(l);
    if (fence) {
      const buf: string[] = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith(fence[1])) buf.push(lines[i++]);
      const open = i >= lines.length;
      i++;
      out.push({ t: 'code', lang: fence[2], text: buf.join('\n'), open });
      continue;
    }
    if (!l.trim()) {
      i++;
      continue;
    }
    const h = /^(#{1,6})\s+(.*)$/.exec(l);
    if (h) {
      out.push({ t: 'h', level: h[1].length, text: h[2] });
      i++;
      continue;
    }
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(l)) {
      out.push({ t: 'hr' });
      i++;
      continue;
    }
    if (l.includes('|') && i + 1 < lines.length && isTableSep(lines[i + 1])) {
      const head = cells(l);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) rows.push(cells(lines[i++]));
      out.push({ t: 'table', head, rows });
      continue;
    }
    if (/^\s*>/.test(l)) {
      const buf: string[] = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) buf.push(lines[i++].replace(/^\s*>\s?/, ''));
      out.push({ t: 'quote', text: buf.join('\n') });
      continue;
    }
    const li = /^\s*([-*+]|\d+[.)])\s+(.*)$/.exec(l);
    if (li) {
      const ordered = /\d/.test(li[1]);
      const items: { text: string; checked: boolean | null }[] = [];
      while (i < lines.length) {
        const m = /^\s*([-*+]|\d+[.)])\s+(.*)$/.exec(lines[i]);
        if (m) {
          const task = /^\[( |x|X)\]\s+(.*)$/.exec(m[2]);
          items.push(task ? { text: task[2], checked: task[1] !== ' ' } : { text: m[2], checked: null });
          i++;
        } else if (lines[i].trim() && /^\s{2,}/.test(lines[i]) && items.length) {
          items[items.length - 1].text += ` ${lines[i++].trim()}`;
        } else break;
      }
      out.push({ t: ordered ? 'ol' : 'ul', items });
      continue;
    }
    const buf: string[] = [l];
    i++;
    while (i < lines.length && lines[i].trim() && !/^\s*(```|~~~|#{1,6}\s|>|[-*+]\s|\d+[.)]\s)/.test(lines[i]) && !(lines[i].includes('|') && i + 1 < lines.length && isTableSep(lines[i + 1])))
      buf.push(lines[i++]);
    out.push({ t: 'p', text: buf.join('\n') });
  }
  return out;
}

const INLINE = /(`+)([\s\S]*?[^`])\1(?!`)|\*\*([^*]+)\*\*|__([^_]+)__|(?<![\w*])\*([^*\s][^*]*?)\*(?!\w)|(?<![\w])_([^_\s][^_]*?)_(?![\w])|\[([^\]]+)\]\((https?:\/\/[^\s)]+|\/[^\s)]*)\)|(https?:\/\/[^\s<>()]+[^\s<>().,;:!?'"])/g;

export function inline(text: string, keyBase = 'i'): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let n = 0;
  for (const m of text.matchAll(INLINE)) {
    const idx = m.index ?? 0;
    if (idx > last) out.push(text.slice(last, idx));
    const k = `${keyBase}-${n++}`;
    if (m[2] !== undefined) out.push(<code key={k} className="md-code">{m[2]}</code>);
    else if (m[3] !== undefined || m[4] !== undefined) out.push(<strong key={k}>{inline(m[3] ?? m[4], k)}</strong>);
    else if (m[5] !== undefined || m[6] !== undefined) out.push(<em key={k}>{inline(m[5] ?? m[6], k)}</em>);
    else if (m[7] !== undefined)
      out.push(<a key={k} href={m[8]} target={m[8].startsWith('http') ? '_blank' : undefined} rel="noreferrer">{inline(m[7], k)}</a>);
    else if (m[9] !== undefined) out.push(<a key={k} href={m[9]} target="_blank" rel="noreferrer">{m[9]}</a>);
    last = idx + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

function withBreaks(text: string, k: string): ReactNode[] {
  return text.split('\n').flatMap((line, i) => (i ? [<br key={`${k}-br${i}`} />, ...inline(line, `${k}-${i}`)] : inline(line, `${k}-${i}`)));
}

export const Markdown = memo(function Markdown({ text, streaming }: { text: string; streaming?: boolean }) {
  const blocks = parse(text);
  return (
    <div className="md">
      {blocks.map((b, i) => {
        const k = `b${i}`;
        const caret = streaming && i === blocks.length - 1 ? <span className="caret" aria-hidden /> : null;
        switch (b.t) {
          case 'code':
            return (
              <div key={k} className="md-pre">
                {b.lang && <span className="md-lang">{b.lang}</span>}
                <pre>
                  <code>{b.text}</code>
                  {caret}
                </pre>
              </div>
            );
          case 'h': {
            const H = `h${Math.min(6, b.level + 2)}` as 'h3';
            return <H key={k} className="md-h">{inline(b.text, k)}{caret}</H>;
          }
          case 'ul':
          case 'ol': {
            const L = b.t;
            return (
              <L key={k}>
                {b.items.map((it, j) => (
                  <li key={j} className={it.checked !== null ? 'task' : undefined}>
                    {it.checked !== null && <span className="task-box" aria-hidden>{it.checked ? '☒' : '☐'}</span>}
                    {inline(it.text, `${k}-${j}`)}
                    {j === b.items.length - 1 && caret}
                  </li>
                ))}
              </L>
            );
          }
          case 'table':
            return (
              <div key={k} className="md-table-wrap">
                <table className="md-table">
                  <thead>
                    <tr>{b.head.map((c, j) => <th key={j}>{inline(c, `${k}h${j}`)}</th>)}</tr>
                  </thead>
                  <tbody>
                    {b.rows.map((r, j) => (
                      <tr key={j}>{r.map((c, x) => <td key={x}>{inline(c, `${k}r${j}c${x}`)}</td>)}</tr>
                    ))}
                  </tbody>
                </table>
                {caret}
              </div>
            );
          case 'quote':
            return <blockquote key={k}>{withBreaks(b.text, k)}{caret}</blockquote>;
          case 'hr':
            return <hr key={k} />;
          default:
            return <p key={k}>{withBreaks(b.text, k)}{caret}</p>;
        }
      })}
      {streaming && blocks.length === 0 && <span className="caret" aria-hidden />}
    </div>
  );
});

export const Inline = ({ text }: { text: string }) => <Fragment>{inline(text)}</Fragment>;
