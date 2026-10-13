import { useState } from "react";
import { api } from "../api.js";
import { useAsync } from "../useAsync";

function List({ title, items }) {
  if (!items?.length) return null;
  return (
    <p className="small">
      <b>{title}:</b> {items.join(", ")}
    </p>
  );
}

export default function Overview({ projectId }) {
  const stats = useAsync(() => api.stats(projectId), [projectId]);
  const overview = useAsync(() => api.overview(projectId), [projectId]);
  const embeddings = useAsync(
    () => api.embeddingStatus(projectId),
    [projectId],
  );
  const llm = useAsync(() => api.llmStatus(), []);

  const [busy, setBusy] = useState(null);
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);

  function reloadAll() {
    stats.reload();
    overview.reload();
    embeddings.reload();
    llm.reload();
  }

  async function run(label, action) {
    setBusy(label);
    setError(null);
    setReport(null);
    try {
      setReport(await action());
      reloadAll();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  }

  function aiSummaries() {
    const ok = window.confirm(
      "This runs your local Ollama model over your CODE files (secrets redacted, config files excluded). " +
        "Nothing leaves your PC, but it is slow on CPU: up to 50 files per click. Continue?",
    );
    if (ok) run("summaries", () => api.refreshSummaries(projectId, "llm"));
  }

  const s = stats.data;
  const e = embeddings.data;
  const llmReady = Boolean(llm.data?.configured);

  return (
    <>
      <div className="row">
        <button
          className="btn"
          disabled={busy}
          onClick={() => run("refresh", () => api.refresh(projectId, false))}
        >
          {busy === "refresh" ? "Refreshing…" : "Refresh index"}
        </button>
        <button
          className="btn secondary"
          disabled={busy}
          onClick={() => run("rebuild", () => api.refresh(projectId, true))}
        >
          {busy === "rebuild" ? "Rebuilding…" : "Full rebuild"}
        </button>
        <button
          className="btn secondary"
          disabled={busy || !llmReady}
          title={
            llmReady ? "" : "Start Ollama on this machine, then reload this tab"
          }
          onClick={aiSummaries}
        >
          {busy === "summaries" ? "Summarising…" : "AI summaries (local)"}
        </button>
        {busy && (
          <span className="muted">
            <span className="spinner" />
            working, this can take a while on a large project
          </span>
        )}
      </div>
      {error && <p className="error">{error}</p>}
      {report && <pre className="block">{JSON.stringify(report, null, 2)}</pre>}

      {stats.error && <p className="error">{stats.error.message}</p>}
      {s && (
        <div className="card grid">
          <div className="stat">
            <span className="muted small">status</span>
            <b>{s.status.toLowerCase().replace("_", " ")}</b>
          </div>
          <div className="stat">
            <span className="muted small">files</span>
            <b>{s.files.toLocaleString()}</b>
          </div>
          <div className="stat">
            <span className="muted small">chunks</span>
            <b>{s.chunks.toLocaleString()}</b>
          </div>
          <div className="stat">
            <span className="muted small">project size</span>
            <b>~{s.estimatedTokens.toLocaleString()} tok</b>
          </div>
          <div className="stat">
            <span className="muted small">embedded</span>
            <b>
              {e ? (e.available ? `${e.embedded}/${e.chunks}` : "off") : "…"}
            </b>
          </div>
          <div className="stat">
            <span className="muted small">local LLM</span>
            <b>{llm.data ? (llmReady ? "ready" : "off") : "…"}</b>
          </div>
        </div>
      )}
      {e && !e.available && (
        <p className="muted small">
          Semantic search is off: {e.unavailableReason}
        </p>
      )}
      {s?.lastIndexedAt && (
        <p className="muted small">
          Last indexed {new Date(s.lastIndexedAt).toLocaleString()}
        </p>
      )}

      {s?.languages?.length > 0 && (
        <div className="card">
          <h3>Languages</h3>
          <table>
            <tbody>
              {s.languages.slice(0, 8).map((l) => (
                <tr key={l.language}>
                  <td>{l.language}</td>
                  <td>{l.files} files</td>
                  <td className="muted">{(l.bytes / 1024).toFixed(0)} KB</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {overview.data && (
        <div className="card">
          <h3>Project memory</h3>
          <List title="Top folders" items={overview.data.topFolders} />
          <List title="Entry points" items={overview.data.entryPoints} />
          <List title="Most depended-on" items={overview.data.mostDependedOn} />
          <List title="Recently active" items={overview.data.recentlyActive} />
        </div>
      )}
    </>
  );
}
