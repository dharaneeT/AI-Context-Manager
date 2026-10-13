import { useState } from "react";
import "./App.css";
import { api } from "./api.js";
import { useAsync } from "./useAsync";
import ProjectSidebar from "./components/ProjectSidebar";
import Workspace from "./components/Workspace";

export default function App() {
  const health = useAsync(() => api.health(), []);
  const projects = useAsync(() => api.projects(), []);
  const [selectedId, setSelectedId] = useState(null);

  const list = projects.data ?? [];
  const selected = list.find((p) => p.id === selectedId) ?? list[0] ?? null;

  let status = "checking…";
  let statusClass = "pill";
  if (health.error) {
    status = "backend offline";
    statusClass = "pill bad";
  } else if (health.data) {
    status = `${health.data.status} · db ${health.data.database ? "ok" : "down"}`;
    statusClass = `pill ${health.data.status === "UP" ? "ok" : "warn"}`;
  }

  return (
    <div className="shell">
      <header className="topbar">
        <h1>ContextLayer</h1>
        <span className="tagline">
          task-specific context packs for AI agents
        </span>
        <span className={statusClass}>{status}</span>
      </header>
      <div className="layout">
        <ProjectSidebar
          projects={list}
          error={projects.error}
          selectedId={selected?.id ?? null}
          onSelect={setSelectedId}
          onChanged={projects.reload}
        />
        <main className="main">
          {selected ? (
            <Workspace key={selected.id} project={selected} />
          ) : (
            <p className="muted">
              Add a project folder on the left to get started.
            </p>
          )}
        </main>
      </div>
    </div>
  );
}
