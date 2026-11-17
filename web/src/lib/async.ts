import { useCallback, useEffect, useRef, useState, type DependencyList } from 'react';

export type AsyncState<T> = {
  data: T | undefined;
  error: Error | undefined;
  loading: boolean;
  reload: () => void;
  setData: (fn: (prev: T | undefined) => T | undefined) => void;
};

/** Minimal data loader: refetches when deps change, keeps stale data while reloading. */
export function useAsync<T>(fn: () => Promise<T>, deps: DependencyList, opts: { pollMs?: number } = {}): AsyncState<T> {
  const [data, setDataState] = useState<T | undefined>(undefined);
  const [error, setError] = useState<Error | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  useEffect(() => {
    let alive = true;
    setLoading(true);
    fnRef.current().then(
      (d) => {
        if (!alive) return;
        setDataState(d);
        setError(undefined);
        setLoading(false);
      },
      (e: Error) => {
        if (!alive) return;
        setError(e);
        setLoading(false);
      },
    );
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  useEffect(() => {
    if (!opts.pollMs) return;
    const t = setInterval(() => setTick((x) => x + 1), opts.pollMs);
    return () => clearInterval(t);
  }, [opts.pollMs]);

  const reload = useCallback(() => setTick((x) => x + 1), []);
  const setData = useCallback((f: (prev: T | undefined) => T | undefined) => setDataState(f), []);
  return { data, error, loading, reload, setData };
}
