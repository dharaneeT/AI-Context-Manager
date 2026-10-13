import { useState } from "react";
import { api } from "../api.js";

export default function ProjectSidebar({
  projects,
  error,
  selectedId,
  onSelect,
  onChanged,
}) {
  const [name, setName] = useState("");
  const [rootPath, setRootPath] = useState("");
  const [formError, setFormError] = useState(null);
  const [busy, setBusy] = useState(false);

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
    } catch (e) {
      setFormError(e.message);
    }
  }

  return (
    <aside className="sidebar">
      <h3>Projects</h3>
      {error && (
        <p className="error small">Could not load projects: {error.message}</p>
      )}
      {projects.map((p) => (
        <div
          key={p.id}
          className={`project ${p.id === selectedId ? "active" : ""}`}
          onClick={() => onSelect(p.id)}
        >
          <span>
            {p.name}
            <small>{p.indexStatus.toLowerCase().replace("_", " ")}</small>
          </span>
          <button
            className="btn danger small"
            title="Delete project"
            onClick={(e) => {
              e.stopPropagation();
              remove(p);
            }}
          >
            ✕
          </button>
        </div>
      ))}

      <form onSubmit={add} className="card">
        <h3>Add project</h3>
        <p>
          <input
            className="field"
            placeholder="Name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </p>
        <p>
          <input
            className="field"
            placeholder="Folder path"
            value={rootPath}
            onChange={(e) => setRootPath(e.target.value)}
            required
          />
        </p>
        <p className="muted small">
          The path as the backend sees it (inside Docker, under /workspace).
        </p>
        <button className="btn" disabled={busy}>
          {busy ? "Adding…" : "Add"}
        </button>
        {formError && <p className="error small">{formError}</p>}
      </form>
    </aside>
  );
}
