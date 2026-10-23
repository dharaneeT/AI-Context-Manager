/** Pill colour for an agent task status. */
export function statusClass(status) {
  if (
    [
      "APPROVED",
      "CONTEXT_READY",
      "IMPLEMENTED",
      "VERIFIED",
      "COMPLETE",
    ].includes(status)
  )
    return "ok";
  if (["FAILED", "REJECTED", "VERIFY_FAILED"].includes(status)) return "bad";
  if (["NEEDS_INPUT", "AWAITING_APPROVAL", "PARTIAL"].includes(status))
    return "warn";
  return "";
}

export const isActive = (status) => status === "QUEUED" || status === "RUNNING";

export const isFinished = (status) =>
  [
    "CONTEXT_READY",
    "APPROVED",
    "REJECTED",
    "IMPLEMENTED",
    "VERIFIED",
    "VERIFY_FAILED",
    "COMPLETE",
    "PARTIAL",
    "FAILED",
    "CANCELLED",
  ].includes(status);

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
