import { useEffect, useState } from "react";

/**
 * Runs an async function when `deps` change (or when reload() is called).
 * Keeps the previous data while reloading, so the screen doesn't flicker.
 */
export function useAsync(fn, deps) {
  const [state, setState] = useState({
    data: null,
    error: null,
    loading: true,
  });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    fn()
      .then((data) => {
        if (!cancelled) setState({ data, error: null, loading: false });
      })
      .catch((error) => {
        if (!cancelled)
          setState((s) => ({ data: s.data, error, loading: false }));
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  return { ...state, reload: () => setTick((t) => t + 1) };
}
