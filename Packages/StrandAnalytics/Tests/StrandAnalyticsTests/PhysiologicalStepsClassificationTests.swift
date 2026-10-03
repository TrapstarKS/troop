import XCTest
@testable import StrandAnalytics

final class PhysiologicalStepsClassificationTests: XCTestCase {
    private func describe(_ blocks: [PhysiologicalSteps.SleepBlock], _ offset: Int = 0, _ habitual: Int? = nil) -> String {
        let selected = PhysiologicalSteps.classifyForCycle(blocks, offsetSec: offset, habitualMidsleepSec: habitual)
        let kinds = selected.map { $0.kind == .mainSleep ? "M" : "N" }.joined()
        let onset = PhysiologicalSteps.mainSleepOnset(blocks, offsetSec: offset, habitualMidsleepSec: habitual).map(String.init) ?? "nil"
        return "\(kinds):\(onset)"
    }

    func testClassificationMatchesStandaloneSwiftOracle() {
        var rows: [String] = []
        for offset in [-43200, 0, 19800, 50400] {
            for hour in 0..<24 {
                let onset = 172800 + hour * 3600 - offset
                rows.append(describe([.init(onset: onset, end: onset + 10800)], offset))
            }
        }
        for length in [10799,10800,10801] { rows.append(describe([.init(onset: 244800, end: 244800 + length)])) }
        for gap in [0,3599,3600,5399,5400,7200] {
            rows.append(describe([.init(onset: 252000, end: 257400), .init(onset: 257400 + gap, end: 262800 + gap)]))
        }
        rows.append(describe([.init(onset: 219600, end: 241200, kind: .nap)]))
        rows.append(describe([.init(onset: 176400, end: 189000), .init(onset: 219600, end: 241200)]))
        rows.append(describe([.init(onset: 176400, end: 189000), .init(onset: 219600, end: 241200, kind: .nap)]))
        rows.append(describe([.init(onset: 176400, end: 189000, kind: .mainSleep), .init(onset: 219600, end: 244800, kind: .mainSleep)]))
        rows.append(describe([.init(onset: 176400, end: 189000, kind: .mainSleep), .init(onset: 219600, end: 244800, kind: .mainSleep)], 0, 232200 % 86400))
        rows.append(describe([.init(onset: 219600, end: 230400, editedOnset: 219601)]))
        let expected = """
        M:216000
        M:219600
        M:223200
        M:226800
        M:230400
        M:234000
        M:237600
        M:241200
        M:244800
        M:248400
        M:252000
        M:255600
        M:259200
        M:262800
        M:266400
        M:270000
        M:273600
        M:277200
        M:280800
        M:284400
        M:288000
        M:291600
        M:295200
        M:298800
        M:172800
        M:176400
        M:180000
        M:183600
        M:187200
        M:190800
        M:194400
        M:198000
        M:201600
        M:205200
        M:208800
        M:212400
        M:216000
        M:219600
        M:223200
        M:226800
        M:230400
        M:234000
        M:237600
        M:241200
        M:244800
        M:248400
        M:252000
        M:255600
        M:153000
        M:156600
        M:160200
        M:163800
        M:167400
        M:171000
        M:174600
        M:178200
        M:181800
        M:185400
        M:189000
        M:192600
        M:196200
        M:199800
        M:203400
        M:207000
        M:210600
        M:214200
        M:217800
        M:221400
        M:225000
        M:228600
        M:232200
        M:235800
        M:122400
        M:126000
        M:129600
        M:133200
        M:136800
        M:140400
        M:144000
        M:147600
        M:151200
        M:154800
        M:158400
        M:162000
        M:165600
        M:169200
        M:172800
        M:176400
        M:180000
        M:183600
        M:187200
        M:190800
        M:194400
        M:198000
        M:201600
        M:205200
        N:nil
        M:244800
        M:244800
        MM:252000
        MM:252000
        MM:252000
        MM:252000
        NN:nil
        NN:nil
        N:nil
        NM:219600
        MN:176400
        MM:219600
        MM:219600
        N:nil
        """
        XCTAssertEqual(rows.joined(separator: "\n"), expected)
    }

    func testEarlyEveningAndShiftWorkerSleepUseCanonicalSelector() {
        for onset in [19 * 3600 + 45 * 60, 12 * 3600, 16 * 3600] {
            let blocks: [PhysiologicalSteps.SleepBlock] = [.init(onset: onset, end: onset + 8 * 3600)]
            XCTAssertEqual(PhysiologicalSteps.mainSleepOnset(blocks, offsetSec: 0, habitualMidsleepSec: nil), onset)
            XCTAssertEqual(PhysiologicalSteps.classifyForCycle(blocks, offsetSec: 0, habitualMidsleepSec: nil).first?.kind, .mainSleep)
        }
    }
}
