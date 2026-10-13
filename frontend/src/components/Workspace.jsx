import { useState } from "react";
import Overview from "./Overview";
import PackBuilder from "./PackBuilder";
import SearchPanel from "./SearchPanel";
import TaskHistory from "./TaskHistory";

const TABS = [
  ["pack", "Ask"],
  ["overview", "Index"],
  ["search", "Search"],
  ["history", "History"],
];

export default function Workspace({ project }) {
  const [tab, setTab] = useState("pack");
  const [taskId, setTaskId] = useState(null);

  return (
    <>
      <h2>
        {project.name} <small className="muted">{project.rootPath}</small>
      </h2>
      <nav className="tabs">
        {TABS.map(([id, label]) => (
          <button
            key={id}
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
