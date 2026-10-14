import { useState } from "react";
import { api } from "../api.js";
import { useAsync } from "../useAsync";
import { toast } from "../toast.js";

const FILTERS = [
  ["all", "All"],
  ["succeeded", "Succeeded"],
  ["failed", "Failed"],
];

export default function TaskHistory({ projectId, onOpen }) {
  const tasks = useAsync(() => api.tasks(projectId), [projectId]);
  const [filter, setFilter] = useState("all");

  async function remove(id) {
    try {
      await api.deleteTask(projectId, id);
      toast("Task deleted.", "success");
    } catch {
      toast("Could not delete the task.", "error");
    }
    // reload either way so the screen shows the real state
    tasks.reload();
  }

  if (tasks.error)
    return <div className="alert error">{tasks.error.message}</div>;
  if (!tasks.data)
    return (
      <div className="status-line">
        <span className="spinner" />
        Loading history…
      </div>
    );
  if (tasks.data.length === 0)
    return (
      <div className="empty">
        <b>No tasks yet</b>
        Ask something on the Ask tab and it will show up here.
      </div>
    );

  const rows = tasks.data.filter(
    (t) => filter === "all" || t.status.toLowerCase() === filter,
  );

  return (
    <>
      <div className="row" style={{ marginBottom: "0.8rem" }}>
        <div className="chips grow">
          {FILTERS.map(([id, label]) => (
            <button
              key={id}
              className={`chip ${filter === id ? "active" : ""}`}
              onClick={() => setFilter(id)}
            >
              {label}
            </button>
          ))}
        </div>
        <span className="hint">{rows.length} shown</span>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Task</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.id}>
                <td className="small num">
                  {new Date(t.createdAt).toLocaleString()}
                </td>
                <td title={t.task}>
                  {t.task.length > 110 ? `${t.task.slice(0, 110)}…` : t.task}
                </td>
                <td>
                  <span
                    className={`pill ${t.status === "SUCCEEDED" ? "ok" : t.status === "FAILED" ? "bad" : ""}`}
                  >
                    {t.status.toLowerCase()}
                  </span>
                </td>
                <td className="num">
                  <button
                    className="btn secondary small"
                    onClick={() => onOpen(t.id)}
                  >
                    Open
                  </button>{" "}
                  <button
                    className="btn danger small"
                    aria-label="Delete task"
                    title="Delete task"
                    onClick={() => remove(t.id)}
                  >
                    ✕
                  </button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={4} className="muted">
                  No {filter} tasks.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
