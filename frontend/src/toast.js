const listeners = new Set();

/** Show a small message in the corner. kind: "info" | "success" | "error". */
export function toast(message, kind = "info") {
  const item = { id: `${Date.now()}-${Math.random()}`, message, kind };
  listeners.forEach((fn) => fn(item));
}

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
