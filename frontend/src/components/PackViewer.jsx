import { useState } from "react";

function download(filename, text) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/markdown" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export default function PackViewer({ task }) {
  const [copied, setCopied] = useState(false);
  const pack = task.pack;
  if (!pack) return <p className="muted">No pack available.</p>;

  const used = Math.min(
    100,
    Math.round((pack.packTokens / pack.targetTokens) * 100),
  );

  async function copy() {
    try {
      await navigator.clipboard.writeText(pack.markdown);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard access can be blocked; the Download button still works
    }
  }

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
        <div className="bar" style={{ margin: "0.5rem 0" }}>
          <div
            className={pack.withinBudget ? "" : "over"}
            style={{ width: `${used}%` }}
          />
        </div>
        <p className="small muted" style={{ margin: 0 }}>
          {pack.reductionVsProjectPercent}% smaller than sending the whole
          project (~
          {pack.projectTokens.toLocaleString()} tokens)
          {!pack.semanticUsed && " · keyword-only ranking"}
        </p>
        {pack.notes.map((n) => (
          <p
            key={n}
            className="small"
            style={{ margin: "0.3rem 0 0", color: "var(--warn)" }}
          >
            {n}
          </p>
        ))}
      </div>

      {task.answer && (
        <div className="card">
          <h3>Answer</h3>
          <pre className="block" style={{ maxHeight: "none" }}>
            {task.answer.text}
          </pre>
          <p className="small muted">
            {task.answer.model} · {task.answer.inputTokens?.toLocaleString()} in
            / {task.answer.outputTokens?.toLocaleString()} out
          </p>
        </div>
      )}
      {task.answerError && (
        <p className="error small">No answer: {task.answerError}</p>
      )}

      <h3>Why these files</h3>
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
          {pack.items.map((i) => (
            <tr key={i.path}>
              <td>
                <code>{i.path}</code>
              </td>
              <td>
                <span className="badge">
                  {i.level.toLowerCase().replace("_", " ")}
                </span>
              </td>
              <td>{i.tokens.toLocaleString()}</td>
              <td className="score">
                <div className="bar">
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
        </tbody>
      </table>

      <div className="row" style={{ margin: "1rem 0 0.5rem" }}>
        <h3 style={{ margin: 0, flex: 1 }}>The pack</h3>
        <button className="btn secondary" onClick={copy}>
          {copied ? "Copied ✓" : "Copy"}
        </button>
        <button
          className="btn secondary"
          onClick={() => download(`context-pack-${task.id}.md`, pack.markdown)}
        >
          Download .md
        </button>
      </div>
      <pre className="block">{pack.markdown}</pre>
    </>
  );
}
