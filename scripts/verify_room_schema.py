#!/usr/bin/env python3
"""Fail CI unless KSP exported the real current Room schema contract.

This deliberately does not invent historical v1-v3 schemas or migration SQL.
"""
import hashlib
import json
import re
import sys
from pathlib import Path


def verify(path: Path, pinned_sha: Path | None = None) -> None:
    if not path.is_file():
        raise ValueError(f"Room v4 schema file missing: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("formatVersion") != 1:
        raise ValueError("Unexpected Room schema JSON format")
    db = data.get("database")
    if not isinstance(db, dict) or db.get("version") != 4:
        raise ValueError("Room schema version must remain 4 for this baseline PR")
    identity = db.get("identityHash")
    if not isinstance(identity, str) or not re.fullmatch(r"[a-fA-F0-9]{32}", identity):
        raise ValueError("Room schema identity hash is invalid")
    entities = db.get("entities")
    if not isinstance(entities, list):
        raise ValueError("Room schema entities are missing")
    names = [entry.get("tableName") for entry in entities]
    required = {
        "calculation_history", "user_profiles", "app_settings",
        "contacts", "conversations", "messages", "call_logs",
    }
    if len(names) != len(set(names)) or set(names) != required:
        raise ValueError(f"Room table inventory drifted: {names!r}")
    required_columns = {
        "app_settings": {"key", "value"},
        "contacts": {"phoneNumber", "name", "isBlocked"},
        "conversations": {"id", "partnerPhone", "lastMessageText"},
        "messages": {"id", "conversationId", "senderPhone", "content", "status"},
    }
    for entity in entities:
        table = entity["tableName"]
        fields = entity.get("fields", [])
        names_in_table = {field.get("columnName") for field in fields}
        missing = required_columns.get(table, set()) - names_in_table
        if missing:
            raise ValueError(f"Room table {table} is missing columns: {sorted(missing)}")
    if pinned_sha is not None:
        expected = pinned_sha.read_text(encoding="utf-8").strip()
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ValueError("Pinned Room v4 SHA256 must be a 64-digit lowercase hash")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError("Room v4 schema drift detected without a version bump")
    print(f"Verified compiler-exported Room v4 schema: {len(entities)} tables, identity {identity}")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]), Path(sys.argv[2]) if len(sys.argv) > 2 else None)
        # Run as part of the existing mandatory Room schema CI gate.
        from verify_room_nondestructive import main as verify_nondestructive
        verify_nondestructive()
    except (IndexError, OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"Room schema CI gate failed: {exc}", file=sys.stderr)
        sys.exit(1)
