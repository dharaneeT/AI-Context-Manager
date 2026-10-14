import { useState } from "react";
import { api } from "../api.js";
import CopyButton from "./CopyButton";

function ResultRow({ f }) {
  const [all, setAll] = useState(false);
  const reasons = all ? f.reasons : f.reasons.slice(0, 3);
  return (
    <tr>
      <td>
        <code>{f.path}</code>
        <div style={{ marginTop: 4 }}>
          <CopyButton text={f.path} label="Copy path" className="link-btn" />
        </div>
      </td>
      <td>
        <div className="chips">
          {Object.entries(f.ranks).map(([signal, rank]) => (
            <span key={signal} className="badge">
              {signal} #{rank}
            </span>
          ))}
        </div>
      </td>
      <td>
        <ul className="reasons">
          {reasons.map((r) => (
            <li key={r}>{r}</li>
          ))}
        </ul>
        {f.reasons.length > 3 && (
          <button className="link-btn" onClick={() => setAll((v) => !v)}>
            {all ? "Show fewer" : `Show all ${f.reasons.length}`}
          </button>
        )}
      </td>
    </tr>
  );
}

export default function SearchPanel({ projectId }) {
  const [text, setText] = useState("");
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setResult(await api.search(projectId, text));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  function clear() {
    setText("");
    setResult(null);
    setError(null);
  }

  return (
    <>
      <form onSubmit={submit} className="row">
        <input
          className="field grow"
          placeholder="Search by words or meaning…"
          aria-label="Search the project"
          value={text}
          onChange={(e) => setText(e.target.value)}
          required
        />
        <button className="btn" disabled={busy}>
          {busy ? "Searching…" : "Search"}
        </button>
        {(result || text) && (
          <button
            type="button"
            className="btn secondary"
            onClick={clear}
            disabled={busy}
          >
            Clear
          </button>
        )}
      </form>

      {busy && (
        <div className="status-line">
          <span className="spinner" />
          Searching…
        </div>
      )}
      {error && <div className="alert error">{error}</div>}

      {!result && !busy && !error && (
        <div className="empty" style={{ marginTop: "1rem" }}>
          <b>Search your indexed code</b>
          Type a few words or describe what you need. Results show which signals
          matched.
        </div>
      )}

      {result && (
        <>
          <p className="hint" style={{ margin: "0.9rem 0 0.6rem" }}>
            {result.files.length} result{result.files.length === 1 ? "" : "s"} ·
            keywords: {result.keywords.join(", ") || "—"}
            {!result.semanticUsed && ` · keyword-only (${result.semanticNote})`}
          </p>
          {result.files.length === 0 ? (
            <div className="empty">
              <b>No matching files</b>
              Try different words, or refresh the index on the Index tab.
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>File</th>
                    <th>Signals</th>
                    <th>Why</th>
                  </tr>
                </thead>
                <tbody>
                  {result.files.map((f) => (
                    <ResultRow key={f.path} f={f} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </>
  );
}
