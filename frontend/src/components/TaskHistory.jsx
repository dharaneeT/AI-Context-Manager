import { api } from "../api.js";
import { useAsync } from "../useAsync";

export default function TaskHistory({ projectId, onOpen }) {
  const tasks = useAsync(() => api.tasks(projectId), [projectId]);

  async function remove(id) {
    try {
      await api.deleteTask(projectId, id);
      tasks.reload();
    } catch {
      // the next reload will show the real state
      tasks.reload();
    }
  }

  if (tasks.error) return <p className="error">{tasks.error.message}</p>;
  if (!tasks.data) return <p className="muted">loading…</p>;
  if (tasks.data.length === 0)
    return (
      <p className="muted">No tasks yet. Ask something on the first tab.</p>
    );

  return (
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
        {tasks.data.map((t) => (
          <tr key={t.id}>
            <td className="small">{new Date(t.createdAt).toLocaleString()}</td>
            <td>{t.task.length > 110 ? `${t.task.slice(0, 110)}…` : t.task}</td>
            <td>
              <span
                className={`pill ${t.status === "SUCCEEDED" ? "ok" : t.status === "FAILED" ? "bad" : ""}`}
              >
                {t.status.toLowerCase()}
              </span>
            </td>
            <td>
              <button
                className="btn secondary small"
                onClick={() => onOpen(t.id)}
              >
                Open
              </button>{" "}
              <button className="btn danger small" onClick={() => remove(t.id)}>
                ✕
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
