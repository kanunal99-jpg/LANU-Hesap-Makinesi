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

def assert_no_production_database_wipes(sources: dict[str, str]) -> None:
    """Prevent accidental destructive APIs from ANY checked-in production source.

    This is defense in depth: static patterns are not a replacement for Room
    migration tests, SQLCipher, physical OTA acceptance or code review.
    Deliberate user-initiated per-row deletion remains unaffected.
    """
    dangerous = re.compile(
        r"\b(?:fallbackToDestructiveMigration\w*|deleteDatabase)\s*\("
    )
    for path, source in sources.items():
        # Keep the scope bounded to checked-in Android production code.
        if not path.startswith("app/src/main/") or not path.endswith((".kt", ".java")):
            raise ValueError("Database guard received an invalid source path")
        clean = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
        clean = re.sub(r"//[^\n]*", "", clean)
        if dangerous.search(clean):
            raise ValueError(f"Destructive database operation in {path}: existing data must survive")


def main() -> None:
    source = (ROOT / "app/src/main/java/com/example/core/database/AppDatabase.kt").read_text(encoding="utf-8")
    assert_room_migration_safe(source)
    app_sources = ROOT / "app/src/main"
    sources = {
        str(path.relative_to(ROOT)).replace("\\\\", "/"): path.read_text(encoding="utf-8")
        for path in sorted(app_sources.rglob("*"))
        if path.is_file() and path.suffix in {".kt", ".java"}
    }
    if not sources:
        raise ValueError("No production source files found for destructive migration audit")
    assert_no_production_database_wipes(sources)
    print("PASS: all Android production sources reject destructive DB APIs; SQLCipher is not falsely claimed")

if __name__ == "__main__":
    main()
