#!/usr/bin/env python3
"""Prevent user-facing LANU surfaces from advertising gated features.

Only inspect *public* UI copy and release/README summaries. Authenticated
settings, private UI strings, and backend documentation remain untouched.
"""
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
HINT = re.compile(
    r"gizli|özel\s+alan|sohbet|mesaj|görüntülü\s+arama|"
    r"sesli\s+arama|e2ee|webrtc|\bpin\b|şifreli\s+iletişim",
    re.IGNORECASE,
)
README_HINT = re.compile(HINT.pattern + r"|2011\.", re.IGNORECASE)
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"', re.DOTALL)


def kotlin_visible_strings(source: str) -> list[str]:
    # Comments are never rendered; keep the gate specific to displayed text.
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    source = re.sub(r"(?m)^\s*//.*$", "", source)
    return [m.group(0)[1:-1] for m in STRING_LITERAL.finditer(source)]


def forbidden_public_copy(copy: str, *, include_entry_code: bool = False) -> bool:
    return bool((README_HINT if include_entry_code else HINT).search(copy))


def main() -> None:
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    if forbidden_public_copy(readme, include_entry_code=True):
        raise SystemExit("Public repository landing page contains a private-feature hint")

    screens = [
        "app/src/main/java/com/example/features/calculator/CalculatorScreen.kt",
        "app/src/main/java/com/example/features/calculator/FinanceScreen.kt",
        "app/src/main/java/com/example/features/calculator/UnitConverterScreen.kt",
        "app/src/main/java/com/example/features/calculator/HistoryScreen.kt",
        "app/src/main/java/com/example/features/update/UpdateCheckScreen.kt",
        "app/src/main/java/com/example/features/update/PublicUpdateText.kt",
    ]
    for name in screens:
        contents = (ROOT / name).read_text(encoding="utf-8")
        for value in kotlin_visible_strings(contents):
            if forbidden_public_copy(value):
                raise SystemExit(f"Unprotected calculator UI disclosure in {name}")

    # Only the public update route is exposed from MainActivity without the
    # separately authenticated navigation gate.
    activity = (ROOT / "app/src/main/java/com/example/MainActivity.kt").read_text(
        encoding="utf-8"
    )
    if "is Screen.Updates -> {" not in activity or "is Screen.Security -> {" not in activity:
        raise SystemExit("Navigation structure changed: re-review public exposure boundary")
    public_update_route = activity.split("is Screen.Updates -> {", 1)[1].split(
        "is Screen.Security -> {", 1
    )[0]
    for value in kotlin_visible_strings(public_update_route):
        if forbidden_public_copy(value):
            raise SystemExit("Private feature hint in public navigation route")

    workflow = (ROOT / ".github/workflows/android.yml").read_text(
        encoding="utf-8"
    )
    published_notes = re.findall(r'(?m)^\s*NOTES="([^"\n]+)"', workflow)
    if len(published_notes) < 2:
        raise SystemExit("Release-note construction changed: must re-review")
    if any(forbidden_public_copy(note) for note in published_notes):
        raise SystemExit("Future public release notes disclose a private feature")
    print("Public calculator copy and release-note privacy gate passed")


if __name__ == "__main__":
    main()
