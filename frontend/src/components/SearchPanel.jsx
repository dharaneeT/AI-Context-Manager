import { useState } from "react";
import { api } from "../api.js";

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

  return (
    <>
      <form onSubmit={submit} className="row">
        <input
          className="field"
          style={{ flex: 1 }}
          placeholder="Search by words or meaning…"
          value={text}
          onChange={(e) => setText(e.target.value)}
          required
        />
        <button className="btn" disabled={busy}>
          {busy ? "Searching…" : "Search"}
        </button>
      </form>
      {error && <p className="error">{error}</p>}
      {result && (
        <>
          <p className="muted small">
            keywords: {result.keywords.join(", ") || "—"}
            {!result.semanticUsed && ` · keyword-only (${result.semanticNote})`}
          </p>
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
                <tr key={f.path}>
                  <td>
                    <code>{f.path}</code>
                  </td>
                  <td>
                    {Object.entries(f.ranks).map(([signal, rank]) => (
                      <span
                        key={signal}
                        className="badge"
                        style={{ marginRight: 4 }}
                      >
                        {signal} #{rank}
                      </span>
                    ))}
                  </td>
                  <td>
                    <ul className="reasons">
                      {f.reasons.slice(0, 3).map((r) => (
                        <li key={r}>{r}</li>
                      ))}
                    </ul>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </>
  );
}
