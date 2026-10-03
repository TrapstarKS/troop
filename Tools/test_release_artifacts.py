import argparse
import importlib.util
import json
from pathlib import Path
import plistlib
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('release_artifacts', Path(__file__).with_name('verify-release-artifacts.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
BUNDLE = 'com.trapstarks.troop.noop'
REPO = 'TrapstarKS/troop'


class ReleaseArtifactTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.ipa = self.root / 'app.ipa'
        self.source = self.root / 'source.json'
        self.source.write_text(json.dumps({'sourceURL': f'https://raw.githubusercontent.com/{REPO}/main/altstore-source.json',
                                          'apps': [{'bundleIdentifier': BUNDLE, 'versions': []}]}))

    def make_ipa(self, changes=None, widget_changes=None, extra=None, platform=2):
        info = dict(CFBundleIdentifier=BUNDLE, CFBundleShortVersionString='1.2.3', CFBundleVersion='9',
                    MinimumOSVersion='17.4', DTPlatformName='iphoneos', CFBundleExecutable='app',
                    AppGroupIdentifier='group.troop', NSBluetoothAlwaysUsageDescription='Read strap locally',
                    NSMicrophoneUsageDescription='Local voice input')
        widget = dict(info, CFBundleIdentifier=BUNDLE + '.widgets')
        info.update(changes or {})
        widget.update(widget_changes or {})
        binary = struct.pack('<8I', 0xFEEDFACF, 0x100000C, 0, 2, 1, 24, 0, 0) + struct.pack('<6I', 0x32, 24, platform, 0, 0, 0)
        with zipfile.ZipFile(self.ipa, 'w') as z:
            for prefix, metadata in [('Payload/NOOP.app/', info), ('Payload/NOOP.app/PlugIns/NOOPWidgets.appex/', widget)]:
                z.writestr(prefix + 'Info.plist', plistlib.dumps(metadata))
                z.writestr(prefix + 'app', binary)
                z.writestr(prefix + '_CodeSignature/CodeResources', b'fixture seal')
            for name in extra or []:
                z.writestr(name, b'fixture')

    def args(self, **changes):
        values = dict(repo=REPO, source=str(self.source), ipa=str(self.ipa), version='1.2.3', description=None,
                      asset_url=f'https://github.com/{REPO}/releases/download/v1.2.3/NOOP-ios-unsigned-v1.2.3.ipa')
        values.update(changes)
        return argparse.Namespace(**values)

    def test_manifest_uses_real_metadata_and_permissions(self):
        self.make_ipa()
        release.update_manifest(self.args())
        app = json.loads(self.source.read_text())['apps'][0]
        self.assertEqual(app['versions'][0]['minOSVersion'], '17.4')
        self.assertEqual(app['buildVersion'], '9')
        self.assertEqual(app['size'], self.ipa.stat().st_size)
        self.assertIn('NSMicrophoneUsageDescription', app['appPermissions']['privacy'])
        self.assertEqual(app['versions'][0]['downloadURL'], self.args().asset_url)

    def test_invalid_ipa_does_not_mutate_source(self):
        for field, value in [('CFBundleIdentifier', 'upstream'), ('CFBundleShortVersionString', '1.2.2'),
                             ('CFBundleVersion', ''), ('MinimumOSVersion', ''), ('DTPlatformName', 'iphonesimulator')]:
            with self.subTest(field=field):
                self.make_ipa({field: value})
                before = self.source.read_bytes()
                with self.assertRaises(ValueError):
                    release.update_manifest(self.args())
                self.assertEqual(self.source.read_bytes(), before)

    def test_widget_contract(self):
        for change in [dict(AppGroupIdentifier='group.other'), dict(CFBundleIdentifier='other'), dict(CFBundleVersion='8')]:
            self.make_ipa(widget_changes=change)
            with self.assertRaises(ValueError):
                release.update_manifest(self.args())

    def test_rejects_unsafe_or_personal_or_debug_artifacts(self):
        for extra in ['../secret', 'Payload/second.app/Info.plist', 'Payload/NOOP.app/Watch/foo',
                      'Payload/NOOP.app/embedded.mobileprovision', 'Payload/NOOP.app/app.debug.dylib']:
            self.make_ipa(extra=[extra])
            with self.assertRaises(ValueError):
                release.update_manifest(self.args())

    def test_rejects_simulator_macho_even_with_device_plist(self):
        self.make_ipa(platform=7)
        with self.assertRaises(ValueError):
            release.update_manifest(self.args())

    def test_wrong_url_or_repo_and_immutable_version(self):
        self.make_ipa()
        for change in [dict(repo='ryanbr/noop'), dict(asset_url='https://example.com/file.ipa'),
                       dict(asset_url=self.args().asset_url.replace('1.2.3.ipa', '1.2.4.ipa'))]:
            with self.assertRaises(ValueError):
                release.update_manifest(self.args(**change))
        release.update_manifest(self.args())
        with self.assertRaises(ValueError):
            release.update_manifest(self.args())

    def test_new_version_and_build_must_increase(self):
        self.make_ipa()
        data = json.loads(self.source.read_text())
        data['apps'][0]['versions'] = [dict(version='1.2.2', buildVersion='8', downloadURL=self.args().asset_url)]
        self.source.write_text(json.dumps(data))
        release.update_manifest(self.args())
        self.assertEqual([v['version'] for v in json.loads(self.source.read_text())['apps'][0]['versions']], ['1.2.3', '1.2.2'])

    def test_apk_rejects_wrong_signer_package_or_debuggable(self):
        self.ipa.write_bytes(b'fixture apk')
        args = argparse.Namespace(path=str(self.ipa), apkanalyzer='analyzer', apksigner='signer',
                                  package_id='com.trapstarks.troop', version='1.2.3', cert_sha256='a'*64, previous_code=8)
        outputs = ['com.trapstarks.troop', '1.2.3', '9', 'false', 'Signer #1 certificate SHA-256 digest: ' + 'a'*64]
        with patch.object(release.subprocess, 'check_output', side_effect=outputs):
            self.assertEqual(release.apk_metadata(args)['versionCode'], 9)
        for index, value in [(0, 'upstream'), (1, '1.2.3-staging'), (2, '8'), (3, 'true'), (4, 'Signer #1 certificate SHA-256 digest: ' + 'b'*64)]:
            bad = outputs.copy(); bad[index] = value
            with patch.object(release.subprocess, 'check_output', side_effect=bad), self.assertRaises(ValueError):
                release.apk_metadata(args)


if __name__ == '__main__':
    unittest.main()
