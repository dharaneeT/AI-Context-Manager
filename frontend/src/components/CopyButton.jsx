import { useState } from "react";
import { toast } from "../toast.js";

/** Copies `text` to the clipboard and briefly shows "Copied". */
export default function CopyButton({ text, label = "Copy", className }) {
  const [done, setDone] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setDone(true);
      setTimeout(() => setDone(false), 1500);
    } catch {
      toast("Clipboard is blocked by the browser.", "error");
    }
  }

  return (
    <button
      type="button"
      className={className ?? "btn secondary small"}
      onClick={copy}
    >
      {done ? "Copied ✓" : label}
    </button>
  );
}
