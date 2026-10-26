#!/usr/bin/env python3
"""Install only a verified Firebase Android CLIENT project config on trusted CI.

Never install service-account credentials here; private FCM HTTP v1 keys
belong only to Supabase Edge Secrets. Do not print configuration or tokens.
"""
import base64
import binascii
import json
import os
import pathlib
import sys
import tempfile

EXPECTED_APPLICATION_ID = "com.aistudio.lanucalc.kxwzqm"
VAR = "LANU_FIREBASE_CLIENT_JSON_BASE64"


def install(output: pathlib.Path, encoded: str | None) -> bool:
    if not encoded:
        print("NOTICE: Firebase client config absent; background call push is unavailable.")
        return False
    if len(encoded) > 140_000:
        raise ValueError("Firebase client config is too large")
    try:
        raw = base64.b64decode(encoded, validate=True)
        if not raw or len(raw) > 90_000:
            raise ValueError("Firebase client config byte limit exceeded")
        document = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeError, binascii.Error, json.JSONDecodeError) as error:
        raise ValueError("Invalid Firebase Android client configuration") from error

    if not isinstance(document, dict):
        raise ValueError("Firebase project configuration must be an object")
    project = document.get("project_info")
    clients = document.get("client")
    if (not isinstance(project, dict) or
        not isinstance(project.get("project_id"), str) or
        not project["project_id"] or
        not isinstance(project.get("project_number"), str) or
        not project["project_number"].isdigit() or
        not isinstance(clients, list)):
        raise ValueError("Firebase project metadata missing")
    matched = [c for c in clients if isinstance(c, dict) and
        isinstance(c.get("client_info"), dict) and
        isinstance(c["client_info"].get("android_client_info"), dict) and
        c["client_info"]["android_client_info"].get("package_name") == EXPECTED_APPLICATION_ID and
        isinstance(c["client_info"].get("mobilesdk_app_id"), str) and
        isinstance(c.get("api_key"), list) and c.get("api_key") and
        isinstance(c["api_key"][0], dict) and
        isinstance(c["api_key"][0].get("current_key"), str)]
    if len(matched) != 1:
        raise ValueError("Firebase Android application ID mismatch or duplicate")

    output.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile("wb", dir=output.parent, prefix=".firebase-config-",
                                         delete=False) as tmp:
            tmp_path = pathlib.Path(tmp.name)
            os.chmod(tmp.name, 0o600)
            tmp.write(raw)
        os.replace(tmp_path, output)
    finally:
        if tmp_path is not None:
            tmp_path.unlink(missing_ok=True)
    print("Firebase client project matched expected Android package; secure config installed.")
    return True


if __name__ == "__main__":
    try:
        install(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/google-services.json"),
                os.environ.get(VAR))
    except ValueError as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        sys.exit(1)
