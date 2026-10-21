import { useCallback, useEffect, useState } from "react";
import { api } from "../api.js";
import { toast } from "../toast.js";
import {
  downloadText,
  friendly,
  isActive,
  isFinished,
  statusClass,
} from "../agentUtil.js";
import PlanEditor from "./PlanEditor";

function Steps({ steps }) {
  if (!steps?.length) return null;
  return (
    <ul className="agent-steps">
      {steps.map((s, i) => (
        <li key={`${s.stage}-${i}`} className={s.status.toLowerCase()}>
          <span className="dot-state" aria-hidden="true" />
          <b>{s.stage}</b>
          <span className="muted small">{s.summary}</span>
        </li>
      ))}
    </ul>
  );
}

function Questions({ projectId, task, onDone }) {
  const questions = task.details?.understanding?.questions ?? [];
  const [answers, setAnswers] = useState({});
  const [busy, setBusy] = useState(false);

  async function send(payload) {
    setBusy(true);
    try {
      await api.answerAgentTask(projectId, task.id, payload);
      onDone();
    } catch (e) {
      toast(friendly(e), "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card agent-section">
      <div className="section-head" style={{ margin: "0 0 0.6rem" }}>
        <h3>The agent has a question</h3>
      </div>
      {questions.map((q) => (
        <div key={q.id} style={{ marginBottom: "0.8rem" }}>
          <label className="label" htmlFor={`q-${q.id}`}>
            {q.question}
          </label>
          {q.why && <p className="hint">{q.why}</p>}
          <textarea
            id={`q-${q.id}`}
            className="field"
            value={answers[q.id] ?? ""}
            onChange={(e) =>
              setAnswers((a) => ({ ...a, [q.id]: e.target.value }))
            }
          />
        </div>
      ))}
      <div className="row">
        <button className="btn" disabled={busy} onClick={() => send(answers)}>
          Send answers
        </button>
        <button
          className="btn secondary"
          disabled={busy}
          onClick={() => send({})}
          title="The agent will continue with its own assumptions"
        >
          Skip, use your assumptions
        </button>
      </div>
    </div>
  );
}

function Understanding({ u }) {
  if (!u) return null;
  return (
    <div className="card agent-section">
      <div className="section-head" style={{ margin: "0 0 0.4rem" }}>
        <h3>Understanding</h3>
        <span className="pill">{u.source}</span>
      </div>
      <p>{u.summary}</p>
      {u.acceptance_criteria?.length > 0 && (
        <>
          <b className="small">Acceptance criteria</b>
          <ul className="agent-list">
            {u.acceptance_criteria.map((c) => (
              <li key={c}>{c}</li>
            ))}
          </ul>
        </>
      )}
      {u.assumptions?.length > 0 && (
        <>
          <b className="small">Assumptions</b>
          <ul className="agent-list">
            {u.assumptions.map((c) => (
              <li key={c}>{c}</li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function Impact({ impact }) {
  if (!impact) return null;
  const rows = impact.components.filter((c) => c.role !== "reference");
  return (
    <div className="card agent-section">
      <div className="section-head" style={{ margin: "0 0 0.4rem" }}>
        <h3>Impact</h3>
        <span className="hint">
          layers: {impact.layers_touched.join(", ") || "none"}
        </span>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Role</th>
              <th>Layer</th>
              <th>File</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.path}>
                <td>
                  <span className={`pill ${c.role === "modify" ? "warn" : ""}`}>
                    {c.role}
                  </span>
                </td>
                <td className="small">{c.layer}</td>
                <td className="agent-path">{c.path}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {impact.decisions.map((d) => (
        <p key={d.concept} className="small" style={{ marginTop: "0.5rem" }}>
          <b>{d.concept}:</b> {d.decision} — {d.why}
        </p>
      ))}
      {impact.risks.length > 0 && (
        <>
          <b className="small">Risks</b>
          <ul className="agent-list">
            {impact.risks.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function PackSummary({ projectId, task }) {
  if (!task.packReady) return null;
  async function download() {
    try {
      const text = await api.agentPack(projectId, task.id);
      downloadText(`context-pack-${task.id}.md`, text);
    } catch (e) {
      toast(friendly(e), "error");
    }
  }
  return (
    <div className="card agent-section">
      <div className="section-head" style={{ margin: "0 0 0.4rem" }}>
        <h3>Context pack</h3>
        <button className="btn secondary small" onClick={download}>
          Download pack.md
        </button>
      </div>
      <p className="small muted">
        {task.fullFiles} complete files, {task.windowedFiles} excerpts,{" "}
        {task.pathOnlyFiles} path-only · ~{task.packTokens?.toLocaleString()}{" "}
        tokens
        {task.baseCommit && <> · base commit {task.baseCommit.slice(0, 8)}</>}
      </p>
      <ul className="agent-list">
        {(task.items ?? []).slice(0, 12).map((i) => (
          <li key={i.path}>
            <span className="agent-path">{i.path}</span>{" "}
            <span className="pill">{i.level.toLowerCase()}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function AgentTaskView({ projectId, taskId, onChanged }) {
  const [task, setTask] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try {
      setTask(await api.agentTask(projectId, taskId));
      setError(null);
    } catch (e) {
      setError(e);
    }
  }, [projectId, taskId]);

  useEffect(() => {
    load();
  }, [load]);

  const active = task ? isActive(task.status) : false;
  useEffect(() => {
    if (!active) return undefined;
    const timer = setInterval(load, 2000);
    return () => clearInterval(timer);
  }, [active, load]);

  const status = task?.status;
  useEffect(() => {
    if (status) onChanged();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]);

  async function cancel() {
    try {
      await api.cancelAgentTask(projectId, taskId);
      load();
    } catch (e) {
      toast(friendly(e), "error");
    }
  }

  function changed() {
    load();
    onChanged();
  }

  if (error && !task)
    return <div className="alert error">{friendly(error)}</div>;
  if (!task)
    return (
      <div className="status-line">
        <span className="spinner" />
        Loading task…
      </div>
    );

  const d = task.details ?? {};
  return (
    <div className="agent-section">
      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <b>Task #{task.id}</b>
          <span className={`pill ${statusClass(task.status)}`}>
            {task.status.toLowerCase().replace("_", " ")}
          </span>
        </div>
        <p style={{ margin: "0.4rem 0" }}>{task.command}</p>
        <p className="small muted">
          {task.stage}
          {active && <span className="spinner" style={{ marginLeft: 8 }} />}
        </p>
        {task.error && <div className="alert error">{task.error}</div>}
        <Steps steps={d.steps ?? task.steps} />
        {!isFinished(task.status) && (
          <div className="row" style={{ marginTop: "0.6rem" }}>
            <button className="btn danger small" onClick={cancel}>
              Cancel task
            </button>
          </div>
        )}
      </div>

      {task.status === "NEEDS_INPUT" && (
        <Questions projectId={projectId} task={task} onDone={changed} />
      )}
      <Understanding u={d.understanding} />
      <Impact impact={d.impact} />
      <PackSummary projectId={projectId} task={task} />
      {task.planReady && d.plan && (
        <PlanEditor projectId={projectId} task={task} onChanged={changed} />
      )}
      {task.planReady && !d.plan && (
        <div className="alert warn">
          The plan exists, but the agent service is not reachable right now, so
          it cannot be shown or edited. Start the agent and reload.
        </div>
      )}
    </div>
  );
}
