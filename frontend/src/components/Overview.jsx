import { useState } from "react";
import { api } from "../api.js";
import { useAsync } from "../useAsync";
import { toast } from "../toast.js";

const DONE = {
  refresh: "Index refreshed.",
  rebuild: "Full rebuild finished.",
  summaries: "AI summaries finished.",
};

function List({ title, items }) {
  if (!items?.length) return null;
  return (
    <div style={{ marginBottom: "0.8rem" }}>
      <div className="label">{title}</div>
      <div className="chips">
        {items.map((item) => (
          <span key={item} className="chip-static">
            {item}
          </span>
        ))}
      </div>
    </div>
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
      toast(DONE[label] ?? "Done.", "success");
    } catch (e) {
      setError(e.message);
      toast("That action failed.", "error");
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
  const totalBytes = s?.languages?.reduce((sum, l) => sum + l.bytes, 0) || 1;

  return (
    <>
      <div className="card flat">
        <div className="section-title">Index actions</div>
        <div className="row">
          <button
            className="btn"
            disabled={Boolean(busy)}
            onClick={() => run("refresh", () => api.refresh(projectId, false))}
          >
            {busy === "refresh" ? "Refreshing…" : "Refresh index"}
          </button>
          <button
            className="btn secondary"
            disabled={Boolean(busy)}
            onClick={() => run("rebuild", () => api.refresh(projectId, true))}
          >
            {busy === "rebuild" ? "Rebuilding…" : "Full rebuild"}
          </button>
          <button
            className="btn secondary"
            disabled={Boolean(busy) || !llmReady}
            title={
              llmReady
                ? ""
                : "Start Ollama on this machine, then reload this tab"
            }
            onClick={aiSummaries}
          >
            {busy === "summaries" ? "Summarising…" : "AI summaries (local)"}
          </button>
        </div>
        {busy && (
          <div className="status-line">
            <span className="spinner" />
            Working. This can take a while on a large project.
          </div>
        )}
        {!llmReady && llm.data && (
          <p className="hint" style={{ marginTop: "0.6rem" }}>
            AI summaries need Ollama running on this machine.
          </p>
        )}
      </div>

      {error && <div className="alert error">{error}</div>}
      {report && (
        <details className="fold" open>
          <summary>Last action result</summary>
          <pre className="block">{JSON.stringify(report, null, 2)}</pre>
        </details>
      )}

      {stats.error && <div className="alert error">{stats.error.message}</div>}
      {stats.loading && !s && (
        <div className="status-line">
          <span className="spinner" />
          Loading index stats…
        </div>
      )}
      {s && (
        <div className="grid" style={{ margin: "0.9rem 0" }}>
          <div className="stat">
            <span>Status</span>
            <b>{s.status.toLowerCase().replace("_", " ")}</b>
          </div>
          <div className="stat">
            <span>Files</span>
            <b>{s.files.toLocaleString()}</b>
          </div>
          <div className="stat">
            <span>Chunks</span>
            <b>{s.chunks.toLocaleString()}</b>
          </div>
          <div className="stat">
            <span>Project size</span>
            <b>~{s.estimatedTokens.toLocaleString()} tok</b>
          </div>
          <div className="stat">
            <span>Embedded</span>
            <b>
              {e ? (e.available ? `${e.embedded}/${e.chunks}` : "off") : "…"}
            </b>
          </div>
          <div className="stat">
            <span>Local LLM</span>
            <b>{llm.data ? (llmReady ? "ready" : "off") : "…"}</b>
          </div>
        </div>
      )}
      {e && !e.available && (
        <div className="alert warn small">
          Semantic search is off: {e.unavailableReason}
        </div>
      )}
      {s?.lastIndexedAt && (
        <p className="hint">
          Last indexed {new Date(s.lastIndexedAt).toLocaleString()}
        </p>
      )}

      {s?.languages?.length > 0 && (
        <>
          <div className="section-head">
            <h3>Languages</h3>
          </div>
          <div className="table-wrap">
            <table>
              <tbody>
                {s.languages.slice(0, 8).map((l) => (
                  <tr key={l.language}>
                    <td>{l.language}</td>
                    <td className="num">{l.files} files</td>
                    <td className="num muted">
                      {(l.bytes / 1024).toFixed(0)} KB
                    </td>
                    <td style={{ width: "30%" }}>
                      <div className="bar thin">
                        <div
                          style={{
                            width: `${Math.max(2, Math.round((l.bytes / totalBytes) * 100))}%`,
                          }}
                        />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {overview.data && (
        <div className="card flat" style={{ marginTop: "1.2rem" }}>
          <div className="section-title">Project memory</div>
          <List title="Top folders" items={overview.data.topFolders} />
          <List title="Entry points" items={overview.data.entryPoints} />
          <List title="Most depended-on" items={overview.data.mostDependedOn} />
          <List title="Recently active" items={overview.data.recentlyActive} />
        </div>
      )}
    </>
  );
}
