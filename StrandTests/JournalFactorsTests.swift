import XCTest
@testable import Strand

final class JournalFactorsTests: XCTestCase {
    func testCanonicalMetadataMatchesOracle() {
        XCTAssertEqual(JournalFactor.all.map {
            "\($0.canonical)|\($0.groupKey)|\($0.unit ?? "-")"
        }.joined(separator: "\n"), Self.canonicalMetadata)
        XCTAssertEqual(Set(JournalFactor.all.map(\.canonical)).count, JournalFactor.all.count)
    }

    func testEveryFactorResolvesCaseAndUnicodeWhitespace() {
        for factor in JournalFactor.all {
            let imported = "\n\u{00A0}" + factor.canonical.uppercased().replacingOccurrences(of: " ", with: "\t  ") + "\u{00A0}\n"
            XCTAssertEqual(JournalFactor.find(imported)?.canonical, factor.canonical)
            XCTAssertEqual(JournalCatalogStore.defaultGroup(imported).rawValue, factor.groupKey)
            XCTAssertEqual(JournalCatalogStore.defaultKind(imported).unitLabel, factor.unit)
            XCTAssertEqual(JournalCatalogStore.defaultKind(imported).isNumeric, factor.unit != nil)
        }
        XCTAssertNil(JournalFactor.find("My custom habit"))
        XCTAssertNil(JournalFactor.find(" \n\t"))
    }

    func testLocalizedDisplayPreservesRawKeyAndCustomFallback() {
        var item = JournalCatalogItem(canonical: "DID YOU TAKE  MAGNESIUM?", displayName: nil,
                                      kind: .bool, group: .supplements, sortIndex: 0,
                                      hidden: false, custom: false)
        XCTAssertEqual(item.display, "DID YOU TAKE  MAGNESIUM?")
        XCTAssertEqual(item.localizedDisplay, JournalFactor.find(item.canonical)?.label)
        XCTAssertEqual(item.canonical, "DID YOU TAKE  MAGNESIUM?")

        item.displayName = "My supplement"
        XCTAssertEqual(item.display, "My supplement")
        XCTAssertEqual(item.localizedDisplay, "My supplement")
        XCTAssertEqual(item.canonical, "DID YOU TAKE  MAGNESIUM?")

        item.canonical = "My custom habit"
        item.displayName = nil
        item.custom = true
        XCTAssertEqual(item.localizedDisplay, "My custom habit")
        XCTAssertEqual(item.display, "My custom habit")
    }

    @MainActor
    func testImportedCasingAndInternalSpacingWinWhileOuterWhitespaceTrims() {
        let defaults = UserDefaults.standard
        let original = defaults.object(forKey: JournalCatalogBackupKeys.items)
        defer {
            if let original { defaults.set(original, forKey: JournalCatalogBackupKeys.items) }
            else { defaults.removeObject(forKey: JournalCatalogBackupKeys.items) }
        }
        let store = JournalCatalogStore()
        store.items = []
        let imported = "\n\u{00A0}DID YOU TAKE  MAGNESIUM?\u{00A0}\n"
        let resolved = store.resolvedItems(imported: [imported])
        let matches = resolved.filter { JournalFactor.find($0.canonical)?.canonical == "Did you take magnesium?" }
        XCTAssertEqual(matches.count, 1)
        XCTAssertEqual(matches.first?.canonical, "DID YOU TAKE  MAGNESIUM?")
        XCTAssertEqual(matches.first?.display, "DID YOU TAKE  MAGNESIUM?")
        XCTAssertEqual(matches.first?.localizedDisplay, JournalFactor.find(imported)?.label)
        XCTAssertEqual(resolved.count, JournalFactor.all.count)

        store.rename("DID YOU TAKE  MAGNESIUM?", to: "My supplement")
        XCTAssertEqual(store.resolvedItems(imported: [imported]).first?.localizedDisplay, "My supplement")
        store.rename("DID YOU TAKE  MAGNESIUM?", to: " \n")
        let restored = store.resolvedItems(imported: [imported]).first
        XCTAssertEqual(restored?.canonical, "DID YOU TAKE  MAGNESIUM?")
        XCTAssertEqual(restored?.display, "DID YOU TAKE  MAGNESIUM?")
        XCTAssertEqual(restored?.localizedDisplay, JournalFactor.find(imported)?.label)
    }

    private static let canonicalMetadata = """
    Did you drink any alcohol?|nutrition|-
    Did you have caffeine late in the day?|nutrition|-
    Did you view a screen in bed?|lifestyle|-
    Did you eat close to bedtime?|nutrition|-
    Did you feel stressed?|behaviour|-
    Did you use a sauna?|lifestyle|-
    Did you share your bed?|lifestyle|-
    Did you feel sick or ill?|health|-
    Did you take magnesium?|supplements|-
    Did you read before bed?|lifestyle|-
    Did you meditate?|behaviour|-
    Did you do breathing exercises?|behaviour|-
    Did you spend time outdoors?|lifestyle|-
    Did you travel?|lifestyle|-
    Was your bedroom noisy?|environment|-
    Was your bedroom too warm?|environment|-
    Did you use blackout curtains?|environment|-
    Did you sleep at high altitude?|environment|-
    Did you have allergy symptoms?|health|-
    Did you take prescribed medication?|health|-
    Did you take vitamin D?|supplements|-
    How much caffeine did you consume?|nutrition|mg
    How much water did you drink?|nutrition|mL
    How many minutes did you meditate?|behaviour|min
    What was your bedroom temperature?|environment|°C
    How many hours did you work?|lifestyle|h
    """
}
