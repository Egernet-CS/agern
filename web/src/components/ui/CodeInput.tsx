import { useEffect, useRef, type ClipboardEvent, type KeyboardEvent } from 'react';

/** Six single-digit boxes with auto-advance, backspace-back and paste support. */
export function CodeInput({ value, onChange, onComplete, disabled, autoFocus = true, label = 'Authentication code' }: {
  value: string;
  onChange: (v: string) => void;
  onComplete?: (v: string) => void;
  disabled?: boolean;
  autoFocus?: boolean;
  label?: string;
}) {
  const refs = useRef<(HTMLInputElement | null)[]>([]);
  const digits = Array.from({ length: 6 }, (_, i) => value[i] ?? '');

  useEffect(() => {
    if (autoFocus && !disabled) refs.current[Math.min(value.length, 5)]?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoFocus, disabled]);

  const set = (next: string) => {
    const v = next.replace(/\D/g, '').slice(0, 6);
    onChange(v);
    if (v.length === 6) onComplete?.(v);
    return v;
  };

  const onInput = (i: number, raw: string) => {
    const d = raw.replace(/\D/g, '');
    if (!d) return;
    if (d.length > 1) {
      const v = set(value.slice(0, i) + d);
      refs.current[Math.min(v.length, 5)]?.focus();
      return;
    }
    const arr = digits.slice();
    arr[i] = d;
    const v = set(arr.join('').slice(0, Math.max(i + 1, value.length)));
    if (i < 5) refs.current[Math.min(i + 1, v.length)]?.focus();
  };

  const onKey = (i: number, e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace') {
      e.preventDefault();
      if (digits[i]) set(value.slice(0, i) + value.slice(i + 1));
      else if (i > 0) {
        set(value.slice(0, i - 1) + value.slice(i));
        refs.current[i - 1]?.focus();
      }
    } else if (e.key === 'ArrowLeft' && i > 0) refs.current[i - 1]?.focus();
    else if (e.key === 'ArrowRight' && i < 5) refs.current[i + 1]?.focus();
  };

  const onPaste = (e: ClipboardEvent<HTMLInputElement>) => {
    const t = e.clipboardData.getData('text').replace(/\D/g, '');
    if (!t) return;
    e.preventDefault();
    const v = set(t);
    refs.current[Math.min(v.length, 5)]?.focus();
  };

  return (
    <div className="code-input" role="group" aria-label={label}>
      {digits.map((d, i) => (
        <input
          key={i}
          ref={(el) => {
            refs.current[i] = el;
          }}
          value={d}
          inputMode="numeric"
          autoComplete={i === 0 ? 'one-time-code' : 'off'}
          pattern="[0-9]*"
          maxLength={6}
          aria-label={`Digit ${i + 1} of 6`}
          disabled={disabled}
          onChange={(e) => onInput(i, e.target.value)}
          onKeyDown={(e) => onKey(i, e)}
          onPaste={onPaste}
          onFocus={(e) => e.target.select()}
          className={i === 2 ? 'gap-after' : undefined}
        />
      ))}
    </div>
  );
}
