import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import frontend_inventory as inventory


class FrontendInventoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.lock = self.directory / 'package-lock.json'
        self.manifest = self.directory / 'package.json'
        self.cache = self.directory / 'node_modules'
        self.cache.mkdir()
        self.root = {'name': 'fixture', 'version': '1', 'dependencies': {'a': '1'}}
        self.packages = {'': self.root, 'node_modules/a': {'version': '1', 'license': 'Odd custom terms',
                         'integrity': 'raw-not-a-verified-digest', 'dependencies': {'b': '^2'}},
                         'node_modules/b': {'version': '2', 'optional': True, 'dev': True, 'os': ['linux'], 'cpu': ['arm64']}}

    def write(self):
        self.manifest.write_text(json.dumps(self.root))
        self.lock.write_text(json.dumps({'lockfileVersion': 3, 'packages': self.packages}))

    def installed(self, name='a', version='1'):
        directory = self.cache / name
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'package.json').write_text(json.dumps({'name': name, 'version': version, 'license': 'raw installed terms'}))
        return directory

    def run_inventory(self, installed=True):
        self.write()
        return inventory.build_inventory(self.lock, self.manifest, self.cache if installed else None)

    def test_retains_optional_absence_graph_raw_declarations_and_determinism(self):
        directory = self.installed()
        (directory / 'LICENCE.md').write_bytes(b'original notice')
        result = self.run_inventory()
        a, b = result['packages']
        self.assertEqual('direct', a['dependencyKind'])
        self.assertEqual('transitive', b['dependencyKind'])
        self.assertEqual('Odd custom terms', a['licenseDeclaration'])
        self.assertEqual('raw-not-a-verified-digest', a['integrityDeclaration'])
        self.assertEqual('DECLARED_NOT_VERIFIED', a['integrityStatus'])
        self.assertEqual('ABSENT', b['installed']['status'])
        self.assertEqual(['linux'], b['metadata']['os'])
        self.assertEqual('UNKNOWN', b['licenseStatus'])
        self.assertEqual(inventory.sha(b'original notice'), a['installed']['rootNotices'][0]['sha256'])
        self.assertIn({'from': 'node_modules/a', 'to': 'node_modules/b', 'name': 'b', 'kind': 'dependencies',
                       'declarationOptional': False, 'specification': '^2', 'specificationSha256': inventory.sha(b'^2'), 'resolution': 'LOCK_PATH_LOOKUP'}, result['declaredEdges'])
        self.assertEqual(result, self.run_inventory())
        self.assertNotIn(str(self.directory), json.dumps(result))

    def test_identity_mismatch_is_rejected_without_version_guessing(self):
        self.installed(version='999')
        with self.assertRaises(inventory.InventoryError):
            self.run_inventory()

    def test_missing_notice_remains_unknown_and_no_cache_requested_is_distinct(self):
        self.installed()
        result = self.run_inventory()
        self.assertEqual('ROOT_NOTICE_NOT_FOUND', result['packages'][0]['installed']['unknownReason'])
        self.assertEqual('NOT_REQUESTED', self.run_inventory(False)['packages'][0]['installed']['status'])

    def test_duplicate_nonfinite_oversized_and_nonobject_json_rejected(self):
        self.write()
        for raw in ('{"packages":{},"packages":{}}', '{"x":NaN}', '[]', '{"x":Infinity}'):
            self.lock.write_text(raw)
            with self.assertRaises(inventory.InventoryError):
                inventory.build_inventory(self.lock, self.manifest)
        self.write()
        with mock.patch.object(inventory, 'MAX_JSON_BYTES', 4):
            with self.assertRaises(inventory.InventoryError):
                inventory.build_inventory(self.lock, self.manifest)

    def test_manifest_drift_link_and_unsafe_paths_are_rejected(self):
        self.write()
        self.manifest.write_text('{"name":"wrong","version":"1"}')
        with self.assertRaises(inventory.InventoryError):
            inventory.build_inventory(self.lock, self.manifest)
        for path in ('../node_modules/a', '/node_modules/a', 'node_modules/../a', 'node_modules/a//node_modules/b', 'node_modules/@scope/..', 'node_modules/a\\b'):
            with self.subTest(path=path):
                self.packages[path] = {'version': '1'}
                with self.assertRaises(inventory.InventoryError):
                    self.run_inventory(False)
                del self.packages[path]
        self.packages['node_modules/a']['link'] = True
        with self.assertRaises(inventory.InventoryError):
            self.run_inventory(False)

    def test_root_peer_metadata_drift_cannot_mark_required_peer_optional(self):
        self.root['peerDependencies'] = {'missing': '*'}
        self.root['peerDependenciesMeta'] = {'missing': {'optional': True}}
        self.write()
        manifest = dict(self.root, peerDependenciesMeta={'missing': {'optional': False}})
        self.manifest.write_text(json.dumps(manifest))
        with self.assertRaises(inventory.InventoryError):
            inventory.build_inventory(self.lock, self.manifest)

    def test_root_peer_metadata_validates_both_inputs_before_installed_reads(self):
        self.root['peerDependencies'] = {'missing': '*'}
        for bad in (None, [], {'missing': []}, {'missing': {'optional': 0}}, {'missing': {'optional': 'false'}}):
            for source in ('manifest', 'lock'):
                with self.subTest(metadata=bad, source=source):
                    self.root['peerDependenciesMeta'] = {'missing': {'optional': False}}
                    self.write()
                    if source == 'manifest':
                        self.manifest.write_text(json.dumps(dict(self.root, peerDependenciesMeta=bad)))
                    else:
                        lock = json.loads(self.lock.read_text())
                        lock['packages']['']['peerDependenciesMeta'] = bad
                        self.lock.write_text(json.dumps(lock))
                    with mock.patch.object(inventory, 'installed_evidence', side_effect=AssertionError('root validation must be first')):
                        with self.assertRaises(inventory.InventoryError):
                            inventory.build_inventory(self.lock, self.manifest, self.cache)
        self.root['peerDependenciesMeta'] = {'missing': {'optional': False}}
        result = self.run_inventory(False)
        self.assertFalse(next(e['declarationOptional'] for e in result['declaredEdges'] if e['name'] == 'missing'))

    def test_symlink_package_and_notice_cannot_escape_root(self):
        outside = self.directory / 'outside'
        outside.mkdir()
        (self.cache / 'a').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(inventory.InventoryError):
            self.run_inventory()
        (self.cache / 'a').unlink()
        directory = self.installed()
        external = self.directory / 'terms'
        external.write_text('terms')
        (directory / 'NOTICE').symlink_to(external)
        with self.assertRaises(inventory.InventoryError):
            self.run_inventory()

    def test_notice_file_aggregate_and_root_entry_bounds(self):
        directory = self.installed()
        (directory / 'LICENSE').write_bytes(b'12345')
        for key, value in (('MAX_NOTICE_BYTES', 4), ('MAX_NOTICE_TOTAL', 4), ('MAX_ROOT_ENTRIES', 1)):
            with self.subTest(key=key), mock.patch.object(inventory, key, value):
                with self.assertRaises(inventory.InventoryError):
                    self.run_inventory()

    def test_nested_lookup_and_missing_optional_peer_are_explicit(self):
        self.packages['node_modules/a/node_modules/b'] = {'version': '3'}
        self.packages['node_modules/a']['peerDependencies'] = {'missing': '*', 'required': '*'}
        self.packages['node_modules/a']['peerDependenciesMeta'] = {'missing': {'optional': True}}
        result = self.run_inventory(False)
        self.assertEqual('node_modules/a/node_modules/b', next(e['to'] for e in result['declaredEdges'] if e['name'] == 'b'))
        optional = next(e for e in result['declaredEdges'] if e['name'] == 'missing')
        required = next(e for e in result['declaredEdges'] if e['name'] == 'required')
        self.assertIsNone(optional['to'])
        self.assertTrue(optional['declarationOptional'])
        self.assertIsNone(required['to'])
        self.assertFalse(required['declarationOptional'])

    def test_no_subprocess_or_install_and_existing_output_is_preserved(self):
        self.write()
        output = self.directory / 'report.json'
        argv = ['--lock', str(self.lock), '--manifest', str(self.manifest), '--output', str(output)]
        with mock.patch('subprocess.run', side_effect=AssertionError('must not execute')):
            self.assertEqual(0, inventory.main(argv))
        original = output.read_bytes()
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, inventory.main(argv))
        self.assertEqual(original, output.read_bytes())

    def test_no_resolved_url_or_sensitive_spec_in_report(self):
        self.packages['node_modules/a']['resolved'] = 'https://user:secret@example.invalid/a'
        self.packages['node_modules/a']['dependencies']['b'] = 'https://user:secret@example.invalid/b'
        result = self.run_inventory(False)
        self.assertNotIn('secret', json.dumps(result))
        self.assertEqual('REDACTED_NONREGISTRY_SPEC', next(e['specification'] for e in result['declaredEdges'] if e['name'] == 'b'))


if __name__ == '__main__':
    unittest.main()
