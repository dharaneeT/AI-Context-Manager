import { useEffect, useState } from "react";
import { api } from "../api.js";
import PackViewer from "./PackViewer";

/** Polls one task until it finishes, then shows the pack. Remount (via key) to switch tasks. */
export default function TaskResult({ projectId, taskId }) {
  const [task, setTask] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    let timer;
    const poll = async () => {
      try {
        const t = await api.task(projectId, taskId);
        if (cancelled) return;
        setTask(t);
        if (t.status === "QUEUED" || t.status === "RUNNING")
          timer = setTimeout(poll, 1200);
      } catch (e) {
        if (!cancelled) setError(e.message);
      }
    };
    poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [projectId, taskId]);

  if (error) return <p className="error">{error}</p>;
  if (!task)
    return (
      <p className="muted">
        <span className="spinner" />
        loading…
      </p>
    );

  if (task.status === "QUEUED" || task.status === "RUNNING") {
    return (
      <p className="muted">
        <span className="spinner" />
        {task.stage ?? task.status}…
      </p>
    );
  }
  if (task.status === "FAILED") {
    return (
      <div className="card">
        <b className="error">Task failed.</b>
        <p>{task.error}</p>
      </div>
    );
  }
  return <PackViewer task={task} />;
}
