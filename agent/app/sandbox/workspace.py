"""A private copy of the project where the agent may change files. Your real project is never written to.

The copy contains every non-ignored file (including your uncommitted changes), and its own git repository whose first
commit is the baseline, so `diff()` always shows exactly what the agent changed, whatever state your own repo is in."""
from __future__ import annotations

import os
import shutil
import stat
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from app.core.errors import AgentError, NotFound

GIT = ["git", "-c", "user.name=contextlayer-agent", "-c", "user.email=agent@localhost", "-c", "core.autocrlf=false",
       "-c", "core.safecrlf=false", "-c", "core.quotepath=off"]
UNTRACKED = ["target/", "node_modules/", "dist/", "build/", ".gradle/", "__pycache__/", "*.pyc", ".agent_data/"]


def _is_link(path: str) -> bool:
    if os.path.islink(path):
        return True
    junction = getattr(os.path, "isjunction", None)  # Windows junctions (Python 3.12+)
    return bool(junction and junction(path))


def _unlink_links(root: Path) -> None:
    """Remove symlinks and junctions inside root WITHOUT following them, so a delete can never reach your project."""
    for dirpath, dirs, _files in os.walk(root, followlinks=False):
        for d in list(dirs):
            full = os.path.join(dirpath, d)
            if _is_link(full):
                dirs.remove(d)
                try:
                    os.rmdir(full)  # a junction or Windows directory link
                except OSError:
                    try:
                        os.unlink(full)
                    except OSError:
                        pass


def _make_writable_and_retry(func, path, _error) -> None:
    """Windows: git marks its object files read-only, which makes a plain rmtree skip them. Unlock and retry."""
    try:
        for target in (path, os.path.dirname(path)):
            os.chmod(target, stat.S_IWRITE | stat.S_IREAD | stat.S_IEXEC)
        func(path)
    except OSError:
        pass


def force_rmtree(path: Path) -> None:
    """Delete a workspace folder for real (read-only files, links), or say clearly why it could not be deleted."""
    if not path.exists():
        return
    _unlink_links(path)
    for _attempt in range(2):
        if sys.version_info >= (3, 12):
            shutil.rmtree(path, onexc=_make_writable_and_retry)
        else:
            shutil.rmtree(path, onerror=_make_writable_and_retry)
        if not path.exists():
            return
    raise AgentError(f"Could not delete the old workspace folder {path}. Close any editor or terminal that is open "
                     "inside it and try again.", 409)


def workspace_dir(settings, run_id: int) -> Path:
    return Path(settings.data_dir) / "workspaces" / str(run_id)


def tree_of(meta: dict) -> Path:
    return Path(meta["path"])


def _git(tree: Path, *args: str, check: bool = True) -> str:
    try:
        out = subprocess.run([*GIT, *args], cwd=tree, capture_output=True, text=True, encoding="utf-8",
                             errors="replace", timeout=120)
    except (OSError, subprocess.SubprocessError) as e:
        raise AgentError(f"git is required for the agent workspace: {e}", 503) from e
    if check and out.returncode != 0:
        raise AgentError(f"git {args[0]} failed in the workspace: {out.stderr.strip()[:300]}", 500)
    return out.stdout


def _link_dependencies(source: Path, tree: Path, files: list) -> tuple:
    """Frontend builds need node_modules, which is far too big to copy: link the original folder instead."""
    linked, notes = [], []
    for rel in files:
        if rel.rsplit("/", 1)[-1] != "package.json":
            continue
        module = rel.rsplit("/", 1)[0] if "/" in rel else ""
        src = source / module / "node_modules"
        dst = tree / module / "node_modules"
        if not src.is_dir() or dst.exists():
            continue
        try:
            os.symlink(src, dst, target_is_directory=True)
        except OSError:
            made = False
            if sys.platform == "win32":  # a junction needs no administrator rights
                made = subprocess.run(["cmd", "/c", "mklink", "/J", str(dst), str(src)],
                                      capture_output=True).returncode == 0
            if not made:
                notes.append(f"Could not link {src}: frontend commands in '{module or '.'}' may fail")
                continue
        linked.append(f"{module + '/' if module else ''}node_modules")
    return linked, notes

WRAPPER_FILES = {"mvnw", "mvnw.cmd", "gradlew", "gradlew.bat"}
SKIP_WALK = {".git", "node_modules", "target", "build", "dist", ".idea", ".vscode", ".gradle", ".agent_data", "__pycache__"}


