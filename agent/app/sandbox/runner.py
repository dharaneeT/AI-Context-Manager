"""Runs ONE named build or test command inside the workspace, with limits.

The agent never runs a command the model wrote. It runs the named commands from the project profile (Day 7), after
these checks: the name exists and is available, the program is on a short allowlist, every argument is plain (no
shell characters, no '..'), the folder is inside the workspace, the environment is rebuilt from a short whitelist
(your API keys and passwords are NOT passed on), time and output are limited, and the whole process tree is killed
on timeout.

Honest limit: this is a restricted environment, not a virtual machine. The command still runs with your Windows
user's rights and your network, because it executes your own project's build scripts."""
from __future__ import annotations

import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from collections import deque
from pathlib import Path

from app.context.redact import redact
from app.core.errors import AgentError

ALLOWED_PROGRAMS = {"mvn", "mvnw", "mvnw.cmd", "npm", "npm.cmd", "python", "python3", "python.exe", "py"}
SAFE_ARG = re.compile(r"^[\w:./\\=+@,-]+$")
ENV_WHITELIST = {"PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR", "HOME", "USERPROFILE",
                 "APPDATA", "LOCALAPPDATA", "JAVA_HOME", "M2_HOME", "MAVEN_HOME", "NODE_HOME", "LANG", "LC_ALL",
                 "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMDATA", "OS", "PROCESSOR_ARCHITECTURE", "HOMEDRIVE",
                 "HOMEPATH", "NUMBER_OF_PROCESSORS"}
ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")


def clean_env(settings) -> dict:
    env = {k: v for k, v in os.environ.items() if k.upper() in ENV_WHITELIST}
    env.update({"CI": "true", "NO_COLOR": "1", "FORCE_COLOR": "0", "MAVEN_OPTS": settings.maven_opts,
                "NODE_OPTIONS": settings.node_opts})
    return env


class OutputBuffer:
    """Keeps the start and the end of the output (where errors usually are) and counts what it dropped."""

    def __init__(self, limit: int):
        self.head_limit, self.tail_limit = limit // 4, limit - limit // 4
        self.head, self.tail, self.head_len, self.tail_len, self.dropped = [], deque(), 0, 0, 0

    def add(self, text: str) -> None:
        if self.head_len < self.head_limit:
            room = self.head_limit - self.head_len
            self.head.append(text[:room])
            self.head_len += min(len(text), room)
            text = text[room:]
        if not text:
            return
        self.tail.append(text)
        self.tail_len += len(text)
        while self.tail_len > self.tail_limit and self.tail:
            first = self.tail[0]
            over = self.tail_len - self.tail_limit
            if len(first) <= over:
                self.tail.popleft()
                self.tail_len -= len(first)
                self.dropped += len(first)
            else:
                self.tail[0] = first[over:]
                self.tail_len -= over
                self.dropped += over

    def text(self) -> str:
        mid = f"\n...[{self.dropped} characters omitted]...\n" if self.dropped else ""
        return "".join(self.head) + mid + "".join(self.tail)


def _kill_tree(proc: subprocess.Popen) -> None:
    try:
        if sys.platform == "win32":
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True, timeout=30)
        else:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
    except (OSError, subprocess.SubprocessError):
        proc.kill()


def _resolve_program(name: str, cwd: Path, env: dict) -> str | None:
    """The wrapper (mvnw.cmd, ./mvnw) lives in the module folder; everything else is looked up on PATH."""
    bare = name[2:] if name.startswith("./") else name
    local = cwd / bare
    if local.is_file():
        return str(local)
    return shutil.which(bare, path=env.get("PATH"))


def validate_command(profile: dict, key: str, tree: Path) -> dict:
    """Returns the command definition, or raises AgentError saying exactly why it is refused."""
    cmd = (profile.get("commands") or {}).get(key)
    if not cmd:
        raise AgentError(f"Unknown command '{key}'. Allowed: {sorted((profile.get('commands') or {}))}", 400)
    if not cmd.get("available"):
        raise AgentError(f"Command '{key}' is not available: {cmd.get('reason') or 'unknown reason'}", 409)
    argv = cmd["argv"]
    program = argv[0].replace("\\", "/").rsplit("/", 1)[-1].lower()
    if program not in ALLOWED_PROGRAMS:
        raise AgentError(f"'{argv[0]}' is not on the allowed program list", 403)
    for arg in argv[1:]:
        if not SAFE_ARG.match(arg) or ".." in arg.replace("\\", "/").split("/"):
            raise AgentError(f"Argument '{arg}' is not allowed", 403)
    cwd = (tree / cmd["cwd"]).resolve()
    if cwd != tree.resolve() and tree.resolve() not in cwd.parents:
        raise AgentError("The command folder is outside the workspace", 403)
    return {**cmd, "cwd_abs": cwd}


def run_command(settings, tree: Path, profile: dict, key: str, timeout: int | None = None) -> dict:
    cmd = validate_command(profile, key, tree)
    env = clean_env(settings)
    cwd = cmd["cwd_abs"]
    program = _resolve_program(cmd["argv"][0], cwd, env)
    started = time.monotonic()
    shown = " ".join(cmd["argv"])
    if program is None:
        return {"command": key, "display": shown, "cwd": cmd["cwd"], "exit_code": -1, "timed_out": False,
                "seconds": 0.0, "output": f"Program not found: {cmd['argv'][0]}. Is it installed and on PATH "
                                          f"(or is the Maven wrapper missing)?", "output_truncated": False,
                "environment_problem": True}
    flags = {}
    if sys.platform == "win32":
        flags["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        flags["start_new_session"] = True
    proc = subprocess.Popen([program, *cmd["argv"][1:]], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **flags)
    buf = OutputBuffer(settings.max_output_chars)

    def pump():
        for chunk in iter(lambda: proc.stdout.read(4096), b""):
            buf.add(chunk.decode("utf-8", errors="replace"))

    reader = threading.Thread(target=pump, daemon=True)
    reader.start()
    timed_out = False
    try:
        proc.wait(timeout=timeout or settings.command_timeout_seconds)
    except subprocess.TimeoutExpired:
        timed_out = True
        _kill_tree(proc)
        proc.wait()
    reader.join(timeout=10)
    proc.stdout.close()
    text, _ = redact(ANSI.sub("", buf.text()).replace("\r\n", "\n"))
    return {"command": key, "display": shown, "cwd": cmd["cwd"], "exit_code": -9 if timed_out else proc.returncode,
            "timed_out": timed_out, "seconds": round(time.monotonic() - started, 2), "output": text,
            "output_truncated": buf.dropped > 0, "environment_problem": False}
