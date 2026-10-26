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
EXPECTED_FIREBASE_PROJECT_ID = "gen-lang-client-0658022317"
VAR = "LANU_FIREBASE_CLIENT_JSON_BASE64"
RAW_VAR = "LANU_FIREBASE_CLIENT_JSON"


def install(output: pathlib.Path, encoded: str | None, *, raw_json: str | None = None) -> bool:
    # The owner can paste the downloaded CLIENT JSON directly into a GitHub
    # repository secret from a phone. Base64 is kept for existing owners.
    # Never accept ambiguous dual configuration from two different projects.
    if encoded and raw_json:
        raise ValueError("Conflicting Firebase client config secrets")
    if not encoded and not raw_json:
        print("NOTICE: Firebase client config absent; background call push is unavailable.")
        return False
    if encoded and len(encoded) > 140_000:
        raise ValueError("Firebase client config is too large")
    if raw_json and len(raw_json) > 90_000:
        raise ValueError("Firebase client config is too large")
    try:
        raw = raw_json.encode("utf-8") if raw_json else base64.b64decode(encoded, validate=True)
        if not raw or len(raw) > 90_000:
            raise ValueError("Firebase client config byte limit exceeded")
        document = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeError, binascii.Error, json.JSONDecodeError) as error:
        raise ValueError("Invalid Firebase Android client configuration") from error

    # Diagnose *format*, not data. This may execute with protected GitHub
    # Secrets, so never print any supplied keys, values, project names, bytes,
    # raw exception reprs, or hashes derived from the secret.
    if isinstance(document, str):
        raise ValueError("Firebase Secret holds a JSON-encoded string; paste the raw Android JSON object, not a quoted JSON string")
    if not isinstance(document, dict):
        raise ValueError("Firebase Secret must contain a top-level Android JSON object")
    project = document.get("project_info")
    clients = document.get("client")
    if document.get("type") == "service_account":
        raise ValueError("Service-account JSON is not Android google-services.json; keep server credentials only in Supabase Edge Secrets")
    if not isinstance(project, dict):
        if isinstance(document.get("projectId"), str) and (
            "apiKey" in document or "appId" in document or "messagingSenderId" in document
        ):
            raise ValueError("GitHub Secret contains Firebase Web SDK config, not Android google-services.json")
        if any(field in document for field in ("google-services.json", "content", "value", "config")):
            raise ValueError("GitHub Secret contains a wrapper object, not raw Android google-services.json")
        if isinstance(clients, list):
            raise ValueError("GitHub Secret contains an incomplete Android JSON: top-level project_info is missing")
        raise ValueError("GitHub Secret is not the top-level Android google-services.json object; replace its entire value with the downloaded file contents")
    if project.get("project_id") != EXPECTED_FIREBASE_PROJECT_ID:
        raise ValueError("Firebase Android project mismatch; expected " + EXPECTED_FIREBASE_PROJECT_ID)
    if (not isinstance(project.get("project_number"), str) or
        not project["project_number"].isdigit() or
        not isinstance(clients, list)):
        raise ValueError("Invalid Firebase Android client project_number or client list")
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
                os.environ.get(VAR), raw_json=os.environ.get(RAW_VAR))
    except ValueError as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        sys.exit(1)
