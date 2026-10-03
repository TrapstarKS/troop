"""Diagnostic clipboard entry points must retain explicit export review.

Controller tests exercise redaction, caps and confirmation. These product-source
guards cover UI-only selection and crash-launch paths that cannot run in a pure
package test, and fail if a raw clipboard shortcut is reintroduced.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
DECLARATION = re.compile(
    r"^(?:(?:private|internal|public|fileprivate|open)\s+)*"
    r"(?:struct|class|enum|fun|func)\s+(\w+)\b", re.MULTILINE
)


def definition(relative: str, name: str) -> str:
    source = (ROOT / relative).read_text(encoding="utf-8")
    declarations = list(DECLARATION.finditer(source))
    for index, declaration in enumerate(declarations):
        if declaration.group(1) == name:
            end = declarations[index + 1].start() if index + 1 < len(declarations) else len(source)
            return source[declaration.start():end]
    raise AssertionError(f"Diagnostic routing contract needs its declaration updated: {relative}: {name}")


class DebugExportRouteTests(unittest.TestCase):
    def assert_reviewed_apple_copy(self, relative: str, name: str) -> None:
        body = definition(relative, name)
        self.assertIn("FileExport.copyDebugText(", body)
        self.assertNotRegex(body, r"\.textSelection\s*\(\s*\.enabled\s*\)")
        self.assertIn(".textSelection(.disabled)", body)

    def test_apple_probe_reports_have_no_selection_copy_bypass(self) -> None:
        for name in (
            "ExtendedBatteryProbeResultView", "BodyLocationProbeResultView",
            "FeatureFlagProbeResultView", "EcgProbeResultView", "DeviceConfigProbeResultView",
        ):
            with self.subTest(report=name):
                self.assert_reviewed_apple_copy("Strand/Screens/DevicesView.swift", name)

    def test_apple_diagnostics_and_review_preview_require_confirmation(self) -> None:
        self.assert_reviewed_apple_copy("Strand/Screens/SettingsView.swift", "DiagnosticsSheet")
        review = definition("Strand/Screens/TestCentreView.swift", "ReportReviewSheet")
        self.assertNotRegex(review, r"\.textSelection\s*\(\s*\.enabled\s*\)")
        self.assertIn(".textSelection(.disabled)", review)
        self.assertIn("onConfirm()", review)

    def test_android_probe_reports_route_copy_through_review(self) -> None:
        for name in (
            "BatteryInfoProbeResultDialog", "BatteryPackProbeResultDialog",
            "BodyLocationProbeResultDialog", "FeatureFlagProbeResultDialog", "DeviceConfigProbeResultDialog",
        ):
            with self.subTest(report=name):
                body = definition("android/app/src/main/java/com/noop/ui/DevicesScreen.kt", name)
                self.assertIn(".stageCopy(", body)
                self.assertNotIn("SelectionContainer", body)
                self.assertNotRegex(body, r"setText\s*\(\s*AnnotatedString\s*\(\s*(?:text|shown)\s*\)")

    def test_android_crash_copy_has_an_early_review_host_without_normal_startup(self) -> None:
        path = "android/app/src/main/java/com/noop/ui/MainActivity.kt"
        crash = definition(path, "CrashRecoveryScreen")
        self.assertIn(".stageCopy(", crash)
        self.assertNotIn("SelectionContainer", crash)
        self.assertNotRegex(crash, r"setText\s*\(\s*AnnotatedString\s*\(\s*(?:crash|shown)\s*\)")
        activity = definition(path, "MainActivity")
        start = activity.index("CrashCapture.pendingCrash")
        early_branch = activity[start:activity.index("return", start)]
        self.assertIn("DebugExportReviewHost()", early_branch)
        self.assertNotIn("AppRoot(", early_branch)
        self.assertNotIn("appViewModel", early_branch)
        self.assertIn("CrashCapture.acknowledge(this, crash)", early_branch)

    def test_android_review_preview_is_not_selectable_and_names_copy_action(self) -> None:
        review = definition("android/app/src/main/java/com/noop/ui/TestCentreScreen.kt", "ReportReviewDialog")
        self.assertNotIn("SelectionContainer", review)
        self.assertIn("isCopy", review)
        self.assertIn("onShare", review)


if __name__ == "__main__":
    unittest.main()
