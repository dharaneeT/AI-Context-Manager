import { useEffect, useState } from "react";
import { subscribe } from "../toast.js";

export default function Toasts() {
  const [items, setItems] = useState([]);

  useEffect(
    () =>
      subscribe((item) => {
        setItems((list) => [...list.slice(-3), item]);
        setTimeout(
          () => setItems((list) => list.filter((x) => x.id !== item.id)),
          3500,
        );
      }),
    [],
  );

  return (
    <div className="toasts" role="status" aria-live="polite">
      {items.map((t) => (
        <div key={t.id} className={`toast ${t.kind}`}>
          <span className="grow">{t.message}</span>
          <button
            className="icon-btn"
            aria-label="Dismiss"
            onClick={() => setItems((l) => l.filter((x) => x.id !== t.id))}
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  );
}
