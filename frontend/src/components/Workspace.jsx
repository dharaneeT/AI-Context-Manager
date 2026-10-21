import { useState } from "react";
import Overview from "./Overview";
import PackBuilder from "./PackBuilder";
import SearchPanel from "./SearchPanel";
import TaskHistory from "./TaskHistory";
import AgentPanel from "./AgentPanel";
import CopyButton from "./CopyButton";

const TABS = [
  ["pack", "Ask"],
  ["agent", "Agent"],
  ["overview", "Index"],
  ["search", "Search"],
  ["history", "History"],
];

export default function Workspace({ project }) {
  const [tab, setTab] = useState("pack");
  const [taskId, setTaskId] = useState(null);

  function onKeyDown(event) {
    const i = TABS.findIndex(([id]) => id === tab);
    let next = null;
    if (event.key === "ArrowRight") next = (i + 1) % TABS.length;
    if (event.key === "ArrowLeft") next = (i - 1 + TABS.length) % TABS.length;
    if (next !== null) {
      event.preventDefault();
      setTab(TABS[next][0]);
      document.getElementById(`tab-${TABS[next][0]}`)?.focus();
    }
  }

  return (
    <>
      <div className="page-title">
        <h2>{project.name}</h2>
        <span className="path-chip" title={project.rootPath}>
          <span>{project.rootPath}</span>
          <CopyButton
            text={project.rootPath}
            label="Copy"
            className="btn secondary small"
          />
        </span>
      </div>

      <nav className="tabs" role="tablist" onKeyDown={onKeyDown}>
        {TABS.map(([id, label]) => (
          <button
            key={id}
            id={`tab-${id}`}
            role="tab"
            aria-selected={tab === id}
            tabIndex={tab === id ? 0 : -1}
            className={`tab ${tab === id ? "active" : ""}`}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
      </nav>

      {tab === "pack" && (
        <PackBuilder
          projectId={project.id}
          taskId={taskId}
          onCreated={setTaskId}
        />
      )}
      {tab === "agent" && <AgentPanel projectId={project.id} />}
      {tab === "overview" && <Overview projectId={project.id} />}
      {tab === "search" && <SearchPanel projectId={project.id} />}
      {tab === "history" && (
        <TaskHistory
          projectId={project.id}
          onOpen={(id) => {
            setTaskId(id);
            setTab("pack");
          }}
        />
      )}
    </>
  );
}
