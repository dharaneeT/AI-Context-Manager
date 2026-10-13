import { useState } from "react";
import { api } from "../api.js";
import { useAsync } from "../useAsync";
import TaskResult from "./TaskResult";

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
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await api.createTask(projectId, {
        task,
        maxTokens,
        answer,
      });
      onCreated(created.id);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <form onSubmit={submit}>
        <textarea
          className="field"
          placeholder="Describe the task, e.g. “Fix the authentication timeout issue”"
          value={task}
          onChange={(e) => setTask(e.target.value)}
          required
        />
        <div className="row" style={{ marginTop: "0.6rem" }}>
          <label className="small">
            Token budget: <b>{maxTokens.toLocaleString()}</b>
            <br />
            <input
              type="range"
              min="1000"
              max="30000"
              step="500"
              value={maxTokens}
              onChange={(e) => setMaxTokens(Number(e.target.value))}
            />
          </label>
          <label
            className="small"
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
            />{" "}
            Also ask the local model to answer
            <br />
            <span className="muted">
              runs on your PC and can take a few minutes. Needs a budget of
              5,000 or less
            </span>
          </label>
          <button className="btn" disabled={busy}>
            {busy ? "Submitting…" : "Build context pack"}
          </button>
        </div>
        {answer && maxTokens > 5000 && (
          <p className="small" style={{ color: "var(--warn)" }}>
            With a budget above 5,000 the pack is too big for the local model.
            You will get the pack but no answer.
          </p>
        )}
        {error && <p className="error">{error}</p>}
      </form>

      {taskId && (
        <TaskResult key={taskId} projectId={projectId} taskId={taskId} />
      )}
    </>
  );
}
