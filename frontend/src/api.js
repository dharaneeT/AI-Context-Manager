const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

async function request(path, options = {}) {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
    },
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Request failed with status ${response.status}`);
  }

  if (response.status === 204) return null;

  const contentType = response.headers.get("content-type");
  if (contentType?.includes("application/json")) {
    return response.json();
  }

  return response.text();
}

const json = (method, body) => ({
  method,
  body: JSON.stringify(body),
});

const projectPath = (id) => `/api/projects/${id}`;
const agentPath = (id, taskId = "") =>
  `${projectPath(id)}/agent/tasks${taskId ? `/${taskId}` : ""}`;

export const api = {
  health: () => request("/api/health"),
  projects: () => request("/api/projects"),
  createProject: (body) => request("/api/projects", json("POST", body)),
  deleteProject: (id) => request(projectPath(id), { method: "DELETE" }),

  stats: (id) => request(`${projectPath(id)}/stats`),
  overview: (id) => request(`${projectPath(id)}/overview`),
  embeddingStatus: (id) => request(`${projectPath(id)}/embeddings/status`),
  llmStatus: () => request("/api/llm/status"),

  refresh: (id, force) =>
    request(`${projectPath(id)}/index?force=${force}`, { method: "POST" }),
  refreshSummaries: (id, mode) =>
    request(
      `${projectPath(id)}/summaries/refresh?mode=${encodeURIComponent(mode)}`,
      {
        method: "POST",
      },
    ),

  search: (id, q) =>
    request(`${projectPath(id)}/search?q=${encodeURIComponent(q)}`),

  createTask: (id, body) =>
    request(`${projectPath(id)}/tasks`, json("POST", body)),
  tasks: (id) => request(`${projectPath(id)}/tasks`),
  task: (id, taskId) => request(`${projectPath(id)}/tasks/${taskId}`),
  deleteTask: (id, taskId) =>
    request(`${projectPath(id)}/tasks/${taskId}`, { method: "DELETE" }),

  // ---- agent (Days 2 to 10) ----
  agentStatus: () => request("/api/agent/status"),
  agentTasks: (id) => request(agentPath(id)),
  agentTask: (id, taskId) => request(agentPath(id, taskId)),
  createAgentTask: (id, body) => request(agentPath(id), json("POST", body)),
  deleteAgentTask: (id, taskId) =>
    request(agentPath(id, taskId), { method: "DELETE" }),
  cancelAgentTask: (id, taskId) =>
    request(`${agentPath(id, taskId)}/cancel`, { method: "POST" }),
  answerAgentTask: (id, taskId, answers) =>
    request(`${agentPath(id, taskId)}/answers`, json("POST", { answers })),
  agentPack: (id, taskId) => request(`${agentPath(id, taskId)}/pack.md`),
  agentPlanMarkdown: (id, taskId) =>
    request(`${agentPath(id, taskId)}/plan.md`),
  editAgentPlan: (id, taskId, body) =>
    request(`${agentPath(id, taskId)}/plan`, json("PUT", body)),
  approveAgentPlan: (id, taskId, body) =>
    request(`${agentPath(id, taskId)}/approve`, json("POST", body)),
  rejectAgentPlan: (id, taskId, reason) =>
    request(`${agentPath(id, taskId)}/reject`, json("POST", { reason })),
};
