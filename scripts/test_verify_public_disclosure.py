"""Negative tests for the public calculator UI and release-note word gate."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "lanu_public_disclosure_gate", ROOT / "verify_public_disclosure.py"
)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class PublicDisclosureTest(unittest.TestCase):
    def test_disclosure_examples_are_blocked(self):
        for sample in [
            "Gizli alandaki PIN, kimlik ve sohbetler korunur.",
            "Gizli mesaj bildirimleri ve çağrı düzeltmeleri",
            "Uçtan uca şifreli iletişim",
            "Özel alan güvenliği",
            "Sesli arama",
        ]:
            self.assertTrue(gate.forbidden_public_copy(sample), sample)

    def test_public_copy_is_generic(self):
        for sample in [
            "Paket imzası ve dosya bütünlüğü doğrulanır.",
            "Android sürümüne göre sistem yükleme izni gerekebilir.",
            "Yeni sürüm hazır: v1.2.0-signed.888",
            "Normal hesap makinesi ve birim dönüştürme",
        ]:
            self.assertFalse(gate.forbidden_public_copy(sample), sample)

    def test_entry_code_is_forbidden_in_public_documentation_only(self):
        self.assertTrue(gate.forbidden_public_copy("2011.", include_entry_code=True))
        self.assertFalse(gate.forbidden_public_copy("2011."))
        # Preserve the existing calculator code-based entry mechanism while
        # preventing it from being advertised in the public README.

    def test_update_back_button_requires_safe_system_insets(self):
        safe = (
            "Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {"
            'TextButton(onClick = { navigateBack() }) { Text("← Hesap makinesine dön") }'
            "UpdateCheckScreen(modifier = Modifier.weight(1f))"
            "}"
        )
        self.assertTrue(gate.update_route_has_safe_system_insets(safe))
        self.assertFalse(gate.update_route_has_safe_system_insets(
            safe.replace("windowInsetsPadding(WindowInsets.safeDrawing)", "padding(0.dp)")
        ))
        self.assertFalse(gate.update_route_has_safe_system_insets(
            safe.replace("windowInsetsPadding(WindowInsets.safeDrawing)", "padding(0.dp)") +
            " // windowInsetsPadding(WindowInsets.safeDrawing)"
        ))
        self.assertFalse(gate.update_route_has_safe_system_insets(
            'Text("← Hesap makinesine dön")' +
            "Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) { UpdateCheckScreen() }"
        ))

    def test_comments_are_not_treated_as_on_screen_labels(self):
        src = (
            '// This is the "gizli alan" source comment\n'
            '/* note: "PIN" is not displayed */\n'
            'Text("Güncelleme Kontrol")\n'
        )
        self.assertEqual(['Güncelleme Kontrol'], gate.kotlin_visible_strings(src))


if __name__ == "__main__":
    unittest.main()
