/** Pill colour for an agent task status. */
export function statusClass(status) {
  if (status === "APPROVED" || status === "CONTEXT_READY") return "ok";
  if (status === "FAILED" || status === "REJECTED") return "bad";
  if (status === "NEEDS_INPUT" || status === "AWAITING_APPROVAL") return "warn";
  return "";
}

export const isActive = (status) => status === "QUEUED" || status === "RUNNING";

export const isFinished = (status) =>
  ["CONTEXT_READY", "APPROVED", "REJECTED", "FAILED", "CANCELLED"].includes(
    status,
  );

/** The backend sends errors as JSON text. Show just the message. */
export function friendly(error) {
  const raw = error?.message ?? String(error);
  try {
    const parsed = JSON.parse(raw);
    return parsed.message || raw;
  } catch {
    return raw;
  }
}

export function downloadText(filename, text) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/markdown" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
