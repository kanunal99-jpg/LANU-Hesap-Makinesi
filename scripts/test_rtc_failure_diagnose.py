import unittest

from rtc_failure_diagnose import diagnose


class RtcFailureDiagnosticsTests(unittest.TestCase):
    def test_exact_desugaring_crash_and_last_known_step(self):
        logs = """10-25 14:55:25 I LANU-RTC-SMOKE: local-answer-set
10-25 14:55:25 W System.err: java.lang.NoClassDefFoundError: Failed resolution of: Lorg/webrtc/PeerConnection$Observer$-CC;
10-25 14:55:25 F libc: Fatal signal 6 (SIGABRT) in signaling_threa
"""
        result = diagnose(logs)
        self.assertIn("RTC-JNI-OBSERVER-001", result)
        self.assertIn("last_safe_stage=local-answer-set", result)

    def test_untrusted_strings_cannot_enter_diagnostics(self):
        leak = "a=candidate:very-private 10.12.3.45 sdp-secret contact=person"
        result = diagnose("LANU-RTC-SMOKE: " + leak + "\n" + leak + "\nFatal signal 6 (SIGABRT)")
        self.assertIn("RTC-NATIVE-ABORT-005", result)
        for value in ("10.12.3.45", "candidate", "person", "secret", "private"):
            self.assertNotIn(value, result)

    def test_malformed_or_unrecognized_logs_do_not_invent_success(self):
        result = diagnose("LANU-RTC-SMOKE: bogus-sensitive-stage\nunexpected failure")
        self.assertIn("RTC-RELEASE-UNKNOWN-999", result)
        self.assertIn("last_safe_stage=not-recorded", result)

    def test_jni_zero_and_missing_method_have_separate_codes(self):
        self.assertIn("RTC-JNI-ZERO-002", diagnose("java.lang.ClassNotFoundException: org.jni_zero.JniZero"))
        self.assertIn("RTC-JNI-METHOD-003", diagnose("java.lang.NoSuchMethodError"))

if __name__ == "__main__":
    unittest.main()
