import { useEffect, useId, useMemo, useRef, useState, type ClipboardEvent, type KeyboardEvent, type ReactNode } from 'react';

export const FACTORY_COMMANDS = [
  { cmd: '/factory run ', usage: '/factory run <issue>', help: 'Start a pipeline run for an issue' },
  { cmd: '/factory status', usage: '/factory status', help: 'List runs in this project' },
  { cmd: '/factory approve', usage: '/factory approve [runId] [update]', help: 'Approve a run waiting for a human; update: update and merge it' },
  { cmd: '/factory cancel ', usage: '/factory cancel <runId>', help: 'Cancel a run' },
  { cmd: '/factory help', usage: '/factory help', help: 'Show factory commands' },
];

/** Chat commands suggested after `/`: the factory's, then the CLI's own `/compact` (docs/ARCHITECTURE.md §5). */
export const CHAT_COMMANDS = [
  ...FACTORY_COMMANDS,
  { cmd: '/compact ', usage: '/compact [what to keep]', help: 'Summarise the conversation to free up context' },
];

type Props = {
  onSubmit: (text: string) => Promise<boolean>;
  /** Absent: no interrupt (messages to pipeline agents). */
  onInterrupt?: () => void;
  working: boolean;
  /** Sending is allowed while the agent works (messages to a pipeline agent are queued for its next step). */
  sendWhileWorking?: boolean;
  /** `/factory …` and `/compact` command suggestions (chat only). */
  commands?: boolean;
  placeholder?: string;
  label?: string;
  history: string[];
  /** Chips of the attachments being composed, shown above the input. */
  attachments?: ReactNode;
  /** Files picked, pasted (`pasted`) or dropped. Absent: no attach affordances. */
  onFiles?: (files: File[], pasted?: boolean) => void;
  /** At least one uploaded attachment is ready: a message without text may be sent. */
  hasAttachments?: boolean;
  /** Why sending is not possible right now (uploads in flight, failed uploads). */
  blocked?: string | null;
};

export function Prompt({
  onSubmit,
  onInterrupt,
  working,
  sendWhileWorking = false,
  commands = true,
  placeholder,
  label = 'Message the agent',
  history,
  attachments,
  onFiles,
  hasAttachments = false,
  blocked = null,
}: Props) {
  const [value, setValue] = useState('');
  const [sel, setSel] = useState(0);
  const [dismissed, setDismissed] = useState(false);
  const [histIdx, setHistIdx] = useState<number | null>(null);
  const ta = useRef<HTMLTextAreaElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const listId = useId();

  const suggestions = useMemo(() => {
    if (!commands || dismissed || !value.startsWith('/') || value.includes('\n')) return [];
    const v = value.toLowerCase();
    return CHAT_COMMANDS.filter((c) => c.cmd.startsWith(v));
  }, [value, dismissed, commands]);
  const open = suggestions.length > 0;

  useEffect(() => setSel(0), [suggestions.length]);

  useEffect(() => {
    const el = ta.current;
    if (!el) return;
    el.style.height = '0px';
    // Capped (see .prompt textarea max-height): beyond it the textarea scrolls instead of pushing the pane.
    el.style.height = `${el.scrollHeight}px`;
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

  const canSend = (value.trim().length > 0 || hasAttachments) && !blocked;
  const submit = async () => {
    const text = value.trim();
    if ((!text && !hasAttachments) || (working && !sendWhileWorking) || blocked) return;
    setValue('');
    setHistIdx(null);
    setDismissed(false);
    const ok = await onSubmit(text);
    if (!ok) {
      // Give the text back without popping the command suggestions over the error message.
      setValue(text);
      setDismissed(true);
    }
  };

  const onPaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    if (!onFiles) return;
    const files = Array.from(e.clipboardData?.files ?? []);
    if (files.length === 0) return;
    // A copied file or screenshot: attach it instead of pasting its name.
    e.preventDefault();
    onFiles(files, true);
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
    if (e.key === 'Escape' && working && onInterrupt) {
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
      {attachments}
      {blocked && <div className="prompt-blocked muted small" role="status">{blocked}</div>}
      {open && (
        <ul className="prompt-suggest" id={listId} role="listbox" aria-label="Commands">
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
          placeholder={placeholder ?? (working ? 'Working — esc to interrupt' : 'Ask the agent, or /factory …')}
          aria-label={label}
          aria-autocomplete="list"
          aria-controls={open ? listId : undefined}
          aria-activedescendant={open ? `${listId}-${sel}` : undefined}
          onChange={(e) => {
            setValue(e.target.value);
            setDismissed(false);
            setHistIdx(null);
          }}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
        />
        {onFiles && (
          <>
            <input
              ref={picker}
              type="file"
              multiple
              hidden
              tabIndex={-1}
              aria-hidden
              onChange={(e) => {
                onFiles(Array.from(e.target.files ?? []));
                e.target.value = '';
                ta.current?.focus();
              }}
            />
            <button type="button" className="prompt-btn prompt-btn--attach" onClick={() => picker.current?.click()} aria-label="Attach files" title="Attach files — or paste / drop them">
              +
            </button>
          </>
        )}
        {working && onInterrupt ? (
          <button type="button" className="prompt-btn prompt-btn--stop" onClick={onInterrupt} aria-label="Interrupt (Esc)">
            ■
          </button>
        ) : (
          <button type="button" className="prompt-btn" onClick={() => void submit()} disabled={!canSend} aria-label="Send (Enter)" title={blocked ?? undefined}>
            ⏎
          </button>
        )}
      </div>
    </div>
  );
}
