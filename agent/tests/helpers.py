"""Shared test fixtures: a throwaway project folder and a fake Spring backend."""
from __future__ import annotations

import json
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from app.core.config import Settings

PROJECT_FILES = {
    "backend/pom.xml": "<project><artifactId>demo</artifactId></project>\n",
    "backend/src/main/resources/application.properties": "server.port=8080\ndb.password=hunter2hunter2\n",
    "backend/src/main/java/com/demo/review/ReviewService.java":
        "package com.demo.review;\n\npublic class ReviewService {\n    public int rate(int stars) {\n        return stars;\n    }\n}\n",
    "backend/src/main/java/com/demo/review/ReviewController.java":
        "package com.demo.review;\n\nimport com.demo.review.ReviewService;\n\npublic class ReviewController {\n    ReviewService s;\n}\n",
    "backend/src/test/java/com/demo/review/ReviewServiceTest.java":
        "package com.demo.review;\n\nclass ReviewServiceTest {\n}\n",
    "frontend/src/components/StarRating.jsx": "export default function StarRating() { return null; }\n",
    "frontend/node_modules/lib/index.js": "ignored();\n",
    "backend/target/classes/Old.class": "binary",
    ".env": "SECRET=abc\n",
    "docs/logo.png": "\x00\x01",
}


def make_project() -> Path:
    root = Path(tempfile.mkdtemp(prefix="cl_proj_"))
    for rel, text in PROJECT_FILES.items():
        target = root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    return root


def make_settings(**overrides) -> Settings:
    base = dict(shared_secret="s3cret", data_dir=Path(tempfile.mkdtemp(prefix="cl_data_")), pack_default_tokens=6000,
                llm_provider="mock")  # tests never touch a real model unless they ask for it
    base.update(overrides)
    return Settings(**base)


class FakeBackend:
    """Same method names as BackendClient, answering from fixed data (no network)."""

    def __init__(self, root: Path):
        self.root = root

    def health(self):
        return {"status": "UP"}

    def project(self, project_id):
        return {"id": project_id, "name": "demo", "rootPath": str(self.root)}

    def rank(self, project_id, task, limit=25):
        return {"keywords": ["rating"], "semanticUsed": True, "semanticNote": "", "files": [
            {"path": "backend/src/main/java/com/demo/review/ReviewService.java", "score": 0.9,
             "reasons": ["keyword match: rate"], "chunks": [{"startLine": 3, "endLine": 6}]},
            {"path": "frontend/src/components/StarRating.jsx", "score": 0.5, "reasons": ["name match"], "chunks": []},
            {"path": "gone/Deleted.java", "score": 0.4, "reasons": ["stale index"], "chunks": []},
        ]}

    def hybrid_search(self, project_id, query, limit=15):
        return {"chunks": [{"path": "backend/src/main/java/com/demo/review/ReviewService.java", "startLine": 4,
                            "endLine": 5, "score": 0.1}],
                "files": [{"path": "backend/src/main/java/com/demo/review/ReviewService.java", "score": 0.1}]}

    def dependencies(self, project_id, path):
        return []

    def symbols(self, project_id, path):
        if path.endswith("ReviewService.java"):
            return [{"kind": "CLASS", "qualifiedName": "com.demo.review.ReviewService", "annotations": "@Service",
                     "path": path, "startLine": 3, "endLine": 7}]
        if path.endswith("ReviewController.java"):
            return [{"kind": "CLASS", "qualifiedName": "com.demo.review.ReviewController",
                     "annotations": "@RestController", "path": path, "startLine": 5, "endLine": 7}]
        return []

    def dependents(self, project_id, path):
        if path.endswith("ReviewService.java"):
            return ["backend/src/main/java/com/demo/review/ReviewController.java"]
        return []


def serve(handler_fn):
    """Start a tiny HTTP server on a free port. handler_fn(method, path, body) -> (status, dict)."""
    class H(BaseHTTPRequestHandler):
        def _do(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length)) if length else None
            status, payload = handler_fn(self.command, self.path, body)
            raw = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        do_GET = do_POST = _do

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_port}"