import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';

export const FACTORY_COMMANDS = [
  { cmd: '/factory run ', usage: '/factory run <issue>', help: 'Start a pipeline run for an issue' },
  { cmd: '/factory status', usage: '/factory status', help: 'List runs in this project' },
  { cmd: '/factory approve', usage: '/factory approve [runId]', help: 'Approve a run waiting for a human' },
  { cmd: '/factory cancel ', usage: '/factory cancel <runId>', help: 'Cancel a run' },
  { cmd: '/factory help', usage: '/factory help', help: 'Show factory commands' },
];

type Props = {
  onSubmit: (text: string) => Promise<boolean>;
  onInterrupt: () => void;
  working: boolean;
  history: string[];
};

export function Prompt({ onSubmit, onInterrupt, working, history }: Props) {
  const [value, setValue] = useState('');
  const [sel, setSel] = useState(0);
  const [dismissed, setDismissed] = useState(false);
  const [histIdx, setHistIdx] = useState<number | null>(null);
  const ta = useRef<HTMLTextAreaElement>(null);
  const listId = useId();

  const suggestions = useMemo(() => {
    if (dismissed || !value.startsWith('/') || value.includes('\n')) return [];
    const v = value.toLowerCase();
    return FACTORY_COMMANDS.filter((c) => c.cmd.startsWith(v));
  }, [value, dismissed]);
  const open = suggestions.length > 0;

  useEffect(() => setSel(0), [suggestions.length]);

  useEffect(() => {
    const el = ta.current;
    if (!el) return;
    el.style.height = '0px';
    el.style.height = `${Math.min(el.scrollHeight, 240)}px`;
  }, [value]);

  useEffect(() => {
    ta.current?.focus({ preventScroll: true });
  }, []);

  const complete = (i: number) => {
    const c = suggestions[i];
    if (!c) return;
    setValue(c.cmd);
    setDismissed(c.cmd.endsWith(' ') ? false : true);
    ta.current?.focus();
  };

  const submit = async () => {
    const text = value.trim();
    if (!text || working) return;
    setValue('');
    setHistIdx(null);
    setDismissed(false);
    const ok = await onSubmit(text);
    if (!ok) setValue(text);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (open) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSel((s) => (s + 1) % suggestions.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSel((s) => (s - 1 + suggestions.length) % suggestions.length);
        return;
      }
      if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey && suggestions[sel] && suggestions[sel].cmd.trimEnd() !== value.trimEnd())) {
        e.preventDefault();
        complete(sel);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setDismissed(true);
        return;
      }
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void submit();
      return;
    }
    if (e.key === 'Escape' && working) {
      e.preventDefault();
      onInterrupt();
      return;
    }
    const el = e.currentTarget;
    if (e.key === 'ArrowUp' && history.length && el.selectionStart === 0 && el.selectionEnd === 0) {
      e.preventDefault();
      const next = histIdx === null ? history.length - 1 : Math.max(0, histIdx - 1);
      setHistIdx(next);
      setValue(history[next]);
      return;
    }
    if (e.key === 'ArrowDown' && histIdx !== null && el.selectionStart === el.value.length) {
      e.preventDefault();
      const next = histIdx + 1;
      if (next >= history.length) {
        setHistIdx(null);
        setValue('');
      } else {
        setHistIdx(next);
        setValue(history[next]);
      }
    }
  };

  return (
    <div className={`prompt ${working ? 'is-working' : ''}`}>
      {open && (
        <ul className="prompt-suggest" id={listId} role="listbox" aria-label="Factory commands">
          {suggestions.map((s, i) => (
            <li
              key={s.cmd}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === sel}
              className={i === sel ? 'is-sel' : ''}
              onMouseDown={(e) => {
                e.preventDefault();
                complete(i);
              }}
            >
              <span className="ps-usage">{s.usage}</span>
              <span className="ps-help">{s.help}</span>
            </li>
          ))}
        </ul>
      )}
      <div className="prompt-box">
        <span className="prompt-caret" aria-hidden>&gt;</span>
        <textarea
          ref={ta}
          rows={1}
          value={value}
          spellCheck={false}
          placeholder={working ? 'Working — esc to interrupt' : 'Ask the agent, or /factory …'}
          aria-label="Message the agent"
          aria-autocomplete="list"
          aria-controls={open ? listId : undefined}
          aria-activedescendant={open ? `${listId}-${sel}` : undefined}
          onChange={(e) => {
            setValue(e.target.value);
            setDismissed(false);
            setHistIdx(null);
          }}
          onKeyDown={onKeyDown}
        />
        {working ? (
          <button type="button" className="prompt-btn prompt-btn--stop" onClick={onInterrupt} aria-label="Interrupt (Esc)">
            ■
          </button>
        ) : (
          <button type="button" className="prompt-btn" onClick={() => void submit()} disabled={!value.trim()} aria-label="Send (Enter)">
            ⏎
          </button>
        )}
      </div>
    </div>
  );
}