def _copy_one(source: Path, tree: Path, file: Path, copied: list) -> None:
    rel = file.relative_to(source)
    try:
        (tree / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(file, tree / rel)
        copied.append(rel.as_posix())
    except OSError:
        pass


def _copy_build_tools(source: Path, tree: Path) -> list:
    """The Maven/Gradle wrapper scripts and the .mvn folder are not source code, so the file lister ignores them, but
    the build commands need them. Copy them (small files only) so `mvnw.cmd compile` works inside the workspace."""
    copied: list = []
    for dirpath, dirs, names in os.walk(source):
        here = Path(dirpath)
        depth = len(here.relative_to(source).parts)
        if here.name == ".mvn":
            for f in here.rglob("*"):
                if f.is_file() and f.stat().st_size < 5_000_000:
                    _copy_one(source, tree, f, copied)
            dirs[:] = []
            continue
        dirs[:] = [d for d in dirs if d not in SKIP_WALK] if depth < 4 else []
        for name in names:
            if name in WRAPPER_FILES:
                _copy_one(source, tree, here / name, copied)
    return copied


def create_workspace(settings, run_id: int, source: Path, files: list, source_state: dict) -> dict:
    """Copy the project, make the baseline commit, link dependencies. Replaces any earlier workspace of this run."""
    base = workspace_dir(settings, run_id)
    force_rmtree(base)
    tree = base / "tree"
    tree.mkdir(parents=True)
    for rel in files:
        target = tree / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            shutil.copy2(source / rel, target)
        except OSError:
            continue  # a file vanished while copying: not worth failing for
    _copy_build_tools(source, tree)
    _git(tree, "init", "-q")
    exclude = tree / ".git" / "info" / "exclude"
    exclude.parent.mkdir(parents=True, exist_ok=True)
    exclude.write_text("\n".join(UNTRACKED) + "\n", encoding="utf-8")
    _git(tree, "add", "-A")
    _git(tree, "commit", "-q", "--allow-empty", "-m", "baseline")
    baseline = _git(tree, "rev-parse", "HEAD").strip()
    linked, notes = _link_dependencies(source, tree, files)
    return {"path": str(tree), "baseline": baseline, "file_count": len(files), "linked": linked, "notes": notes,
            "source_commit": source_state.get("base_commit"), "source_uncommitted": source_state.get("uncommitted_files", 0),
            "created_at": datetime.now(timezone.utc).isoformat()}


def require_workspace(run: dict) -> Path:
    meta = run.get("workspace")
    if not meta or not Path(meta["path"]).is_dir():
        raise NotFound(f"Run {run['id']} has no workspace yet. Create one first.")
    return tree_of(meta)


def changed_files(tree: Path, base: str = "HEAD") -> list:
    """What differs from `base` (pass the baseline commit once the agent has made checkpoint commits): [{'path', 'status': A|M|D, 'added', 'removed'}]."""
    _git(tree, "add", "-A", "-N", ".")  # intent-to-add: new files show up in the diff without being committed
    status = {}
    for line in _git(tree, "diff", "--name-status", "--no-renames", base).splitlines():
        code, _, path = line.partition("\t")
        status[path] = code[:1]
    out = []
    for line in _git(tree, "diff", "--numstat", "--no-renames", base).splitlines():
        a, r, path = line.split("\t", 2)
        out.append({"path": path, "status": status.get(path, "M"),
                    "added": int(a) if a.isdigit() else 0, "removed": int(r) if r.isdigit() else 0})
    return sorted(out, key=lambda c: c["path"])


def diff_text(tree: Path, base: str = "HEAD") -> str:
    _git(tree, "add", "-A", "-N", ".")
    return _git(tree, "diff", "--no-color", "--no-renames", base)


def checkpoint(tree: Path, message: str) -> str:
    """Save the current state as a commit in the workspace's own repository and return its id."""
    _git(tree, "add", "-A")
    _git(tree, "commit", "-q", "--allow-empty", "-m", message)
    return _git(tree, "rev-parse", "HEAD").strip()


def rollback_to(tree: Path, commit: str) -> None:
    """Return every file to how it was at `commit` (a checkpoint or the baseline) and drop later checkpoints."""
    _git(tree, "reset", "-q", "--hard", commit)
    _git(tree, "clean", "-q", "-fd")


def reset_workspace(tree: Path, baseline: str = "HEAD") -> None:
    """Throw away every change ('start over'): back to the baseline commit."""
    rollback_to(tree, baseline)


def destroy_workspace(settings, run_id: int, linked=()) -> None:
    """Delete the workspace. Linked node_modules are unlinked FIRST so nothing can ever follow them into your project."""
    base = workspace_dir(settings, run_id)
    if not base.exists():
        return
    tree = base / "tree"
    for rel in linked:
        link = tree / rel
        try:
            if link.is_symlink():
                link.unlink()
            elif link.exists():  # a Windows junction: rmdir removes the junction, never its target
                os.rmdir(link)
        except OSError:
            pass
    force_rmtree(base)
