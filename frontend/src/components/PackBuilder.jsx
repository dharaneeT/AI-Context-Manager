import { useState } from "react";
import { api } from "../api.js";
import { useAsync } from "../useAsync";
import { toast } from "../toast.js";
import TaskResult from "./TaskResult";

const PRESETS = [2000, 4000, 8000, 16000];

export default function PackBuilder({ projectId, taskId, onCreated }) {
  const llm = useAsync(() => api.llmStatus(), []);
  const [task, setTask] = useState("");
  const [maxTokens, setMaxTokens] = useState(4000);
  const [wantAnswer, setWantAnswer] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const canAnswer = Boolean(llm.data?.configured);
  const answer = wantAnswer && canAnswer;

  async function submit(event) {
    event?.preventDefault();
    if (!task.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      const created = await api.createTask(projectId, {
        task,
        maxTokens,
        answer,
      });
      onCreated(created.id);
      toast("Task submitted. Building the pack…", "success");
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  function onKeyDown(event) {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) submit();
  }

  return (
    <>
      <form onSubmit={submit} className="card">
        <label className="label" htmlFor="task">
          What do you want to do?
        </label>
        <textarea
          id="task"
          className="field"
          placeholder="Describe the task, e.g. “Fix the authentication timeout issue”"
          value={task}
          onChange={(e) => setTask(e.target.value)}
          onKeyDown={onKeyDown}
          required
        />
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span className="hint">Tip: press Ctrl/⌘ + Enter to build.</span>
          <span className="hint">
            {task.length.toLocaleString()} characters
          </span>
        </div>

        <div
          className="grid"
          style={{
            gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))",
            marginTop: "1rem",
            gap: "1.25rem",
          }}
        >
          <div>
            <label className="label" htmlFor="budget">
              Token budget: <b>{maxTokens.toLocaleString()}</b>
            </label>
            <input
              id="budget"
              type="range"
              min="1000"
              max="30000"
              step="500"
              value={maxTokens}
              onChange={(e) => setMaxTokens(Number(e.target.value))}
            />
            <div className="chips" style={{ marginTop: "0.4rem" }}>
              {PRESETS.map((p) => (
                <button
                  key={p}
                  type="button"
                  className={`chip ${maxTokens === p ? "active" : ""}`}
                  onClick={() => setMaxTokens(p)}
                >
                  {p / 1000}k
                </button>
              ))}
            </div>
          </div>

          <label
            className={`check ${canAnswer ? "" : "disabled"}`}
            title={
              canAnswer
                ? ""
                : "Start Ollama on this machine, then reload this page"
            }
          >
            <input
              type="checkbox"
              checked={answer}
              disabled={!canAnswer}
              onChange={(e) => setWantAnswer(e.target.checked)}
            />
            <span>
              <b className="small">Also ask the local model to answer</b>
              <br />
              <span className="hint">
                Runs on your PC and can take a few minutes. Needs a budget of
                5,000 or less.
              </span>
            </span>
          </label>
        </div>

        {answer && maxTokens > 5000 && (
          <div className="alert warn">
            With a budget above 5,000 the pack is too big for the local model.
            You will get the pack but no answer.
          </div>
        )}
        {error && <div className="alert error">{error}</div>}

        <div className="row" style={{ marginTop: "1rem" }}>
          <button className="btn" disabled={busy || !task.trim()}>
            {busy ? (
              <>
                <span
                  className="spinner"
                  style={{ margin: 0, borderTopColor: "currentColor" }}
                />
                Submitting…
              </>
            ) : (
              "Build context pack"
            )}
          </button>
        </div>
      </form>

      {taskId ? (
        <TaskResult key={taskId} projectId={projectId} taskId={taskId} />
      ) : (
        <div className="empty">
          <b>No context pack yet</b>
          Describe a task above. ContextLayer picks the files that matter and
          fits them into your token budget.
        </div>
      )}
    </>
  );
}
