import { useState } from "react";
import "../agent.css";
import { api } from "../api.js";
import { useAsync } from "../useAsync";
import { toast } from "../toast.js";
import { friendly, statusClass } from "../agentUtil.js";
import AgentTaskView from "./AgentTaskView";

const BUDGETS = [2000, 4000, 6000, 8000];

export default function AgentPanel({ projectId }) {
  const agent = useAsync(() => api.agentStatus(), []);
  const tasks = useAsync(() => api.agentTasks(projectId), [projectId]);
  const [command, setCommand] = useState("");
  const [maxTokens, setMaxTokens] = useState(6000);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [selected, setSelected] = useState(null);

  async function submit(event) {
    event?.preventDefault();
    if (command.trim().length < 3 || busy) return;
    setBusy(true);
    setError(null);
    try {
      const created = await api.createAgentTask(projectId, {
        command,
        maxTokens,
      });
      setSelected(created.id);
      setCommand("");
      tasks.reload();
      toast("Task started. The agent is working on it.", "success");
    } catch (e) {
      setError(friendly(e));
    } finally {
      setBusy(false);
    }
  }

  async function remove(id) {
    try {
      await api.deleteAgentTask(projectId, id);
      if (selected === id) setSelected(null);
      toast("Task deleted.", "success");
    } catch (e) {
      toast(friendly(e), "error");
    }
    tasks.reload();
  }

  const agentDown = agent.data && agent.data.reachable === false;
  const list = tasks.data ?? [];

  return (
    <>
      {agentDown && (
        <div className="alert warn">
          The agent service is not reachable. {agent.data.message}
        </div>
      )}

      <form onSubmit={submit} className="card">
        <label className="label" htmlFor="agent-command">
          What should the agent do?
        </label>
        <textarea
          id="agent-command"
          className="field"
          placeholder="e.g. “Add a rating feature to completed exchanges. Users give 1 to 5 stars.”"
          value={command}
          onChange={(e) => setCommand(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) submit();
          }}
          required
        />
        <div className="row" style={{ marginTop: "0.6rem" }}>
          <span className="hint">Context budget</span>
          <div className="chips">
            {BUDGETS.map((b) => (
              <button
                key={b}
                type="button"
                className={`chip ${maxTokens === b ? "active" : ""}`}
                onClick={() => setMaxTokens(b)}
              >
                {b / 1000}k
              </button>
            ))}
          </div>
          <span className="grow" />
          <button className="btn" disabled={busy || command.trim().length < 3}>
            {busy ? "Starting…" : "Plan this task"}
          </button>
        </div>
        {error && <div className="alert error">{error}</div>}
        <p className="hint" style={{ marginTop: "0.5rem" }}>
          The agent reads your code, writes a plan, and waits for your approval.
          It does not change any files yet.
        </p>
      </form>

      {list.length > 0 && (
        <div className="table-wrap agent-section">
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
              {list.map((t) => (
                <tr key={t.id}>
                  <td className="small num">
                    {new Date(t.createdAt).toLocaleString()}
                  </td>
                  <td title={t.command}>
                    {t.command.length > 80
                      ? `${t.command.slice(0, 80)}…`
                      : t.command}
                  </td>
                  <td>
                    <span className={`pill ${statusClass(t.status)}`}>
                      {t.status.toLowerCase().replace("_", " ")}
                    </span>
                  </td>
                  <td className="num">
                    <button
                      className="btn secondary small"
                      onClick={() => setSelected(t.id)}
                    >
                      Open
                    </button>{" "}
                    <button
                      className="btn danger small"
                      aria-label="Delete task"
                      onClick={() => remove(t.id)}
                    >
                      ✕
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selected ? (
        <AgentTaskView
          key={selected}
          projectId={projectId}
          taskId={selected}
          onChanged={tasks.reload}
        />
      ) : (
        list.length === 0 && (
          <div className="empty">
            <b>No agent tasks yet</b>
            Describe a change above. You will get an implementation plan to
            review.
          </div>
        )
      )}
    </>
  );
}
