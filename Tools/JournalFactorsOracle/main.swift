import Foundation

for factor in JournalFactor.all {
    let variant = "\n\u{00A0}" + factor.canonical.uppercased().replacingOccurrences(of: " ", with: "\t  ") + "\u{00A0}\n"
    precondition(JournalFactor.find(variant)?.canonical == factor.canonical)
}
precondition(JournalFactor.find("My custom habit") == nil)

print(JournalFactor.all.map {
    "\($0.canonical)|\($0.groupKey)|\($0.unit ?? "-")"
}.joined(separator: "\n"), terminator: "")
