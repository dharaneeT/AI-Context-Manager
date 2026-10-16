import { useRef, useState } from "react";
import { api } from "../api.js";
import { toast } from "../toast.js";
import "./FilePackPanel.css";

const CATEGORY_ORDER = ["CORE", "DEPENDENCY", "SUPPORTING", "CONTEXT_ONLY"];

/** The server sends errors as JSON text; show just the message part. */
function messageOf(error) {
  try {
    const parsed = JSON.parse(error.message);
    if (parsed?.message) return parsed.message;
  } catch {
    // not JSON: fall through
  }
  return error.message || "Something went wrong.";
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function saveBlob(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function Spinner() {
  return (
    <span
      className="spinner"
      style={{ margin: 0, borderTopColor: "currentColor" }}
    />
  );
}

export default function FilePackPanel({ projectId, task, onTaskChange }) {
  const [result, setResult] = useState(null); // answer of /file-pack/discover
  const [selected, setSelected] = useState(() => new Set());
  const [finding, setFinding] = useState(false);
  const [findError, setFindError] = useState(null);
  const [preview, setPreview] = useState(null); // { path, loading, error, data }
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState(null);

  const previewRequest = useRef(0); // ignores slow answers for a file we already left
  const exportLock = useRef(false); // stops a double click before React re-renders

  async function findFiles(event) {
    event?.preventDefault();
    if (!task.trim() || finding || exporting) return;
    setFinding(true);
    setFindError(null);
    setExportError(null);
    try {
      const data = await api.discoverFiles(projectId, { task });
      setResult(data);
      setSelected(
        new Set(
          data.files.filter((f) => f.selectedByDefault).map((f) => f.path),
        ),
      );
      setPreview(null);
      previewRequest.current++;
    } catch (e) {
      setFindError(messageOf(e));
    } finally {
      setFinding(false);
    }
  }

  function onKeyDown(event) {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) findFiles();
  }

  function toggle(path) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }

  async function openPreview(path) {
    const request = ++previewRequest.current;
    setPreview({ path, loading: true, error: null, data: null });
    try {
      const data = await api.previewFile(projectId, path);
      if (request === previewRequest.current)
        setPreview({ path, loading: false, error: null, data });
    } catch (e) {
      if (request === previewRequest.current)
        setPreview({ path, loading: false, error: messageOf(e), data: null });
    }
  }

  async function exportZip() {
    if (!result || selected.size === 0 || exportLock.current) return;
    exportLock.current = true;
    setExporting(true);
    setExportError(null);
    try {
      // We send the task the list was made for and only the paths that are ticked.
      // The server checks every path again on its own.
      const paths = result.files
        .filter((f) => selected.has(f.path))
        .map((f) => f.path);
      const file = await api.exportFiles(projectId, {
        task: result.task,
        paths,
      });
      saveBlob(file.blob, file.fileName);
      toast(`Downloaded ${file.fileName} (${file.included} files)`, "success");
      if (file.excluded > 0) {
        toast(
          `${file.excluded} file(s) were left out because they may contain secrets. See README.md in the ZIP.`,
          "info",
        );
      }
    } catch (e) {
      setExportError(messageOf(e));
    } finally {
      exportLock.current = false;
      setExporting(false);
    }
  }

  const files = result?.files ?? [];
  const chosen = files.filter((f) => selected.has(f.path));
  const totalBytes = chosen.reduce((sum, f) => sum + f.sizeBytes, 0);
  const totalTokens = chosen.reduce((sum, f) => sum + f.estimatedTokens, 0);
  const stale = result && task.trim() !== result.task;
  const groups = CATEGORY_ORDER.map((category) => ({
    category,
    items: files.filter((f) => f.category === category),
  })).filter((g) => g.items.length > 0);

  return (
    <>
      <form onSubmit={findFiles} className="card">
        <label className="label" htmlFor="fp-task">
          What do you want to do?
        </label>
        <textarea
          id="fp-task"
          className="field"
          placeholder="Describe the task, e.g. “Add email verification to the registration workflow”"
          value={task}
          onChange={(e) => onTaskChange(e.target.value)}
          onKeyDown={onKeyDown}
          required
        />
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span className="hint">Tip: press Ctrl/⌘ + Enter to search.</span>
          <span className="hint">
            {task.length.toLocaleString()} characters
          </span>
        </div>
        {findError && <div className="alert error">{findError}</div>}
        <div className="row" style={{ marginTop: "1rem" }}>
          <button
            className="btn"
            disabled={finding || exporting || !task.trim()}
          >
            {finding ? (
              <>
                <Spinner />
                Finding files…
              </>
            ) : (
              "Find relevant files"
            )}
          </button>
        </div>
      </form>

      {!result && !finding && (
        <div className="empty">
          <b>No files selected yet</b>
          Describe a task above. ContextLayer recommends the source files that
          look relevant, and you can export their full contents as a ZIP.
        </div>
      )}

      {result && (
        <>
          <div className="alert info small" style={{ marginTop: "1rem" }}>
            {result.disclaimer}
          </div>
          {stale && (
            <div className="alert warn small">
              You changed the task after this list was made. Press “Find
              relevant files” again to update it. The ZIP uses the task shown in
              this list: “{result.task}”.
            </div>
          )}
          {result.notes.map((note) => (
            <div key={note} className="alert warn small">
              {note}
            </div>
          ))}

          {files.length === 0 ? (
            <div className="empty">
              <b>No relevant files found</b>
              Try describing the task with more specific words, or index the
              project first.
            </div>
          ) : (
            <div className="fp-layout">
              <div className="card">
                <div className="fp-toolbar">
                  <b>
                    {chosen.length} of {files.length} files selected
                  </b>
                  <div className="row">
                    <button
                      type="button"
                      className="btn secondary small"
                      disabled={exporting}
                      onClick={() =>
                        setSelected(new Set(files.map((f) => f.path)))
                      }
                    >
                      Select all
                    </button>
                    <button
                      type="button"
                      className="btn secondary small"
                      disabled={exporting}
                      onClick={() => setSelected(new Set())}
                    >
                      Deselect all
                    </button>
                  </div>
                </div>

                <div className="fp-summary small">
                  <span>
                    <b>{chosen.length}</b> files
                  </span>
                  <span>
                    ~<b>{totalTokens.toLocaleString()}</b> tokens (estimate)
                  </span>
                  <span>
                    <b>{formatBytes(totalBytes)}</b> before zipping
                  </span>
                </div>

                {groups.map((group) => (
                  <section key={group.category}>
                    <h4 className="fp-group-title">
                      {group.items[0].categoryLabel} ({group.items.length})
                    </h4>
                    <ul className="fp-list">
                      {group.items.map((f) => (
                        <li
                          key={f.path}
                          className={`fp-item ${preview?.path === f.path ? "active" : ""}`}
                        >
                          <input
                            type="checkbox"
                            aria-label={`Select ${f.path}`}
                            checked={selected.has(f.path)}
                            disabled={exporting}
                            onChange={() => toggle(f.path)}
                          />
                          <div className="fp-main">
                            <button
                              type="button"
                              className="fp-path"
                              title="Preview this file"
                              onClick={() => openPreview(f.path)}
                            >
                              {f.path}
                            </button>
                            <div className="small">{f.reason}</div>
                            <div className="small muted">
                              relevance {Math.round(f.score * 100)}% ·{" "}
                              {formatBytes(f.sizeBytes)} · ~
                              {f.estimatedTokens.toLocaleString()} tokens
                            </div>
                          </div>
                        </li>
                      ))}
                    </ul>
                  </section>
                ))}

                <div className="alert warn small" style={{ marginTop: "1rem" }}>
                  <b>The ZIP contains your source code.</b> Share it only with
                  people and tools you trust. Files that look like secrets are
                  never included.
                </div>
                {exportError && (
                  <div className="alert error small">{exportError}</div>
                )}
                <div className="row" style={{ marginTop: "0.8rem" }}>
                  <button
                    type="button"
                    className="btn"
                    disabled={exporting || chosen.length === 0}
                    onClick={exportZip}
                  >
                    {exporting ? (
                      <>
                        <Spinner />
                        Building ZIP…
                      </>
                    ) : (
                      `Download ZIP (${chosen.length} files)`
                    )}
                  </button>
                  {chosen.length === 0 && (
                    <span className="hint">Select at least one file.</span>
                  )}
                </div>
              </div>

              <div className="card fp-preview">
                <h3 style={{ marginTop: 0 }}>Preview</h3>
                {!preview && (
                  <p className="muted small">
                    Click a file name to see its contents before you export.
                  </p>
                )}
                {preview?.loading && (
                  <p className="small">
                    <Spinner /> Loading {preview.path}…
                  </p>
                )}
                {preview?.error && (
                  <div className="alert error small">{preview.error}</div>
                )}
                {preview?.data && (
                  <>
                    <p
                      className="small mono"
                      style={{ wordBreak: "break-all" }}
                    >
                      {preview.data.path}
                    </p>
                    <p className="small muted">
                      {formatBytes(preview.data.sizeBytes)} · ~
                      {preview.data.estimatedTokens.toLocaleString()} tokens
                    </p>
                    {preview.data.redactions > 0 && (
                      <div className="alert warn small">
                        {preview.data.redactions} possible secret value(s) are
                        hidden in this preview. This file will be left out of
                        the ZIP.
                      </div>
                    )}
                    <pre className="block">{preview.data.content}</pre>
                  </>
                )}
              </div>
            </div>
          )}
        </>
      )}
    </>
  );
}
