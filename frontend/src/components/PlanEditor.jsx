import { useState } from "react";
import { api } from "../api.js";
import { toast } from "../toast.js";
import { downloadText, friendly } from "../agentUtil.js";

const ACTIONS = ["modify", "create", "delete", "verify"];

const toDraft = (steps) =>
  steps.map((s) => ({
    title: s.title,
    description: s.description,
    action: s.action,
    files: s.files.join("\n"),
    verify: s.verify ?? "",
  }));

const fromDraft = (draft) =>
  draft.map((s) => ({
    title: s.title.trim(),
    description: s.description.trim(),
    action: s.action,
    files: s.files
      .split("\n")
      .map((f) => f.trim())
      .filter(Boolean),
    verify: s.action === "verify" ? s.verify.trim() || null : null,
    // depends_on is left out on purpose: the agent makes each step follow the one before it
  }));

export default function PlanEditor({ projectId, task, onChanged }) {
  const plan = task.details?.plan;
  const waiting = task.status === "AWAITING_APPROVAL";
  const [draft, setDraft] = useState(() => toDraft(plan.steps));
  const [baseVersion, setBaseVersion] = useState(plan.version);
  const [dirty, setDirty] = useState(false);
  const [ack, setAck] = useState({});
  const [note, setNote] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  // a new plan version arrived (from the agent or after saving): show it
  if (plan.version !== baseVersion) {
    setBaseVersion(plan.version);
    setDraft(toDraft(plan.steps));
    setDirty(false);
    setAck({});
  }

  function change(index, patch) {
    setDraft((d) => d.map((s, i) => (i === index ? { ...s, ...patch } : s)));
    setDirty(true);
  }
  function remove(index) {
    setDraft((d) => d.filter((_, i) => i !== index));
    setDirty(true);
  }
  function move(index, delta) {
    setDraft((d) => {
      const next = [...d];
      const target = index + delta;
      if (target < 0 || target >= next.length) return d;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
    setDirty(true);
  }
  function add() {
    setDraft((d) => [
      ...d,
      {
        title: "New step",
        description: "",
        action: "modify",
        files: "",
        verify: "",
      },
    ]);
    setDirty(true);
  }

  async function run(action, success) {
    setBusy(true);
    try {
      await action();
      toast(success, "success");
      onChanged();
    } catch (e) {
      toast(friendly(e), "error");
    } finally {
      setBusy(false);
    }
  }

  const needed = plan.approval_required ?? [];
  const allAcked = needed.every((a) => ack[a.step]);
  const problems = plan.problems ?? [];
  const canApprove =
    waiting && !dirty && problems.length === 0 && allAcked && !busy;

  async function showMarkdown() {
    try {
      downloadText(
        `plan-${task.id}-v${plan.version}.md`,
        await api.agentPlanMarkdown(projectId, task.id),
      );
    } catch (e) {
      toast(friendly(e), "error");
    }
  }

  return (
    <div className="card agent-section">
      <div className="section-head" style={{ margin: "0 0 0.4rem" }}>
        <h3>Implementation plan</h3>
        <span className="hint">
          version {plan.version} · {plan.generated_by}
          {plan.fallback_reason && ` · template (${plan.fallback_reason})`}
        </span>
      </div>
      {plan.summary && <p>{plan.summary}</p>}

      {problems.length > 0 && (
        <div className="alert error">
          <b>Fix these before approving:</b>
          <ul className="agent-list">
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      )}

      {draft.map((s, i) => (
        <div className="plan-step" key={i}>
          <div className="row">
            <b>{i + 1}.</b>
            <input
              className="field grow"
              value={s.title}
              disabled={!waiting}
              aria-label={`Title of step ${i + 1}`}
              onChange={(e) => change(i, { title: e.target.value })}
            />
            <select
              className="field"
              style={{ width: "auto" }}
              value={s.action}
              disabled={!waiting}
              aria-label={`Action of step ${i + 1}`}
              onChange={(e) => change(i, { action: e.target.value })}
            >
              {ACTIONS.map((a) => (
                <option key={a}>{a}</option>
              ))}
            </select>
            {waiting && (
              <>
                <button
                  className="btn secondary small"
                  aria-label="Move up"
                  onClick={() => move(i, -1)}
                >
                  ↑
                </button>
                <button
                  className="btn secondary small"
                  aria-label="Move down"
                  onClick={() => move(i, 1)}
                >
                  ↓
                </button>
                <button
                  className="btn danger small"
                  aria-label="Remove step"
                  onClick={() => remove(i)}
                >
                  ✕
                </button>
              </>
            )}
          </div>
          <textarea
            className="field"
            style={{ marginTop: "0.4rem" }}
            value={s.description}
            disabled={!waiting}
            aria-label={`Description of step ${i + 1}`}
            onChange={(e) => change(i, { description: e.target.value })}
          />
          {s.action === "verify" ? (
            <input
              className="field"
              style={{ marginTop: "0.4rem" }}
              value={s.verify}
              disabled={!waiting}
              placeholder="command, e.g. backend:test"
              aria-label={`Command of step ${i + 1}`}
              onChange={(e) => change(i, { verify: e.target.value })}
            />
          ) : (
            <textarea
              className="field agent-path"
              style={{ marginTop: "0.4rem" }}
              value={s.files}
              disabled={!waiting}
              placeholder="one file path per line"
              aria-label={`Files of step ${i + 1}`}
              onChange={(e) => change(i, { files: e.target.value })}
            />
          )}
        </div>
      ))}

      {waiting && (
        <div className="row">
          <button className="btn secondary small" onClick={add}>
            + Add step
          </button>
          <button
            className="btn small"
            disabled={!dirty || busy}
            onClick={() =>
              run(
                () =>
                  api.editAgentPlan(projectId, task.id, {
                    steps: fromDraft(draft),
                    summary: plan.summary,
                  }),
                "Plan saved as a new version.",
              )
            }
          >
            Save edits
          </button>
          <button className="btn secondary small" onClick={showMarkdown}>
            Download plan.md
          </button>
          <span className="hint">
            Steps run in the order shown; each follows the one before it.
          </span>
        </div>
      )}

      {plan.risks?.length > 0 && (
        <>
          <b className="small">Risks</b>
          <ul className="agent-list">
            {plan.risks.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        </>
      )}

      {waiting && (
        <div className="agent-section">
          {needed.length > 0 && (
            <div className="alert warn">
              <b>These steps need your explicit OK:</b>
              {needed.map((a, i) => (
                <label className="check" key={`${a.step}-${i}`}>
                  <input
                    type="checkbox"
                    checked={Boolean(ack[a.step])}
                    onChange={(e) =>
                      setAck((x) => ({ ...x, [a.step]: e.target.checked }))
                    }
                  />
                  <span className="small">
                    Step {a.step.slice(1)} ({a.kind}): {a.reason}
                  </span>
                </label>
              ))}
            </div>
          )}
          <label className="label" htmlFor="approve-note">
            Note (optional)
          </label>
          <input
            id="approve-note"
            className="field"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
          {dirty && <p className="hint">Save your edits before approving.</p>}
          <div className="row" style={{ marginTop: "0.6rem" }}>
            <button
              className="btn"
              disabled={!canApprove}
              onClick={() =>
                run(
                  () =>
                    api.approveAgentPlan(projectId, task.id, {
                      version: plan.version,
                      acknowledged: Object.keys(ack).filter((k) => ack[k]),
                      note,
                    }),
                  "Plan approved.",
                )
              }
            >
              Approve plan v{plan.version}
            </button>
            <input
              className="field grow"
              placeholder="Reason, if you reject this plan"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              aria-label="Rejection reason"
            />
            <button
              className="btn danger"
              disabled={busy || !reason.trim()}
              onClick={() =>
                run(
                  () => api.rejectAgentPlan(projectId, task.id, reason),
                  "Plan rejected.",
                )
              }
            >
              Reject
            </button>
          </div>
        </div>
      )}

      {!waiting && (
        <p className="small muted agent-section">
          Decision: <b>{task.status.toLowerCase()}</b>
          {task.decisionNote && <> — {task.decisionNote}</>}
        </p>
      )}
    </div>
  );
}
