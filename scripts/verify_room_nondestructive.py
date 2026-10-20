#!/usr/bin/env python3
"""Prevent changes that would silently delete LANU PIN/chat history during Room upgrades."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ROOM_FILE = Path("app/src/main/java/com/example/core/database/AppDatabase.kt")
# Fail even when a destructive fallback appears in a new database builder.
UNSAFE = re.compile(r"\.\s*(?:fallbackToDestructiveMigration(?:From|OnDowngrade)?|allowMainThreadQueries)\s*\(")


def verify_room_source(app_database: str, all_kotlin_files: dict[str, str]) -> None:
    if not re.search(r"\bversion\s*=\s*4\b", app_database):
        raise ValueError("Room schema version changed: recover authentic old/new fixtures first")
    if not re.search(r"\bexportSchema\s*=\s*true\b", app_database):
        raise ValueError("Room compiler schema export must remain enabled")
    if not re.search(r"\bclass\s+AppDatabase\s*:\s*RoomDatabase", app_database):
        raise ValueError("AppDatabase implementation missing")
    for name, source in all_kotlin_files.items():
        if UNSAFE.search(source):
            raise ValueError(f"Unsafe destructive/foreground Room database action in {name}")


def main() -> None:
    path = ROOT / ROOM_FILE
    kotlin_files = {
        str(p.relative_to(ROOT)): p.read_text(encoding="utf-8")
        for p in (ROOT / "app/src/main").rglob("*.kt")
    }
    try:
        verify_room_source(path.read_text(encoding="utf-8"), kotlin_files)
    except (OSError, ValueError) as error:
        raise SystemExit(f"FAILED: Room user-data preservation guard: {error}")
    print(f"PASS: Room v4 exported; no destructive fallback or main-thread DB calls in {len(kotlin_files)} Kotlin files")


if __name__ == "__main__":
    main()
