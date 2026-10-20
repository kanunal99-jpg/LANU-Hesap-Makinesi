"""Unit tests for the schema CI guard: no Android SDK required."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from verify_room_schema import verify
from test_verify_room_nondestructive import RoomNondestructiveRegressionTest


class RoomSchemaGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "4.json"
        self.schema = {
            "formatVersion": 1,
            "database": {
                "version": 4,
                "identityHash": "a" * 32,
                "entities": [
                    {"tableName": name, "fields": [{"columnName": col} for col in columns]}
                    for name, columns in {
                        "calculation_history": ["id"],
                        "user_profiles": ["phoneNumber"],
                        "app_settings": ["key", "value"],
                        "contacts": ["phoneNumber", "name", "isBlocked"],
                        "conversations": ["id", "partnerPhone", "lastMessageText"],
                        "messages": ["id", "conversationId", "senderPhone", "content", "status"],
                        "call_logs": ["id"],
                    }.items()
                ],
            },
        }

    def check(self):
        self.path.write_text(json.dumps(self.schema), encoding="utf-8")
        verify(self.path)

    def test_expected_version_four_contract_is_accepted(self):
        self.check()

    def test_changed_version_is_rejected(self):
        self.schema["database"]["version"] = 5
        with self.assertRaises(ValueError):
            self.check()

    def test_missing_message_column_is_rejected(self):
        message = next(e for e in self.schema["database"]["entities"] if e["tableName"] == "messages")
        message["fields"] = [{"columnName": "id"}]
        with self.assertRaises(ValueError):
            self.check()

    def test_pinned_hash_accepts_exact_schema_and_rejects_drift(self):
        self.path.write_text(json.dumps(self.schema), encoding="utf-8")
        pin = Path(self.tmp.name) / "baseline.sha256"
        pin.write_text(hashlib.sha256(self.path.read_bytes()).hexdigest() + "\n", encoding="utf-8")
        verify(self.path, pin)
        self.schema["database"]["entities"][0]["fields"].append({"columnName": "silentChange"})
        self.path.write_text(json.dumps(self.schema), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "schema drift"):
            verify(self.path, pin)

    def test_missing_database_file_is_rejected(self):
        with self.assertRaises(ValueError):
            verify(self.path)


if __name__ == "__main__":
    unittest.main()
