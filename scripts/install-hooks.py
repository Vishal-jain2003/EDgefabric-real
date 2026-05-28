"""Install EdgeFabric git hooks (cross-platform).

Run once per clone:
    py scripts/install-hooks.py
"""
from __future__ import annotations

import shutil
import stat
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "scripts" / "git-hooks"
DST = ROOT / ".git" / "hooks"


def main() -> int:
    if not DST.exists():
        print(f"[install-hooks] {DST} not found - is this a git checkout?")
        return 1
    installed = []
    for src in SRC.iterdir():
        if not src.is_file():
            continue
        dst = DST / src.name
        shutil.copyfile(src, dst)
        try:
            dst.chmod(dst.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
        except Exception:
            pass
        installed.append(src.name)
    print(f"[install-hooks] installed: {', '.join(installed) or '(none)'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
