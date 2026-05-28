"""MCP server exposing the EdgeFabric codebase indexer to agents.

Tools:
  - codebase_overview()       -> read OVERVIEW.md
  - codebase_module(name)     -> read modules/<name>.md
  - find_symbol(name)         -> O(1) symbol lookup from symbols.json
  - find_files(glob_pattern)  -> file paths matching a glob
  - list_endpoints(module?)   -> all REST endpoints
  - codebase_stats()          -> counts + freshness
  - reindex(incremental=True) -> rebuild the index
"""
from __future__ import annotations

import asyncio
import fnmatch
import json
from pathlib import Path

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent

import codebase_indexer as ci

OUT_DIR = ci.OUT_DIR

app = Server("codebase-indexer")


def _read(p: Path) -> str:
    if not p.exists():
        return f"(missing) {p.relative_to(ci.REPO_ROOT)} - run reindex"
    return p.read_text(encoding="utf-8")


def _manifest() -> dict:
    fp = OUT_DIR / "MANIFEST.json"
    if not fp.exists():
        return {"files": [], "stats": {}}
    return json.loads(fp.read_text(encoding="utf-8"))


def _symbols() -> dict:
    fp = OUT_DIR / "symbols.json"
    if not fp.exists():
        return {"symbols": {}}
    return json.loads(fp.read_text(encoding="utf-8"))


@app.list_tools()
async def list_tools():
    return [
        Tool(name="codebase_overview",
             description="Return the high-level OVERVIEW.md (READ THIS FIRST before grepping).",
             inputSchema={"type": "object", "properties": {}}),
        Tool(name="codebase_module",
             description="Return the per-module summary (REST controllers, services, configs, all types).",
             inputSchema={"type": "object",
                          "properties": {"name": {"type": "string",
                                                  "description": "Module name e.g. loadbalancer, caching, registry"}},
                          "required": ["name"]}),
        Tool(name="find_symbol",
             description="O(1) lookup: returns all locations of a class/interface/enum/record by name.",
             inputSchema={"type": "object",
                          "properties": {"name": {"type": "string"}},
                          "required": ["name"]}),
        Tool(name="find_files",
             description="Return file paths matching a glob pattern (e.g. **/*Controller.java).",
             inputSchema={"type": "object",
                          "properties": {"pattern": {"type": "string"}},
                          "required": ["pattern"]}),
        Tool(name="list_endpoints",
             description="List all REST endpoints, optionally filtered by module.",
             inputSchema={"type": "object",
                          "properties": {"module": {"type": "string"}}}),
        Tool(name="codebase_stats",
             description="File counts, modules, indexed git SHA, freshness flag.",
             inputSchema={"type": "object", "properties": {}}),
        Tool(name="reindex",
             description="Rebuild the codebase index. Use after large changes.",
             inputSchema={"type": "object",
                          "properties": {"incremental": {"type": "boolean", "default": True}}}),
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict):
    if name == "codebase_overview":
        return [TextContent(type="text", text=_read(OUT_DIR / "OVERVIEW.md"))]

    if name == "codebase_module":
        mod = (arguments or {}).get("name", "")
        return [TextContent(type="text", text=_read(OUT_DIR / "modules" / f"{mod}.md"))]

    if name == "find_symbol":
        sym = (arguments or {}).get("name", "")
        hits = _symbols().get("symbols", {}).get(sym, [])
        return [TextContent(type="text", text=json.dumps({"symbol": sym, "hits": hits}, indent=2))]

    if name == "find_files":
        pattern = (arguments or {}).get("pattern", "")
        files = [f["path"] for f in _manifest()["files"] if fnmatch.fnmatch(f["path"], pattern)]
        return [TextContent(type="text", text=json.dumps({"pattern": pattern, "files": files}, indent=2))]

    if name == "list_endpoints":
        module = (arguments or {}).get("module")
        out = []
        for f in _manifest()["files"]:
            if module and f["module"] != module:
                continue
            for e in f.get("endpoints", []):
                out.append({"verb": e["verb"], "path": e["path"], "module": f["module"],
                            "file": f["path"], "line": e["line"]})
        return [TextContent(type="text", text=json.dumps(out, indent=2))]

    if name == "codebase_stats":
        m = _manifest()
        return [TextContent(type="text", text=json.dumps({
            "git_head_indexed": m.get("git_head"),
            "stats": m.get("stats", {}),
            "stale": ci.is_stale(),
        }, indent=2))]

    if name == "reindex":
        incremental = bool((arguments or {}).get("incremental", True))
        if incremental and not ci.is_stale():
            return [TextContent(type="text", text="no changes - index unchanged")]
        manifest = ci.build_index()
        ci.write_all(manifest)
        return [TextContent(type="text", text=f"reindexed {manifest['stats']['files']} files")]

    return [TextContent(type="text", text=f"unknown tool: {name}")]


async def _main():
    async with stdio_server() as (read, write):
        await app.run(read, write, app.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(_main())
