import { useEffect, useState } from "react";
import { api } from "../api.js";
import PackViewer from "./PackViewer";

const MAX_RETRIES = 6;

/** Polls one task until it finishes, then shows the pack. Remount (via key) to switch tasks. */
export default function TaskResult({ projectId, taskId }) {
  const [task, setTask] = useState(null);
  const [error, setError] = useState(null);
  const [retrying, setRetrying] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let timer;
    let failures = 0;
    const poll = async () => {
      try {
        const t = await api.task(projectId, taskId);
        if (cancelled) return;
        failures = 0;
        setRetrying(false);
        setTask(t);
        if (t.status === "QUEUED" || t.status === "RUNNING")
          timer = setTimeout(poll, 1200);
      } catch (e) {
        if (cancelled) return;
        failures += 1;
        // A short backend or database hiccup should not kill the screen:
        // keep trying a few times before showing the error.
        if (failures < MAX_RETRIES) {
          setRetrying(true);
          timer = setTimeout(poll, 3000);
        } else {
          setRetrying(false);
          setError(e.message);
        }
      }
    };
    poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [projectId, taskId]);

  if (error)
    return (
      <div className="alert error">
        <div>
          <b>Could not read this task.</b>
          <p>{error}</p>
          <p className="small">
            If the backend or database was restarted, the task may have been
            interrupted. Open History to check its status.
          </p>
        </div>
      </div>
    );
  if (!task)
    return (
      <div className="status-line">
        <span className="spinner" />
        {retrying ? "Backend not responding, retrying…" : "Loading task…"}
      </div>
    );

  if (task.status === "QUEUED" || task.status === "RUNNING") {
    return (
      <div className="card flat">
        <div className="status-line" style={{ padding: 0 }}>
          <span className="spinner" />
          <span>
            {task.stage ?? task.status}…{" "}
            <span className="hint">
              {retrying
                ? "(backend not responding, retrying)"
                : "(live status)"}
            </span>
          </span>
        </div>
      </div>
    );
  }
  if (task.status === "FAILED") {
    return (
      <div className="alert error">
        <div>
          <b>Task failed.</b>
          <p>{task.error}</p>
        </div>
      </div>
    );
  }
  return <PackViewer task={task} />;
}
