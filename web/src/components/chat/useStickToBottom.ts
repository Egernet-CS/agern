import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';

/** How long after a wheel, touch or key event a scroll still counts as the user's (refreshed while scrolling goes on). */
const INTENT_MS = 300;
/** A user scroll that ends this close to the bottom (CSS px) follows the bottom again. */
const RESTICK_PX = 80;
const SCROLL_KEYS = new Set(['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' ', 'Spacebar']);

/** True when the view moved up since `lastTop` (and is not simply clamped at the bottom after the content shrank). */
function movedUp(el: HTMLElement, lastTop: number): boolean {
  return el.scrollTop < lastTop - 0.5 && el.scrollHeight - el.scrollTop - el.clientHeight > 1;
}

/**
 * Keeps a scroll container at the bottom while its content grows (the chat transcript), until the user scrolls up.
 *
 * - Stuck at mount. While stuck, the view is pinned to the bottom on every render and whenever the content or the
 *   container changes size (images loading, child components rendering later, the composer or keyboard resizing the
 *   pane). ResizeObserver callbacks run before paint, so the reader never sees a frame away from the bottom.
 * - Only scrolling by the user changes the state: a user scroll upward unsticks, a user scroll that ends near the bottom
 *   re-sticks. Scrolls without a recent wheel, touch, scroll key or scrollbar press (our own pinning, the smooth scroll of
 *   "jump to latest", browser scroll anchoring, scripts) leave it alone.
 */
export function useStickToBottom() {
  const scrollerRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const [atBottom, setAtBottom] = useState(true);
  const lastTop = useRef(0);
  const lastIntent = useRef(-Infinity);
  const held = useRef(false);

  const setStuck = useCallback((v: boolean) => {
    stick.current = v;
    setAtBottom(v);
  }, []);

  const pin = useCallback(() => {
    const el = scrollerRef.current;
    if (!el || !stick.current) return;
    // A user scroll-up whose scroll event has not been handled yet must not be undone by pinning.
    if ((held.current || performance.now() - lastIntent.current < INTENT_MS) && movedUp(el, lastTop.current)) {
      lastTop.current = el.scrollTop;
      setStuck(false);
      return;
    }
    el.scrollTop = el.scrollHeight;
    lastTop.current = el.scrollTop;
  }, [setStuck]);

  // Pin after every render of the owner.
  useLayoutEffect(pin);

  // Pin when the content (images, late child renders, layout) or the pane (composer, keyboard, window) changes size.
  useEffect(() => {
    const el = scrollerRef.current;
    const content = contentRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => pin());
    ro.observe(el);
    if (content) ro.observe(content);
    return () => ro.disconnect();
  }, [pin]);

  // Signs that the user is scrolling: wheel or trackpad, touch, scroll keys in the transcript, the scrollbar held.
  useEffect(() => {
    const el = scrollerRef.current;
    if (!el) return;
    const intent = () => {
      lastIntent.current = performance.now();
    };
    const onKey = (e: KeyboardEvent) => {
      if (SCROLL_KEYS.has(e.key)) intent();
    };
    const onDown = (e: PointerEvent) => {
      // The scrollbar belongs to the scroller itself; clicks on the transcript's content target its children.
      if (e.target === el) held.current = true;
    };
    const release = () => {
      if (held.current) {
        held.current = false;
        intent(); // the last scroll events of a drag may still be on their way
      }
    };
    const onMove = (e: PointerEvent) => {
      if (held.current && e.buttons === 0) release();
    };
    const passive = { passive: true } as const;
    el.addEventListener('wheel', intent, passive);
    el.addEventListener('touchstart', intent, passive);
    el.addEventListener('touchmove', intent, passive);
    el.addEventListener('keydown', onKey);
    el.addEventListener('pointerdown', onDown);
    window.addEventListener('pointerup', release);
    window.addEventListener('pointercancel', release);
    window.addEventListener('pointermove', onMove, passive);
    return () => {
      el.removeEventListener('wheel', intent);
      el.removeEventListener('touchstart', intent);
      el.removeEventListener('touchmove', intent);
      el.removeEventListener('keydown', onKey);
      el.removeEventListener('pointerdown', onDown);
      window.removeEventListener('pointerup', release);
      window.removeEventListener('pointercancel', release);
      window.removeEventListener('pointermove', onMove);
    };
  }, []);

  const onScroll = useCallback(() => {
    const el = scrollerRef.current;
    if (!el) return;
    const now = performance.now();
    const top = el.scrollTop;
    const dist = el.scrollHeight - top - el.clientHeight;
    if (held.current || now - lastIntent.current < INTENT_MS) {
      // Momentum and smooth key scrolling keep sending scroll events after the last input event.
      lastIntent.current = now;
      if (movedUp(el, lastTop.current)) {
        if (stick.current) setStuck(false);
      } else if (dist < RESTICK_PX) {
        if (!stick.current) setStuck(true);
      }
    }
    lastTop.current = top;
  }, [setStuck]);

  const jumpToLatest = useCallback(() => {
    const el = scrollerRef.current;
    setStuck(true);
    el?.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }, [setStuck]);

  /** Follow the bottom again (the user sent a message). */
  const stickNow = useCallback(() => setStuck(true), [setStuck]);

  return { scrollerRef, contentRef, atBottom, onScroll, jumpToLatest, stickNow };
}
