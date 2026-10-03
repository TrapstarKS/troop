import XCTest
@testable import Strand

final class ExperimentIdentityTests: XCTestCase {
    func testStoredCanonicalIdentityMatchesVerbatimSwiftOracle() {
        let cases: [(String, [String], String, String)] = [
            ("setupSaved", ["Caffeine", "Late meal"], "Late meal", ""),
            ("setupStale", ["Caffeine", "Late meal"], "Removed custom", ""),
            ("setupEmpty", ["Caffeine", "Late meal"], "", ""),
            ("setupNoCandidates", [], "Removed custom", ""),
            ("setupNoCandidatesEmpty", [], "", ""),
            ("setupExactCanonical", ["Caffeine", "Late meal"], "CAFFEINE", ""),
            ("activeEligible", ["Caffeine", "Late meal"], "Late meal", "2026-10-03"),
            ("activeHidden", ["Caffeine"], "Late meal", "2026-10-03"),
            ("activeHiddenNoCandidates", [], "Late meal", "2026-10-03"),
            ("activeStale", ["Caffeine"], "Removed custom", "2026-10-03"),
            ("activeRenamedDisplay", ["Renamed label"], "Late meal", "2026-10-03"),
            ("activeEmpty", ["Caffeine"], "", "2026-10-03"),
            ("activeWhitespace", ["Caffeine"], " \t\r\n", "2026-10-03"),
            ("activeUnicodeCanonical", ["Caffeine"], "Ma\u{f1}ana \u{1f4a4}", "2026-10-03"),
            ("activeTrim-asciiSpace", ["Late meal"], " Caffeine ", "2026-10-03"),
            ("setupTrim-asciiSpace", ["Caffeine", "Late meal"], " Caffeine ", ""),
            ("activeBlank-asciiSpace", ["Caffeine"], " ", "2026-10-03"),
            ("activeTrim-tabCRLF", ["Late meal"], "\t\r\nCaffeine\t\r\n", "2026-10-03"),
            ("setupTrim-tabCRLF", ["Caffeine", "Late meal"], "\t\r\nCaffeine\t\r\n", ""),
            ("activeBlank-tabCRLF", ["Caffeine"], "\t\r\n", "2026-10-03"),
            ("activeTrim-nbsp", ["Late meal"], "\u{a0}Caffeine\u{a0}", "2026-10-03"),
            ("setupTrim-nbsp", ["Caffeine", "Late meal"], "\u{a0}Caffeine\u{a0}", ""),
            ("activeBlank-nbsp", ["Caffeine"], "\u{a0}", "2026-10-03"),
            ("activeTrim-emSpace", ["Late meal"], "\u{2003}Caffeine\u{2003}", "2026-10-03"),
            ("setupTrim-emSpace", ["Caffeine", "Late meal"], "\u{2003}Caffeine\u{2003}", ""),
            ("activeBlank-emSpace", ["Caffeine"], "\u{2003}", "2026-10-03"),
            ("activeTrim-narrowNbsp", ["Late meal"], "\u{202f}Caffeine\u{202f}", "2026-10-03"),
            ("setupTrim-narrowNbsp", ["Caffeine", "Late meal"], "\u{202f}Caffeine\u{202f}", ""),
            ("activeBlank-narrowNbsp", ["Caffeine"], "\u{202f}", "2026-10-03"),
            ("activeTrim-ideographic", ["Late meal"], "\u{3000}Caffeine\u{3000}", "2026-10-03"),
            ("setupTrim-ideographic", ["Caffeine", "Late meal"], "\u{3000}Caffeine\u{3000}", ""),
            ("activeBlank-ideographic", ["Caffeine"], "\u{3000}", "2026-10-03"),
            ("activeTrim-nel", ["Late meal"], "\u{85}Caffeine\u{85}", "2026-10-03"),
            ("setupTrim-nel", ["Caffeine", "Late meal"], "\u{85}Caffeine\u{85}", ""),
            ("activeBlank-nel", ["Caffeine"], "\u{85}", "2026-10-03"),
            ("activeTrim-zeroWidth", ["Late meal"], "\u{200b}Caffeine\u{200b}", "2026-10-03"),
            ("setupTrim-zeroWidth", ["Caffeine", "Late meal"], "\u{200b}Caffeine\u{200b}", ""),
            ("activeBlank-zeroWidth", ["Caffeine"], "\u{200b}", "2026-10-03"),
        ]
        let rows = cases.map { label, candidates, saved, startedDay in
            let value = resolveExperimentBehaviour(candidates: candidates, saved: saved, startedDay: startedDay)
            let encoded = value?.utf8.map { String(format: "%02x", $0) }.joined() ?? "nil"
            return "\(label)|\(encoded)"
        }
        var trimmedScalars: [String] = []
        for code in 0...0xffff {
            guard let scalar = UnicodeScalar(code) else { continue }
            let edge = String(scalar)
            let value = resolveExperimentBehaviour(candidates: [], saved: edge + "Caffeine" + edge, startedDay: "2026-10-03")
            if value == "Caffeine" { trimmedScalars.append(String(format: "%04x", code)) }
        }
        let actual = rows.joined(separator: "\n") + "\ntrimmedBMP|" + trimmedScalars.joined(separator: ",") + "\n"
        // Executed Swift stdout, including all 63,488 non-surrogate BMP scalars.
        let expected = """
        setupSaved|4c617465206d65616c
        setupStale|4361666665696e65
        setupEmpty|4361666665696e65
        setupNoCandidates|nil
        setupNoCandidatesEmpty|nil
        setupExactCanonical|4361666665696e65
        activeEligible|4c617465206d65616c
        activeHidden|4c617465206d65616c
        activeHiddenNoCandidates|4c617465206d65616c
        activeStale|52656d6f76656420637573746f6d
        activeRenamedDisplay|4c617465206d65616c
        activeEmpty|nil
        activeWhitespace|nil
        activeUnicodeCanonical|4d61c3b1616e6120f09f92a4
        activeTrim-asciiSpace|4361666665696e65
        setupTrim-asciiSpace|4361666665696e65
        activeBlank-asciiSpace|nil
        activeTrim-tabCRLF|4361666665696e65
        setupTrim-tabCRLF|4361666665696e65
        activeBlank-tabCRLF|nil
        activeTrim-nbsp|4361666665696e65
        setupTrim-nbsp|4361666665696e65
        activeBlank-nbsp|nil
        activeTrim-emSpace|4361666665696e65
        setupTrim-emSpace|4361666665696e65
        activeBlank-emSpace|nil
        activeTrim-narrowNbsp|4361666665696e65
        setupTrim-narrowNbsp|4361666665696e65
        activeBlank-narrowNbsp|nil
        activeTrim-ideographic|4361666665696e65
        setupTrim-ideographic|4361666665696e65
        activeBlank-ideographic|nil
        activeTrim-nel|4361666665696e65
        setupTrim-nel|4361666665696e65
        activeBlank-nel|nil
        activeTrim-zeroWidth|4361666665696e65
        setupTrim-zeroWidth|4361666665696e65
        activeBlank-zeroWidth|nil
        trimmedBMP|0009,000a,000b,000c,000d,0020,0085,00a0,1680,2000,2001,2002,2003,2004,2005,2006,2007,2008,2009,200a,200b,2028,2029,202f,205f,3000
        """ + "\n"
        XCTAssertEqual(actual, expected)
    }
}
