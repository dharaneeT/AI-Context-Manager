"""Turn search results into candidate FILES (not chunks): one scored entry per file, with the matched line ranges."""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Candidate:
    path: str
    score: float = 0.0
    reasons: list = field(default_factory=list)
    sources: set = field(default_factory=set)
    ranges: list = field(default_factory=list)  # matched (start_line, end_line) pairs from chunk search

    def add(self, score: float, reason: str, source: str) -> None:
        self.score = min(1.0, self.score + score)
        if reason not in self.reasons:
            self.reasons.append(reason)
        self.sources.add(source)


    def public(self) -> dict:
        """Small JSON form saved in the run record, so later stages can be resumed without searching again."""
        return {"path": self.path, "score": round(self.score, 4), "reasons": self.reasons[:6],
                "sources": sorted(self.sources), "ranges": [list(r) for r in self.ranges[:8]]}

    @classmethod
    def from_public(cls, data: dict) -> "Candidate":
        return cls(data["path"], data.get("score", 0.0), list(data.get("reasons", [])), set(data.get("sources", [])),
                   [tuple(r) for r in data.get("ranges", [])])


def get_candidate(cands: dict, path: str) -> Candidate:
    return cands.setdefault(path, Candidate(path))


def from_search(ranking: dict, hybrid: dict) -> dict:
    """Step 1: the existing ContextLayer ranking and hybrid search are the base evidence."""
    cands: dict = {}
    files = ranking.get("files", [])
    top = max((f.get("score", 0.0) for f in files), default=0.0) or 1.0
    for f in files:
        c = get_candidate(cands, f["path"])
        c.add(0.8 * f.get("score", 0.0) / top, "ranked match for the task", "rank")
        for reason in (f.get("reasons") or [])[:3]:
            c.add(0.0, reason, "rank")
        for ch in f.get("chunks") or []:
            c.ranges.append((int(ch["startLine"]), int(ch["endLine"])))
    hf = hybrid.get("files", [])
    htop = max((f.get("score", 0.0) for f in hf), default=0.0) or 1.0
    for f in hf:
        c = get_candidate(cands, f["path"])
        c.add(0.2 * f.get("score", 0.0) / htop, "hybrid search match", "hybrid")
    for ch in hybrid.get("chunks", []):
        c = get_candidate(cands, ch["path"])
        c.ranges.append((int(ch["startLine"]), int(ch["endLine"])))
    return cands