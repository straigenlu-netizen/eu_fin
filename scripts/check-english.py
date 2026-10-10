#!/usr/bin/env python3
"""Reject Cyrillic characters in tracked UTF-8 repository text files."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CYRILLIC = re.compile(r"[\u0400-\u052f\u1c80-\u1c8f\u2de0-\u2dff\ua640-\ua69f]")


def main():
    paths = subprocess.check_output(
        ["git", "ls-files", "-z"], cwd=ROOT
    ).decode("utf-8").split("\0")
    violations = []
    checked = 0
    for name in filter(None, paths):
        path = ROOT / name
        if not path.is_file():
            continue
        raw = path.read_bytes()
        if b"\0" in raw:
            continue
        try:
            content = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue
        checked += 1
        for number, line in enumerate(content.splitlines(), 1):
            if CYRILLIC.search(line):
                violations.append(f"{name}:{number}")
    if violations:
        print("English-only repository policy: Cyrillic text found:", file=sys.stderr)
        print("\n".join(violations), file=sys.stderr)
        return 1
    print(f"English-only repository check passed: {checked} tracked text files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
