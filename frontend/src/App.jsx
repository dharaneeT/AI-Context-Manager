import { useEffect, useState } from "react";
import "./App.css";
import { api } from "./api.js";
import { useAsync } from "./useAsync";
import ProjectSidebar from "./components/ProjectSidebar";
import Workspace from "./components/Workspace";
import Toasts from "./components/Toasts";

function readTheme() {
  try {
    return localStorage.getItem("cl-theme");
  } catch {
    return null;
  }
}

export default function App() {
  const health = useAsync(() => api.health(), []);
  const projects = useAsync(() => api.projects(), []);
  const [selectedId, setSelectedId] = useState(null);
  const [drawer, setDrawer] = useState(false);
  const [theme, setTheme] = useState(readTheme);

  useEffect(() => {
    const root = document.documentElement;
    if (theme) root.dataset.theme = theme;
    else delete root.dataset.theme;
  }, [theme]);

  function toggleTheme() {
    const current =
      theme ??
      (window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light");
    const next = current === "dark" ? "light" : "dark";
    setTheme(next);
    try {
      localStorage.setItem("cl-theme", next);
    } catch {
      // theme still applies for this session
    }
  }

  const list = projects.data ?? [];
  const selected = list.find((p) => p.id === selectedId) ?? list[0] ?? null;

  let status = "checking…";
  let statusClass = "pill";
  if (health.error) {
    status = "backend offline";
    statusClass = "pill bad";
  } else if (health.data) {
    // Spring's overall status already includes the database check.
    // Only show a db part if the backend actually sends one.
    const db = health.data.database;
    status =
      db === undefined
        ? health.data.status
        : `${health.data.status} · db ${db ? "ok" : "down"}`;
    statusClass = `pill ${health.data.status === "UP" && db !== false ? "ok" : "warn"}`;
  }

  function select(id) {
    setSelectedId(id);
    setDrawer(false);
  }

  return (
    <div className="shell">
      <header className="topbar">
        <button
          className="icon-btn menu-btn"
          aria-label="Toggle projects"
          aria-expanded={drawer}
          onClick={() => setDrawer((d) => !d)}
        >
          ☰
        </button>
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            CL
          </span>
          <h1>ContextLayer</h1>
        </div>
        <span className="tagline">
          task-specific context packs for AI agents
        </span>
        <span className="grow-spacer" />
        <span className={statusClass} title="Backend health">
          {status}
        </span>
        <button
          className="icon-btn"
          onClick={toggleTheme}
          title="Switch light / dark theme"
          aria-label="Switch light or dark theme"
        >
          ◐
        </button>
      </header>
      <div className="layout">
        <ProjectSidebar
          open={drawer}
          projects={list}
          loading={projects.loading}
          error={projects.error}
          selectedId={selected?.id ?? null}
          onSelect={select}
          onChanged={projects.reload}
        />
        <div
          className={`scrim ${drawer ? "open" : ""}`}
          onClick={() => setDrawer(false)}
        />
        <main className="main">
          <div className="main-inner">
            {selected ? (
              <Workspace key={selected.id} project={selected} />
            ) : (
              <div className="empty">
                <b>No project selected</b>
                {projects.loading
                  ? "Loading projects…"
                  : "Add a project folder in the sidebar to get started."}
              </div>
            )}
          </div>
        </main>
      </div>
      <Toasts />
    </div>
  );
}
