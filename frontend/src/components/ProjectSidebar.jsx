import { useState } from "react";
import { api } from "../api.js";
import { toast } from "../toast.js";

function statusDot(status) {
  const s = status.toLowerCase();
  if (s.includes("fail") || s.includes("error")) return "bad";
  if (s.includes("index") && !s.includes("not")) return "warn";
  if (s.includes("ready") || s.includes("up_to_date") || s.includes("idle"))
    return "ok";
  return "";
}

export default function ProjectSidebar({
  open,
  projects,
  loading,
  error,
  selectedId,
  onSelect,
  onChanged,
}) {
  const [name, setName] = useState("");
  const [rootPath, setRootPath] = useState("");
  const [formError, setFormError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [filter, setFilter] = useState("");

  async function add(event) {
    event.preventDefault();
    setBusy(true);
    setFormError(null);
    try {
      const created = await api.createProject({ name, rootPath });
      setName("");
      setRootPath("");
      onChanged();
      onSelect(created.id);
      toast(`Project "${created.name ?? name}" added.`, "success");
    } catch (e) {
      setFormError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function remove(project) {
    if (
      !window.confirm(
        `Delete "${project.name}" and everything indexed for it? Your files are not touched.`,
      )
    )
      return;
    try {
      await api.deleteProject(project.id);
      onChanged();
      toast(`Project "${project.name}" deleted.`, "success");
    } catch (e) {
      setFormError(e.message);
    }
  }

  const visible = projects.filter((p) =>
    p.name.toLowerCase().includes(filter.trim().toLowerCase()),
  );

  return (
    <aside className={`sidebar ${open ? "open" : ""}`}>
      <div className="side-title">
        <span>Projects ({projects.length})</span>
        {loading && <span className="spinner" aria-label="Loading" />}
      </div>

      {projects.length > 4 && (
        <input
          className="field"
          placeholder="Filter projects…"
          aria-label="Filter projects"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
      )}

      {error && (
        <div className="alert error small">
          Could not load projects: {error.message}
        </div>
      )}

      {!loading && !error && projects.length === 0 && (
        <p className="hint">No projects yet. Add your first folder below.</p>
      )}

      <ul className="project-list">
        {visible.map((p) => (
          <li
            key={p.id}
            className={`project ${p.id === selectedId ? "active" : ""}`}
          >
            <button
              className="project-main"
              aria-current={p.id === selectedId ? "true" : undefined}
              onClick={() => onSelect(p.id)}
            >
              <span className={`dot ${statusDot(p.indexStatus)}`} />
              <span className="grow">
                <span className="project-name">{p.name}</span>
                <small>{p.indexStatus.toLowerCase().replace("_", " ")}</small>
              </span>
            </button>
            <button
              className="icon-btn danger"
              title="Delete project"
              aria-label={`Delete ${p.name}`}
              onClick={() => remove(p)}
            >
              ✕
            </button>
          </li>
        ))}
      </ul>

      <details className="add-project" open={projects.length === 0}>
        <summary>Add project</summary>
        <form onSubmit={add}>
          <div>
            <label className="label" htmlFor="p-name">
              Name
            </label>
            <input
              id="p-name"
              className="field"
              placeholder="My app"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
            />
          </div>
          <div>
            <label className="label" htmlFor="p-path">
              Folder path
            </label>
            <input
              id="p-path"
              className="field mono"
              placeholder="/workspace/my-app"
              value={rootPath}
              onChange={(e) => setRootPath(e.target.value)}
              required
            />
          </div>
          <p className="hint">
            The path as the backend sees it (inside Docker, under /workspace).
          </p>
          <button className="btn" disabled={busy}>
            {busy ? "Adding…" : "Add project"}
          </button>
          {formError && <div className="alert error small">{formError}</div>}
        </form>
      </details>
    </aside>
  );
}
