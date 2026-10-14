import { useState } from "react";
import { toast } from "../toast.js";
import CopyButton from "./CopyButton";

function download(filename, text) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/markdown" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
  toast(`Downloaded ${filename}`, "success");
}

export default function PackViewer({ task }) {
  const [filter, setFilter] = useState("");
  const pack = task.pack;
  if (!pack)
    return (
      <div className="empty">
        <b>No pack available</b>
        This task finished without a context pack.
      </div>
    );

  const used = Math.min(
    100,
    Math.round((pack.packTokens / pack.targetTokens) * 100),
  );
  const items = pack.items.filter((i) =>
    i.path.toLowerCase().includes(filter.trim().toLowerCase()),
  );

  return (
    <>
      <div className="card">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <b>
            {pack.packTokens.toLocaleString()} /{" "}
            {pack.targetTokens.toLocaleString()} tokens
          </b>
          <span className={`pill ${pack.withinBudget ? "ok" : "bad"}`}>
            {pack.withinBudget ? "within budget" : "over budget"}
          </span>
        </div>
        <div className="bar" style={{ margin: "0.6rem 0" }}>
          <div
            className={pack.withinBudget ? "" : "over"}
            style={{ width: `${used}%` }}
          />
        </div>
        <p className="small muted">
          {pack.reductionVsProjectPercent}% smaller than sending the whole
          project (~{pack.projectTokens.toLocaleString()} tokens)
          {!pack.semanticUsed && " · keyword-only ranking"}
        </p>
        {pack.notes.map((n) => (
          <div key={n} className="alert warn small">
            {n}
          </div>
        ))}
      </div>

      {task.answer && (
        <div className="card">
          <div className="section-head" style={{ margin: "0 0 0.6rem" }}>
            <h3>Answer</h3>
            <CopyButton text={task.answer.text} />
          </div>
          <pre className="block" style={{ maxHeight: "none" }}>
            {task.answer.text}
          </pre>
          <p className="small muted" style={{ marginTop: "0.5rem" }}>
            {task.answer.model} · {task.answer.inputTokens?.toLocaleString()} in
            / {task.answer.outputTokens?.toLocaleString()} out
          </p>
        </div>
      )}
      {task.answerError && (
        <div className="alert error small">No answer: {task.answerError}</div>
      )}

      <div className="section-head">
        <h3>
          Why these files{" "}
          <span className="muted small">({pack.items.length})</span>
        </h3>
        {pack.items.length > 6 && (
          <input
            className="field"
            style={{ maxWidth: 220 }}
            placeholder="Filter files…"
            aria-label="Filter files"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        )}
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>File</th>
              <th>Detail</th>
              <th>Tokens</th>
              <th>Score</th>
              <th>Reasons</th>
            </tr>
          </thead>
          <tbody>
            {items.map((i) => (
              <tr key={i.path}>
                <td>
                  <code>{i.path}</code>
                </td>
                <td>
                  <span className="badge">
                    {i.level.toLowerCase().replace("_", " ")}
                  </span>
                </td>
                <td className="num">{i.tokens.toLocaleString()}</td>
                <td className="score" title={`${Math.round(i.score * 100)}%`}>
                  <div className="bar thin">
                    <div style={{ width: `${Math.round(i.score * 100)}%` }} />
                  </div>
                </td>
                <td>
                  <ul className="reasons">
                    {i.reasons.slice(0, 4).map((r) => (
                      <li key={r}>{r}</li>
                    ))}
                  </ul>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={5} className="muted">
                  No files match “{filter}”.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="section-head">
        <h3>The pack</h3>
        <CopyButton text={pack.markdown} />
        <button
          className="btn secondary small"
          onClick={() => download(`context-pack-${task.id}.md`, pack.markdown)}
        >
          Download .md
        </button>
      </div>
      <pre className="block">{pack.markdown}</pre>
    </>
  );
}
