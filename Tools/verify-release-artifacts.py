#!/usr/bin/env python3
"""Local release validation and Classic-source generation (standard library only)."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import plistlib
import re
import struct
import subprocess
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with Path(path).open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def device_binary(data):
    require(len(data) >= 32, 'Missing Mach-O device executable')
    magic, cpu, _, _, commands, _, _, _ = struct.unpack_from('<8I', data)
    require(magic == 0xFEEDFACF and cpu == 0x100000C, 'Expected thin arm64 Mach-O')
    offset = 32
    device = False
    for _ in range(commands):
        require(offset + 8 <= len(data), 'Truncated Mach-O command')
        command, size = struct.unpack_from('<2I', data, offset)
        require(size >= 8 and offset + size <= len(data), 'Invalid Mach-O command')
        if command == 0x32:
            require(size >= 24, 'Truncated platform command')
            device = struct.unpack_from('<I', data, offset + 8)[0] == 2
        elif command == 0x25:
            device = True
        offset += size
    require(device, 'Expected iOS device binary, not simulator/watch/macOS')


def ipa_metadata(path, bundle_id, version):
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version), 'Version must be X.Y.Z')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate IPA entries')
        require(all(not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts
                    for n in names), 'Unsafe IPA path')
        apps = [n for n in names if re.fullmatch(r'Payload/[^/]+\.app/Info.plist', n)]
        require(len(apps) == 1, 'Expected exactly one Payload app')
        base = apps[0][:-len('Info.plist')]
        require(all(n.startswith(base) or n == 'Payload/' for n in names), 'Unexpected IPA content')
        require(not any('/Watch/' in n or n.endswith('embedded.mobileprovision') or
                        n.endswith('.debug.dylib') or n.endswith('__preview.dylib') or
                        '.dSYM/' in n for n in names), 'Watch/profile/debug artifacts in IPA')
        info = plistlib.loads(archive.read(apps[0]))
        require(info.get('CFBundleIdentifier') == bundle_id, 'IPA bundle identity mismatch')
        require(info.get('CFBundleShortVersionString') == version, 'IPA version mismatch')
        build = info.get('CFBundleVersion', '')
        require(isinstance(build, str) and re.fullmatch(r'[0-9]+', build), 'Invalid IPA build number')
        minimum = info.get('MinimumOSVersion', '')
        require(isinstance(minimum, str) and re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', minimum),
                'Missing minimum iOS version')
        require(info.get('DTPlatformName') == 'iphoneos', 'Expected iphoneos build metadata')
        group = info.get('AppGroupIdentifier')
        require(isinstance(group, str) and group.startswith('group.'), 'Missing App Group')
        widget_path = base + 'PlugIns/NOOPWidgets.appex/'
        widgets = [n for n in names if re.fullmatch(re.escape(base) + r'PlugIns/[^/]+/Info.plist', n)]
        require(widgets == [widget_path + 'Info.plist'], 'Expected only the preserved NOOP widget')
        widget = plistlib.loads(archive.read(widgets[0]))
        require(widget.get('CFBundleIdentifier') == bundle_id + '.widgets', 'Widget identity mismatch')
        require(widget.get('AppGroupIdentifier') == group, 'Widget App Group mismatch')
        require(widget.get('CFBundleShortVersionString') == version and
                widget.get('CFBundleVersion') == build, 'Widget version/build mismatch')
        for prefix, plist in [(base, info), (widget_path, widget)]:
            executable = plist.get('CFBundleExecutable', '')
            require(executable and '/' not in executable, 'Invalid executable name')
            device_binary(archive.read(prefix + executable))
            require(prefix + '_CodeSignature/CodeResources' in names, 'Missing capability-template seal')
        privacy = {key: value for key, value in info.items()
                   if re.fullmatch(r'NS.+UsageDescription', key)}
        require(privacy and all(isinstance(v, str) and v.strip() for v in privacy.values()),
                'Missing privacy usage descriptions')
    return dict(bundleIdentifier=bundle_id, version=version, buildVersion=build,
                minOSVersion=minimum, size=Path(path).stat().st_size, sha256=digest(path), privacy=privacy)


def update_manifest(args):
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo), 'Invalid owner/repo')
    source = Path(args.source)
    data = json.loads(source.read_text())
    require(data.get('sourceURL') == f'https://raw.githubusercontent.com/{args.repo}/main/altstore-source.json',
            'Source URL must belong to the explicit repository')
    require(len(data.get('apps', [])) == 1, 'Expected one app in source')
    app = data['apps'][0]
    meta = ipa_metadata(args.ipa, app['bundleIdentifier'], args.version)
    expected = f'https://github.com/{args.repo}/releases/download/v{args.version}/NOOP-ios-unsigned-v{args.version}.ipa'
    require(args.asset_url == expected, 'Asset URL must match repository, tag, and canonical IPA filename')
    versions = app.get('versions', [])
    current = tuple(map(int, args.version.split('.')))
    for old in versions:
        require(current > tuple(map(int, old['version'].split('.'))),
                'Refusing to replace or prepend an older published version')
        require(int(meta['buildVersion']) > int(old['buildVersion']), 'Build number must increase')
        require(old['downloadURL'].startswith(f'https://github.com/{args.repo}/releases/download/'),
                'Source contains another repository’s releases')
    date = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    description = args.description or f'troop {args.version}. See the release notes for changes.'
    entry = dict(version=args.version, buildVersion=meta['buildVersion'], date=date,
                 localizedDescription=description, downloadURL=args.asset_url,
                 size=meta['size'], minOSVersion=meta['minOSVersion'])
    app['versions'] = [entry] + versions
    app.update(version=args.version, buildVersion=meta['buildVersion'], versionDate=date,
               versionDescription=description, downloadURL=args.asset_url,
               size=meta['size'], minOSVersion=meta['minOSVersion'])
    app['appPermissions'] = dict(entitlements=['com.apple.developer.healthkit',
                                'com.apple.developer.healthkit.access',
                                'com.apple.security.application-groups'], privacy=meta['privacy'])
    temporary = source.with_suffix(source.suffix + '.tmp')
    temporary.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
    temporary.replace(source)
    return meta


def apk_metadata(args):
    def query(field):
        return subprocess.check_output([args.apkanalyzer, 'manifest', field, args.path], text=True).strip()
    require(query('application-id') == args.package_id, 'APK package mismatch')
    require(query('version-name') == args.version, 'APK version mismatch')
    code = int(query('version-code'))
    require(code > args.previous_code, 'APK versionCode must increase')
    require(query('debuggable') == 'false', 'Stable APK is debuggable')
    result = subprocess.check_output([args.apksigner, 'verify', '--verbose', '--print-certs', args.path], text=True)
    signers = re.findall(r'Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)', result)
    require(len(signers) == 1, 'Expected one APK signer')
    require(signers[0].lower() == args.cert_sha256.replace(':', '').lower(), 'APK signer mismatch')
    return dict(package=args.package_id, version=args.version, versionCode=code,
                certificateSHA256=signers[0].lower(), sha256=digest(args.path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    ipa = sub.add_parser('ipa')
    ipa.add_argument('path')
    ipa.add_argument('--bundle-id', required=True)
    ipa.add_argument('--version', required=True)
    manifest = sub.add_parser('manifest')
    for name in ['repo', 'source', 'asset-url', 'version', 'ipa']:
        manifest.add_argument('--' + name, required=True)
    manifest.add_argument('--description')
    apk = sub.add_parser('apk')
    apk.add_argument('path')
    for name in ['package-id', 'version', 'cert-sha256', 'apkanalyzer', 'apksigner']:
        apk.add_argument('--' + name, required=True)
    apk.add_argument('--previous-code', type=int, default=0)
    checksums = sub.add_parser('checksums')
    checksums.add_argument('paths', nargs='+')
    args = parser.parse_args()
    try:
        if args.command == 'checksums':
            for path in args.paths:
                print(f'{digest(path)}  {Path(path).name}')
            return
        result = (ipa_metadata(args.path, args.bundle_id, args.version) if args.command == 'ipa' else
                  update_manifest(args) if args.command == 'manifest' else apk_metadata(args))
        print(json.dumps(result, indent=2, ensure_ascii=False))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, plistlib.InvalidFileException,
            subprocess.CalledProcessError, struct.error) as error:
        parser.exit(1, f'Release validation failed: {error}\n')


if __name__ == '__main__':
    main()
