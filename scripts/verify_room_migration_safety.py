#!/usr/bin/env python3
"""Fail-closed CI guard until a separately verified, lossless SQLCipher migration exists.

This is a regression gate, not disk encryption. A future SQLCipher implementation
must prove safe WAL/checkpoint, identity/Room preservation, power-loss and rollback
before this gate can be replaced with migration acceptance tests.
"""
from __future__ import annotations
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def assert_room_migration_safe(source: str) -> None:
    # Remove comments, preserving executable code. Not a full Kotlin parser,
    # but guards direct accidental additions of destructive migration calls.
    stripped = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    stripped = re.sub(r"//[^\n]*", "", stripped)
    if "Room.databaseBuilder" not in stripped:
        raise ValueError("Room durable database construction must be reviewed")
    if not re.search(r"exportSchema\s*=\s*true\b", stripped):
        raise ValueError("Room schema history export cannot be disabled")
    if re.search(r"\bfallbackToDestructiveMigration[A-Za-z0-9_]*\s*\(", stripped):
        raise ValueError("Destructive Room migrations are forbidden: encrypted messages must survive")
    if re.search(r"\bdeleteDatabase\s*\(", stripped):
        raise ValueError("Deleting the existing Room database is forbidden")
    if re.search(r"\.deleteRecursively\s*\(", stripped):
        raise ValueError("Recursive database deletion is forbidden")

def main() -> None:
    source = (ROOT / "app/src/main/java/com/example/core/database/AppDatabase.kt").read_text(encoding="utf-8")
    assert_room_migration_safe(source)
    print("PASS: Room v4 migration remains non-destructive; SQLCipher not falsely claimed")

if __name__ == "__main__":
    main()
