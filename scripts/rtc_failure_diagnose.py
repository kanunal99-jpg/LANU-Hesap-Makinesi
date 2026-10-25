#!/usr/bin/env python3
"""Privacy-safe WebRTC crash classifier used by the Android release gate.

Never print raw logcat, stack traces, SDP, user messages, remote IPs or ICE.
Only emit a fixed error code, a static explanation and a whitelisted test stage.
"""
from __future__ import annotations

import re
import sys

_STAGE = re.compile(r"\bLANU-RTC-SMOKE:\s+([a-z][a-z0-9-]{0,50})\b")
_ALLOWED_STAGES = frozenset({
    "begin", "factory-initialized", "factory-created", "peer-a-created",
    "peer-b-created", "data-channel-created", "offer-request", "offer-created",
    "local-offer-set", "offer-candidates-ready", "remote-offer-set",
    "answer-created", "local-answer-set", "answer-candidates-ready",
    "remote-answer-set", "data-channel-open", "message-roundtrip-verified",
    "restart-ice-request", "ice-restart-verified",
    "cleanup-start", "cleanup-complete",
})


def diagnose(log: str) -> str:
    stage = "not-recorded"
    observer_bridge = False
    jni_zero = False
    missing_method = False
    jni_error = False
    abort = False
    for line in log.splitlines():
        marker = _STAGE.search(line)
        if marker and marker.group(1) in _ALLOWED_STAGES:
            stage = marker.group(1)
        if ("NoClassDefFoundError" in line or "ClassNotFoundException" in line) and (
            "PeerConnection$Observer$-CC" in line or
            "PeerConnection$Observer$-CC;" in line or
            "PeerConnection$Observer$-CC" in line.replace("/", ".")
        ):
            observer_bridge = True
        if ("NoClassDefFoundError" in line or "ClassNotFoundException" in line) and "org.jni_zero" in line:
            jni_zero = True
        if "NoSuchMethodError" in line:
            missing_method = True
        if "JNI DETECTED ERROR" in line or "JNI exception" in line:
            jni_error = True
        if "Fatal signal 6" in line or "SIGABRT" in line:
            abort = True

    if observer_bridge:
        code = "RTC-JNI-OBSERVER-001"
        hint = "Missing WebRTC Observer desugaring bridge; inspect concrete callback overrides and R8 release ABI."
    elif jni_zero:
        code = "RTC-JNI-ZERO-002"
        hint = "Missing JNI Zero bridge class; inspect vendor AAR and R8 packaging."
    elif missing_method:
        code = "RTC-JNI-METHOD-003"
        hint = "Java ABI method missing in minified target; inspect release desugaring and keep rules."
    elif jni_error:
        code = "RTC-JNI-CALLBACK-004"
        hint = "JNI callback failed; inspect concrete native callbacks and release class retention."
    elif abort:
        code = "RTC-NATIVE-ABORT-005"
        hint = "Native SIGABRT without classified Java cause; inspect fixed stage and local on-device native crash report."
    else:
        code = "RTC-RELEASE-UNKNOWN-999"
        hint = "Release acceptance failed without a recognized native signature; inspect test report."
    return f"error_code={code}\nlast_safe_stage={stage}\nexplanation={hint}"


if __name__ == "__main__":
    # Sensitive input stays in memory and only predefined output is printed.
    print(diagnose(sys.stdin.read()))
