"""Keep opening the journal from implicitly logging yesterday's answers again.

The load functions live inside platform Views without an injectable runtime seam.
This source-boundary check complements behavioral catalog tests and checks only
the absence of persistent journal writes during loading, not the load algorithm.
"""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class JournalLoadBoundaryTests(unittest.TestCase):
    def test_apple_load_cannot_write_journal_answers(self):
        source = (ROOT / "Strand/Screens/InsightsView.swift").read_text()
        load = source.split("private func load(", 1)[1].split("private func restoreFromCache(", 1)[0]
        self.assertNotRegex(load, r"\brepo\.(?:saveJournal\w*|clearJournal\w*)\s*\(")

    def test_android_load_cannot_write_journal_answers(self):
        source = (ROOT / "android/app/src/main/java/com/noop/ui/InsightsScreen.kt").read_text()
        load = source.split("LaunchedEffect(journalSeq, dayOffset, currentDayKey", 1)[1].split(
            "var outcome by", 1
        )[0]
        self.assertNotRegex(load, r"\brepo\.(?:upsertJournal\w*|deleteJournal\w*)\s*\(")


if __name__ == "__main__":
    unittest.main()
