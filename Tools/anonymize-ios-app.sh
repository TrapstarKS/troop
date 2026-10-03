#!/usr/bin/env bash
#
# anonymize-ios-app.sh — scrub the building machine's home path out of an UNSIGNED iOS
# .app (the twin of anonymize-macos-app.sh) before it is packaged into a sideloadable .ipa.
#
# Why: Swift/clang bake source-file path literals (e.g. GRDB's `#file` defaults) into the
# compiled binary; on a release build those include the *builder's* home directory — i.e.
# your username. NOOP ships anonymously, so this must be stripped from every Mach-O in the
# bundle (main executable, the widget .appex, embedded frameworks/dylibs).
#
# Unlike the macOS script this does NOT code-sign: the iOS .ipa is distributed UNSIGNED and
# the end user signs it on-device with their own free Apple ID (AltStore / SideStore /
# Sideloadly). So no Apple Developer identity ever touches the project or the builder.
#
#     xcodebuild -scheme NOOPiOS -configuration Release -destination 'generic/platform=iOS' \
#         -derivedDataPath build/ios-dd CODE_SIGNING_ALLOWED=NO build
#     Tools/anonymize-ios-app.sh build/ios-dd/Build/Products/Release-iphoneos/NOOP.app
#
# os.walk below recurses the WHOLE bundle, so the embedded watch app (NOOP.app/Watch/NOOPWatch.app)
# and its complication .appex are scrubbed and residual-checked along with everything else.
#
# The replacement is the SAME byte length as the original path, so all Mach-O offsets stay
# valid; only the read-only string section changes. The script reads $HOME at runtime and
# contains no identifying information itself.
set -euo pipefail

APP="${1:?usage: $0 path/to/App.app}"
[ -d "$APP" ] || { echo "no such app bundle: $APP" >&2; exit 1; }

HOME_PATH="$HOME"                       # e.g. /Users/alice
REPL="/Users/builder"                   # generic, anonymous
# Pad or trim REPL to EXACTLY the length of $HOME so byte offsets are preserved.
while [ ${#REPL} -lt ${#HOME_PATH} ]; do REPL="${REPL}_"; done
REPL="${REPL:0:${#HOME_PATH}}"

python3 - "$APP" "$HOME_PATH" "$REPL" <<'PY'
import sys, os
app, home, repl = sys.argv[1], sys.argv[2].encode(), sys.argv[3].encode()
assert len(home) == len(repl), "replacement length must match"
total = files = 0
def fail_walk(error):
    raise error
# Walk the whole bundle and scrub any file that embeds the home path (main exe, *.appex,
# Frameworks/*.dylib, *.framework binaries). Same-length replacement keeps Mach-O valid.
for root, _dirs, names in os.walk(app, onerror=fail_walk):
    for name in names:
        p = os.path.join(root, name)
        if os.path.islink(p) or not os.path.isfile(p):
            continue
        with open(p, "rb") as source:
            data = source.read()
        hits = data.count(home)
        if hits:
            with open(p, "wb") as target:
                target.write(data.replace(home, repl))
            total += hits
            files += 1
            print(f"  scrubbed {hits:>4} in {os.path.relpath(p, app)}")
print(f"scrubbed {total} occurrence(s) across {files} file(s)")
# Verify raw bytes on disk, including binaries; read/walk failures must stop packaging.
residual = 0
for root, _dirs, names in os.walk(app, onerror=fail_walk):
    for name in names:
        p = os.path.join(root, name)
        if os.path.islink(p) or not os.path.isfile(p):
            continue
        with open(p, "rb") as source:
            residual += source.read().count(home)
print(f"residual home-path hits: {residual}")
if residual:
    sys.exit("residual paths remain")
print("✓ clean")
PY
