"""EdgeFabric Codebase Indexer.

Builds a lightweight, deterministic, machine + human readable map of the repo
under .codemie/codebase/ so agents do not have to grep/view the whole codebase
on every run.

Usage:
    py mcp-servers/codebase_indexer.py            # full re-index
    py mcp-servers/codebase_indexer.py --incremental
    py mcp-servers/codebase_indexer.py --check    # exit 1 if stale (CI gate)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / ".codemie" / "codebase"
MODULES_DIR = OUT_DIR / "modules"
STATE_FILE = OUT_DIR / ".index-state.json"

JAVA_MODULES = ["loadbalancer", "caching", "registry", "consistent-hashing", "testing_edgefabric"]
UI_MODULES = ["ui"]
SOURCE_EXTS = {".java", ".ts", ".tsx", ".js", ".jsx"}
SKIP_DIRS = {"target", "node_modules", "dist", "build", ".git", ".idea", "__pycache__",
             ".venv", "allure-results", ".allure", ".m2repo", "coverage"}

PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
# Linear-time: no nested quantifiers. Annotations on prior lines are picked up
# separately via a window scan in parse_java().
TYPE_RE = re.compile(
    r"\b(class|interface|enum|record)\s+([A-Z]\w*)\b",
)
SPRING_STEREO_RE = re.compile(
    r"@(RestController|Controller|Service|Repository|Component|Configuration|ConfigurationProperties)\b"
)
MAPPING_RE = re.compile(
    r"@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\s*\(?\s*"
    r"(?:value\s*=\s*)?(?:\{\s*)?[\"']([^\"']+)[\"']"
)
# Linear-time public-method detector: line must start with `public` and contain
# `name(` somewhere later. We grab the last identifier before `(`.
METHOD_RE = re.compile(
    r"^[ \t]*public\b[^\n;{}=]{0,200}?\b([a-z]\w*)\s*\(",
    re.M,
)
TS_EXPORT_RE = re.compile(
    r"^\s*export\s+(?:default\s+)?(class|function|interface|const|type|enum)\s+(\w+)", re.M
)


@dataclass
class FileEntry:
    path: str
    module: str
    language: str
    sha256: str
    size: int
    package: str = ""
    types: list[dict] = field(default_factory=list)
    endpoints: list[dict] = field(default_factory=list)
    public_methods: list[dict] = field(default_factory=list)


def detect_module(rel: Path) -> str:
    parts = rel.parts
    if not parts:
        return "(root)"
    head = parts[0]
    if head in JAVA_MODULES or head in UI_MODULES:
        return head
    return head


def iter_source_files() -> Iterable[Path]:
    """Yield source files tracked by git (ensures consistency across environments)."""
    result = subprocess.run(
        ["git", "ls-files", "--cached"],
        cwd=REPO_ROOT, capture_output=True, text=True
    )
    if result.returncode != 0:
        # Fallback to filesystem walk if git is unavailable
        yield from _iter_source_files_fs()
        return
    for line in result.stdout.strip().split("\n"):
        if not line:
            continue
        p = REPO_ROOT / line
        if p.suffix in SOURCE_EXTS and p.exists():
            yield p


def _iter_source_files_fs() -> Iterable[Path]:
    """Fallback: walk filesystem when git is not available."""
    for root, dirs, files in os.walk(REPO_ROOT):
        dirs[:] = [d for d in dirs
                   if d not in SKIP_DIRS
                   and not d.startswith(".")
                   and not d.startswith(".m2repo-")]
        for f in files:
            if Path(f).suffix in SOURCE_EXTS:
                yield Path(root) / f


def sha_of(p: Path) -> str:
    """Hash file content with line-ending normalization (CRLF → LF).

    This ensures the same hash on Windows (CRLF checkout) and Linux (LF checkout)
    so the CI freshness gate passes regardless of platform.
    """
    h = hashlib.sha256()
    content = p.read_bytes().replace(b"\r\n", b"\n")
    h.update(content)
    return h.hexdigest()


def line_of(text: str, idx: int) -> int:
    return text.count("\n", 0, idx) + 1


def parse_java(text: str) -> dict:
    pkg_m = PKG_RE.search(text)
    pkg = pkg_m.group(1) if pkg_m else ""
    types = []
    for m in TYPE_RE.finditer(text):
        kind, name = m.group(1), m.group(2)
        window = text[max(0, m.start() - 300): m.start()]
        stereos = sorted(set(SPRING_STEREO_RE.findall(window)))
        types.append({
            "kind": kind, "name": name,
            "line": line_of(text, m.start()), "stereotypes": stereos,
        })
    endpoints = []
    for m in MAPPING_RE.finditer(text):
        endpoints.append({
            "verb": m.group(1).replace("Mapping", "").upper(),
            "path": m.group(2), "line": line_of(text, m.start()),
        })
    methods = []
    seen = set()
    for m in METHOD_RE.finditer(text):
        name = m.group(1)
        if name in {"if", "while", "for", "switch", "return", "new", "throw", "catch"}:
            continue
        if name in seen:
            continue
        seen.add(name)
        methods.append({"name": name, "line": line_of(text, m.start())})
    return {"package": pkg, "types": types, "endpoints": endpoints, "public_methods": methods}


def parse_ts(text: str) -> dict:
    types = []
    for m in TS_EXPORT_RE.finditer(text):
        types.append({
            "kind": m.group(1), "name": m.group(2),
            "line": line_of(text, m.start()), "stereotypes": [],
        })
    return {"package": "", "types": types, "endpoints": [], "public_methods": []}


def parse_file(p: Path) -> FileEntry:
    rel = p.relative_to(REPO_ROOT)
    lang = "java" if p.suffix == ".java" else "typescript" if p.suffix in {".ts", ".tsx"} else "javascript"
    try:
        text = p.read_text(encoding="utf-8", errors="replace")
    except Exception:
        text = ""
    parsed = parse_java(text) if lang == "java" else parse_ts(text)
    return FileEntry(
        path=str(rel).replace("\\", "/"),
        module=detect_module(rel),
        language=lang,
        sha256=sha_of(p),
        size=p.stat().st_size,
        **parsed,
    )


def git_head() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, text=True
        ).strip()
    except Exception:
        return "(no-git)"


def build_index() -> dict:
    files = [parse_file(p) for p in sorted(iter_source_files())]
    files.sort(key=lambda f: f.path)
    return {
        "schema_version": 1,
        "git_head": git_head(),
        "stats": {
            "files": len(files),
            "java_files": sum(1 for f in files if f.language == "java"),
            "ts_files": sum(1 for f in files if f.language in {"typescript", "javascript"}),
            "total_bytes": sum(f.size for f in files),
            "modules": sorted({f.module for f in files}),
        },
        "files": [
            {
                "path": f.path, "module": f.module, "language": f.language,
                "size": f.size, "sha256": f.sha256, "package": f.package,
                "types": f.types, "endpoints": f.endpoints,
                "public_methods": f.public_methods,
            }
            for f in files
        ],
    }


def build_symbols(manifest: dict) -> dict:
    symbols: dict[str, list[dict]] = {}
    for f in manifest["files"]:
        for t in f["types"]:
            symbols.setdefault(t["name"], []).append({
                "kind": t["kind"], "file": f["path"], "line": t["line"],
                "module": f["module"], "package": f["package"],
                "stereotypes": t.get("stereotypes", []),
            })
    return {"schema_version": 1, "symbols": dict(sorted(symbols.items()))}


def write_module_md(module: str, manifest: dict) -> str:
    files = [f for f in manifest["files"] if f["module"] == module]
    if not files:
        return ""
    java_files = [f for f in files if f["language"] == "java"]
    ts_files = [f for f in files if f["language"] in {"typescript", "javascript"}]
    types = [(t["kind"], t["name"], f["path"], t["line"], t.get("stereotypes", []))
             for f in files for t in f["types"]]
    types.sort(key=lambda x: (x[0], x[1]))
    endpoints = [(e["verb"], e["path"], f["path"], e["line"])
                 for f in files for e in f["endpoints"]]
    endpoints.sort()
    controllers = [t for t in types if "RestController" in t[4] or "Controller" in t[4]]
    services = [t for t in types if "Service" in t[4]]
    configs = [t for t in types if "Configuration" in t[4] or "ConfigurationProperties" in t[4]]

    lines = [f"# Module: {module}", ""]
    lines.append(f"- Files: {len(files)} ({len(java_files)} Java, {len(ts_files)} TS/JS)")
    lines.append(f"- Total size: {sum(f['size'] for f in files):,} bytes")
    lines.append("")

    if controllers:
        lines += ["## REST Controllers", ""]
        for _, name, path, line, _ in controllers:
            lines.append(f"- `{name}` - {path}:{line}")
        lines.append("")
    if endpoints:
        lines += ["## HTTP Endpoints", ""]
        for verb, route, path, line in endpoints:
            lines.append(f"- `{verb} {route}` - {path}:{line}")
        lines.append("")
    if services:
        lines += ["## Services", ""]
        for _, name, path, line, _ in services:
            lines.append(f"- `{name}` - {path}:{line}")
        lines.append("")
    if configs:
        lines += ["## Configuration Classes", ""]
        for _, name, path, line, _ in configs:
            lines.append(f"- `{name}` - {path}:{line}")
        lines.append("")

    lines += ["## All Types", ""]
    for kind, name, path, line, _ in types:
        lines.append(f"- `{kind} {name}` - {path}:{line}")
    lines.append("")
    return "\n".join(lines)


def write_overview(manifest: dict) -> str:
    s = manifest["stats"]
    return "\n".join([
        "# EdgeFabric Codebase Overview (auto-generated)",
        "",
        "> **Agents: read this file BEFORE grepping the repo.**",
        "> Look up classes/interfaces in `symbols.json`, modules in `modules/<name>.md`,",
        "> file metadata in `MANIFEST.json`. Only fall back to grep/view if those miss.",
        "",
        "## At a glance",
        f"- Git HEAD indexed: `{manifest['git_head']}`",
        f"- Source files: {s['files']} ({s['java_files']} Java, {s['ts_files']} TS/JS)",
        f"- Total source bytes: {s['total_bytes']:,}",
        f"- Modules: {', '.join(s['modules'])}",
        "",
        "## Project shape",
        "EdgeFabric is a distributed cache + load-balancer platform (Java 21, Spring Boot 3,",
        "multi-module Maven). Modules:",
        "",
        "| Module | Port | Role |",
        "|--------|------|------|",
        "| `loadbalancer` | 8080 | HTTP load balancer using consistent hashing |",
        "| `caching` | 8082 | Cache nodes with gossip-based cluster membership |",
        "| `registry` | - | Service registry (deployed separately on EC2) |",
        "| `consistent-hashing` | - | Shared library: hash ring + virtual nodes |",
        "| `testing_edgefabric` | - | Integration / E2E tests |",
        "| `ui` | - | Frontend dashboard |",
        "",
        "## How to use this index",
        "- **Find a class** -> open `symbols.json`, look up the name -> get file:line",
        "- **Understand a module** -> open `modules/<module>.md`",
        "- **List endpoints** -> grep `MANIFEST.json` for `endpoints` or read module pages",
        "- **Check freshness** -> `.index-state.json` records the git SHA + per-file hashes",
        "",
        "## Refresh",
        "- Auto: git pre-commit hook runs incremental index",
        "- Manual: `py mcp-servers/codebase_indexer.py`",
        "- CI gate: `py mcp-servers/codebase_indexer.py --check` (exit 1 if stale)",
        "",
    ])


def write_state(manifest: dict) -> dict:
    return {
        "schema_version": 1,
        "git_head": manifest["git_head"],
        "file_hashes": {f["path"]: f["sha256"] for f in manifest["files"]},
    }


def write_all(manifest: dict) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    MODULES_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "MANIFEST.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    (OUT_DIR / "symbols.json").write_text(
        json.dumps(build_symbols(manifest), indent=2) + "\n", encoding="utf-8"
    )
    (OUT_DIR / "OVERVIEW.md").write_text(write_overview(manifest), encoding="utf-8")
    for module in manifest["stats"]["modules"]:
        md = write_module_md(module, manifest)
        if md:
            (MODULES_DIR / f"{module}.md").write_text(md, encoding="utf-8")
    STATE_FILE.write_text(json.dumps(write_state(manifest), indent=2) + "\n", encoding="utf-8")


def load_state() -> dict | None:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            return None
    return None


def is_stale() -> bool:
    state = load_state()
    if not state:
        return True
    recorded = state.get("file_hashes", {})
    current = {str(p.relative_to(REPO_ROOT)).replace("\\", "/"): sha_of(p)
               for p in iter_source_files()}
    return current != recorded


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="EdgeFabric codebase indexer")
    ap.add_argument("--incremental", action="store_true",
                    help="Skip rebuild if no source files changed since last index")
    ap.add_argument("--check", action="store_true",
                    help="Exit 1 if the on-disk index is stale (for CI gate)")
    args = ap.parse_args(argv)

    if args.check:
        if is_stale():
            print("[codebase-indexer] STALE - run: py mcp-servers/codebase_indexer.py",
                  file=sys.stderr)
            return 1
        print("[codebase-indexer] up to date")
        return 0

    if args.incremental and not is_stale():
        print("[codebase-indexer] no changes - index unchanged")
        return 0

    print("[codebase-indexer] indexing...")
    manifest = build_index()
    write_all(manifest)
    s = manifest["stats"]
    print(f"[codebase-indexer] wrote {OUT_DIR.relative_to(REPO_ROOT)} "
          f"({s['files']} files, {s['java_files']} Java, {s['ts_files']} TS/JS)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
