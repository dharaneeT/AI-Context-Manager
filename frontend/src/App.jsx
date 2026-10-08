import { useEffect, useState } from "react";
import "./App.css";

function App() {
  const [health, setHealth] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    fetch("/api/health")
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(setHealth)
      .catch((err) => setError(err.message));
  }, []);

  return (
    <main className="app">
      <h1>ContextLayer</h1>
      <p className="tagline">Task-specific context packs for AI agents</p>

      <section className="card">
        <h2>Backend status</h2>
        {error && <p className="bad">Cannot reach backend: {error}</p>}
        {!error && !health && <p>Checking…</p>}
        {health && (
          <ul>
            <li>
              Status:{" "}
              <strong className={health.status === "UP" ? "ok" : "bad"}>
                {health.status}
              </strong>
            </li>
            <li>Database: {health.database ? "connected" : "DOWN"}</li>
            <li>Application: {health.application}</li>
          </ul>
        )}
      </section>
    </main>
  );
}

export default App;
