"""Behavior checks using tiny resolved-tree/cache/Boot-JAR fixtures, no network."""

import contextlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

import dependency_inventory as inventory


def jar_bytes(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, content in entries.items():
            archive.writestr(zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0)), content)
    return output.getvalue()


class InventoryBehaviorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "cache"
        self.repository.mkdir()
        self.tree = self.root / "tree.json"
        self.boot = self.root / "application.jar"
        self.output = self.root / "inventory.json"
        self.project = {"groupId": "example", "artifactId": "app", "type": "jar", "version": "1.0", "children": []}
        self.libraries = {}

    def dependency(self, name, scope="compile", license_xml="", jar=None):
        node = {"groupId": "example", "artifactId": name, "type": "jar", "version": "1.0", "scope": scope}
        directory = self.repository / "example" / name / "1.0"
        directory.mkdir(parents=True)
        artifact = jar if jar is not None else jar_bytes({name + ".class": name.encode(), "META-INF/LICENSE.txt": b"license original"})
        (directory / (name + "-1.0.jar")).write_bytes(artifact)
        pom = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
               '<groupId>example</groupId><artifactId>' + name + '</artifactId><version>1.0</version>'
               + license_xml + '</project>')
        (directory / (name + "-1.0.pom")).write_text(pom, encoding="utf-8")
        return node, artifact

    def run_inventory(self):
        self.tree.write_text(json.dumps(self.project), encoding="utf-8")
        self.boot.write_bytes(jar_bytes(self.libraries))
        return inventory.build_inventory(self.tree, self.boot, self.repository)

    def test_reconciles_bytes_and_preserves_direct_transitive_test_and_raw_license_evidence(self):
        license_xml = '<licenses><license><name>Example terms</name><url>https://example.invalid/license</url></license></licenses>'
        direct, direct_jar = self.dependency("direct", license_xml=license_xml)
        child, child_jar = self.dependency("transitive", scope="runtime")
        test, _ = self.dependency("tests", scope="test")
        direct["children"] = [child]
        self.project["children"] = [direct, test]
        # Names alone are not used to identify coordinates.
        self.libraries = {"BOOT-INF/lib/renamed.jar": direct_jar, "BOOT-INF/lib/second.jar": child_jar}

        result = self.run_inventory()

        self.assertEqual("COMPLETE", result["completeness"])
        self.assertEqual("NOT_PERFORMED", result["licenseReview"])
        self.assertEqual("NOT_EVALUATED", result["projectLicense"])
        by_coordinate = {item["coordinate"]: item for item in result["artifacts"]}
        self.assertEqual(["direct"], by_coordinate["example:direct:jar:1.0"]["dependencyKinds"])
        self.assertEqual(["transitive"], by_coordinate["example:transitive:jar:1.0"]["dependencyKinds"])
        declared = by_coordinate["example:direct:jar:1.0"]
        self.assertEqual("DECLARED", declared["pom"]["licenseStatus"])
        self.assertEqual("Example terms", declared["pom"]["declaredLicenses"][0]["name"])
        self.assertEqual("META-INF/LICENSE.txt", declared["licenseNotices"][0]["entry"])
        self.assertEqual({"example:direct:jar:1.0", "example:transitive:jar:1.0"},
                         {item["coordinate"] for item in result["packagedLibraries"]})
        self.assertIn({"from": "example:direct:jar:1.0", "to": "example:transitive:jar:1.0"}, result["dependencyEdges"])
        self.assertNotIn(str(self.root), json.dumps(result))
        self.assertEqual(result, self.run_inventory())

    def test_missing_or_inherited_license_stays_unknown_even_when_notice_exists(self):
        inherited = '<parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent>'
        node, content = self.dependency("inherited", license_xml=inherited)
        self.project["children"] = [node]
        self.libraries = {"BOOT-INF/lib/inherited.jar": content}

        result = self.run_inventory()

        self.assertEqual("COMPLETE", result["completeness"])
        self.assertEqual(["example:inherited:jar:1.0"], result["unknownLicenseCoordinates"])
        self.assertEqual("ABSENT_OR_INHERITED_NOT_RESOLVED", result["artifacts"][0]["pom"]["unknownReason"])
        self.assertTrue(result["artifacts"][0]["licenseNotices"])

    def test_modified_packaged_bytes_fail_and_cli_retains_incomplete_report(self):
        node, _ = self.dependency("changed")
        self.project["children"] = [node]
        self.libraries = {"BOOT-INF/lib/changed-1.0.jar": jar_bytes({"changed.class": b"different"})}
        result = self.run_inventory()
        codes = {error["code"] for error in result["errors"]}
        self.assertIn("UNMATCHED_PACKAGED_LIBRARY", codes)
        self.assertIn("RUNTIME_ARTIFACT_NOT_PACKAGED", codes)
        with contextlib.redirect_stdout(io.StringIO()):
            exit_code = inventory.main(["--tree", str(self.tree), "--boot-jar", str(self.boot),
                                       "--repository", str(self.repository), "--output", str(self.output)])
        self.assertEqual(2, exit_code)
        self.assertEqual("INCOMPLETE", json.loads(self.output.read_text())["completeness"])

    def test_missing_runtime_package_and_missing_cache_pom_are_explicit_errors(self):
        node, _ = self.dependency("missing")
        self.project["children"] = [node]
        (self.repository / "example/missing/1.0/missing-1.0.pom").unlink()
        result = self.run_inventory()
        self.assertEqual({"MISSING_RESOLVED_POM", "RUNTIME_ARTIFACT_NOT_PACKAGED"},
                         {error["code"] for error in result["errors"]})
        self.assertEqual(["example:missing:jar:1.0"], result["unknownLicenseCoordinates"])

    def test_identical_bytes_under_two_runtime_coordinates_are_not_guessed(self):
        content = jar_bytes({"same.class": b"same"})
        first, _ = self.dependency("first", jar=content)
        second, _ = self.dependency("second", jar=content)
        self.project["children"] = [first, second]
        self.libraries = {"BOOT-INF/lib/not-a-coordinate.jar": content}
        result = self.run_inventory()
        self.assertEqual("INCOMPLETE", result["completeness"])
        ambiguity = next(error for error in result["errors"] if error["code"] == "AMBIGUOUS_PACKAGED_LIBRARY")
        self.assertEqual(["example:first:jar:1.0", "example:second:jar:1.0"], ambiguity["coordinates"])
        self.assertIsNone(result["packagedLibraries"][0]["coordinate"])

    def test_unresolved_license_properties_remain_unknown(self):
        node, content = self.dependency("property", license_xml='<licenses><license><name>${license.name}</name></license></licenses>')
        self.project["children"] = [node]
        self.libraries = {"BOOT-INF/lib/property.jar": content}
        result = self.run_inventory()
        self.assertEqual("UNKNOWN", result["artifacts"][0]["pom"]["licenseStatus"])
        self.assertEqual("UNRESOLVED_DECLARATION", result["artifacts"][0]["pom"]["unknownReason"])

    def test_rejects_pom_entities_and_coordinate_path_traversal(self):
        node, content = self.dependency("unsafe")
        self.project["children"] = [node]
        self.libraries = {"BOOT-INF/lib/unsafe.jar": content}
        pom = self.repository / "example/unsafe/1.0/unsafe-1.0.pom"
        pom.write_text('<!DOCTYPE project [<!ENTITY x "text">]><project/>')
        with self.assertRaises(inventory.InventoryError):
            self.run_inventory()
        node["version"] = "../elsewhere"
        with self.assertRaises(inventory.InventoryError):
            self.run_inventory()

    def test_rejects_oversized_license_without_extracting_any_members(self):
        content = jar_bytes({"META-INF/LICENSE": b"x" * (inventory.MAX_LICENSE_BYTES + 1)})
        node, _ = self.dependency("large", jar=content)
        self.project["children"] = [node]
        with self.assertRaises(inventory.InventoryError):
            self.run_inventory()
        self.assertFalse((self.root / "META-INF").exists())

    def test_repeated_safe_directory_entries_do_not_make_file_evidence_ambiguous(self):
        output = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(output, "w") as archive:
                archive.writestr("META-INF/", b"")
                archive.writestr("META-INF/", b"")
                archive.writestr("META-INF/LICENSE", b"original terms")
        node, content = self.dependency("directories", jar=output.getvalue())
        self.project["children"] = [node]
        self.libraries = {"BOOT-INF/lib/directories.jar": content}
        result = self.run_inventory()
        self.assertEqual("COMPLETE", result["completeness"])
        self.assertEqual(["META-INF/LICENSE"],
                         [item["entry"] for item in result["artifacts"][0]["licenseNotices"]])

    def test_duplicate_files_remain_rejected(self):
        output = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(output, "w") as archive:
                archive.writestr("META-INF/LICENSE", b"first terms")
                archive.writestr("META-INF/LICENSE", b"second terms")
        path = self.root / "duplicate.jar"
        path.write_bytes(output.getvalue())
        with self.assertRaisesRegex(inventory.InventoryError, "Duplicate JAR entries"):
            inventory.license_notices(path)

    def test_unsafe_directory_entries_remain_rejected(self):
        path = self.root / "unsafe-directory.jar"
        path.write_bytes(jar_bytes({"../unsafe/": b""}))
        with self.assertRaisesRegex(inventory.InventoryError, "Unsafe JAR member"):
            inventory.license_notices(path)

    def test_boot_exact_manifest_markers_exclude_build_tools_but_keep_evidence(self):
        for marker in ("annotation-processor", "dependencies-starter", "development-tool"):
            with self.subTest(marker=marker):
                name = marker.replace("-", "_")
                content = jar_bytes({"META-INF/MANIFEST.MF":
                                     ("Manifest-Version: 1.0\r\nSpring-Boot-Jar-Type: " + marker + "\r\n\r\n").encode()})
                node, _ = self.dependency(name, jar=content)
                self.project["children"] = [node]
                result = self.run_inventory()
                self.assertEqual("COMPLETE", result["completeness"])
                artifact = result["artifacts"][0]
                self.assertEqual("BUILD_TOOL_EXCLUDED", artifact["packagingCoverage"])
                self.assertTrue(artifact["artifact"]["sha256"])
                self.assertEqual(marker, artifact["manifest"]["mainAttributes"]["Spring-Boot-Jar-Type"])

    def test_unknown_or_nonexact_manifest_marker_does_not_excuse_missing_runtime(self):
        for index, marker in enumerate(("unknown", "annotation-processor ", "ANNOTATION-PROCESSOR")):
            content = jar_bytes({"META-INF/MANIFEST.MF": ("Manifest-Version: 1.0\nSpring-Boot-Jar-Type: " + marker + "\n\n").encode()})
            node, _ = self.dependency("not_excluded_" + str(index), jar=content)
            self.project["children"] = [node]
            result = self.run_inventory()
            self.assertEqual(["RUNTIME_ARTIFACT_NOT_PACKAGED"], [error["code"] for error in result["errors"]])

    def test_named_manifest_section_cannot_exclude_the_whole_runtime_jar(self):
        content = jar_bytes({"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n\nName: nested.class\nSpring-Boot-Jar-Type: annotation-processor\n\n"})
        node, _ = self.dependency("named_section", jar=content)
        self.project["children"] = [node]
        result = self.run_inventory()
        self.assertEqual(["RUNTIME_ARTIFACT_NOT_PACKAGED"], [error["code"] for error in result["errors"]])
        self.assertNotIn("Spring-Boot-Jar-Type", result["artifacts"][0]["manifest"]["mainAttributes"])

    def test_manifest_continuation_is_folded_and_duplicates_or_orphans_rejected(self):
        path = self.root / "manifest.jar"
        path.write_bytes(jar_bytes({"META-INF/MANIFEST.MF":
                                   b"Manifest-Version: 1.0\r\nsPrInG-BoOt-JaR-TyPe: annotation-\r\n processor\r\n\r\n"}))
        evidence = inventory.manifest_evidence(path)
        self.assertEqual("annotation-processor", evidence["mainAttributes"]["Spring-Boot-Jar-Type"])
        for raw in (b"Manifest-Version: 1.0\nSpring-Boot-Jar-Type: annotation-processor\nspring-boot-jar-type: development-tool\n\n",
                    b" orphan\nManifest-Version: 1.0\n\n"):
            path.write_bytes(jar_bytes({"META-INF/MANIFEST.MF": raw}))
            with self.assertRaises(inventory.InventoryError):
                inventory.manifest_evidence(path)

    def test_plugin_embedded_library_requires_explicit_host_evidence_not_a_fake_coordinate(self):
        content = jar_bytes({"tools.class": b"actual embedded bytes"})
        _, host = self.dependency("packaging_host", jar=jar_bytes({"META-INF/jarmode/tools.jar": content,
                                                                 "META-INF/NOTICE": b"host notice"}))
        self.libraries = {"BOOT-INF/lib/tools.jar": content}
        incomplete = self.run_inventory()
        self.assertEqual(["UNMATCHED_PACKAGED_LIBRARY"], [error["code"] for error in incomplete["errors"]])
        source = "example:packaging_host:jar:1.0!META-INF/jarmode/tools.jar"
        result = inventory.build_inventory(self.tree, self.boot, self.repository, source)
        self.assertEqual("COMPLETE", result["completeness"])
        self.assertEqual([], result["dependencyEdges"])
        self.assertEqual([], result["artifacts"])
        library = result["packagedLibraries"][0]
        self.assertEqual("EMBEDDED_PACKAGING_LIBRARY", library["origin"])
        self.assertIsNone(library["coordinate"])
        proof = result["packagingEvidence"][0]
        self.assertEqual("example:packaging_host:jar:1.0", proof["hostCoordinate"])
        self.assertEqual("META-INF/jarmode/tools.jar", proof["embeddedEntry"])
        self.assertEqual(inventory.hashlib.sha256(host).hexdigest(), proof["hostArtifact"]["sha256"])
        self.assertEqual(inventory.hashlib.sha256(content).hexdigest(), proof["embeddedSha256"])
        self.assertNotEqual(incomplete["inputHashes"]["packagingInputSha256"], result["inputHashes"]["packagingInputSha256"])
        with contextlib.redirect_stdout(io.StringIO()):
            exit_code = inventory.main(["--tree", str(self.tree), "--boot-jar", str(self.boot),
                                       "--repository", str(self.repository), "--output", str(self.output),
                                       "--embedded-packaging-library", source])
        self.assertEqual(0, exit_code)

    def test_embedded_source_does_not_hide_ambiguous_runtime_bytes(self):
        node, content = self.dependency("same_bytes")
        self.dependency("host", jar=jar_bytes({"META-INF/jarmode/tools.jar": content}))
        self.project["children"] = [node]
        self.libraries = {"BOOT-INF/lib/same.jar": content}
        self.run_inventory()
        result = inventory.build_inventory(self.tree, self.boot, self.repository,
                                           "example:host:jar:1.0!META-INF/jarmode/tools.jar")
        self.assertIn("AMBIGUOUS_PACKAGED_LIBRARY", [error["code"] for error in result["errors"]])

    def test_invalid_or_missing_embedded_sources_are_not_assumed(self):
        self.run_inventory()
        for source in ("example:extra:zip:1.0!tools.jar", "example:extra:jar:../escape!tools.jar",
                       "example:extra:jar:1.0!../tools.jar", "example:extra:jar:1.0!/tools.jar"):
            with self.assertRaises(inventory.InventoryError):
                inventory.build_inventory(self.tree, self.boot, self.repository, source)
        missing = inventory.build_inventory(self.tree, self.boot, self.repository,
                                            "example:absent:jar:1.0!tools.jar")
        self.assertEqual("INCOMPLETE", missing["completeness"])

    def test_embedded_byte_mismatch_and_duplicate_cli_sources_fail_coverage(self):
        self.dependency("host", jar=jar_bytes({"tools.jar": b"source bytes"}))
        self.libraries = {"BOOT-INF/lib/tools.jar": b"modified bytes"}
        self.run_inventory()
        source = "example:host:jar:1.0!tools.jar"
        result = inventory.build_inventory(self.tree, self.boot, self.repository, source)
        self.assertEqual({"UNMATCHED_PACKAGED_LIBRARY", "EMBEDDED_PACKAGING_LIBRARY_NOT_PACKAGED"},
                         {error["code"] for error in result["errors"]})
        with contextlib.redirect_stderr(io.StringIO()):
            exit_code = inventory.main(["--tree", str(self.tree), "--boot-jar", str(self.boot),
                                       "--repository", str(self.repository), "--output", str(self.output),
                                       "--embedded-packaging-library", source,
                                       "--embedded-packaging-library", source])
        self.assertEqual(2, exit_code)

    def test_duplicate_json_keys_cannot_hide_a_missing_dependency(self):
        root = json.dumps(self.project).removesuffix("}")
        self.tree.write_text(root + ', "children": [{"groupId":"example","artifactId":"missing","type":"jar","version":"1","scope":"runtime"}], "children": []}')
        self.boot.write_bytes(jar_bytes({}))
        self.output.write_text("retained report")
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            exit_code = inventory.main(["--tree", str(self.tree), "--boot-jar", str(self.boot),
                                       "--repository", str(self.repository), "--output", str(self.output)])
        self.assertEqual(2, exit_code)
        self.assertNotIn("Traceback", stderr.getvalue())
        self.assertEqual("retained report", self.output.read_text())

    def test_nonstandard_json_constants_are_not_accepted_as_resolved_evidence(self):
        self.boot.write_bytes(jar_bytes({}))
        for constant in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(constant=constant):
                self.tree.write_text(json.dumps(self.project).removesuffix("}") + ', "ignored": ' + constant + '}')
                with self.assertRaises(inventory.InventoryError):
                    inventory.build_inventory(self.tree, self.boot, self.repository)

    def test_real_unsupported_and_encrypted_zip_members_use_generic_failure_and_keep_report(self):
        self.tree.write_text(json.dumps(self.project))
        original = jar_bytes({"BOOT-INF/lib/tool.jar": b"evidence bytes"})
        for mode in ("unsupported-compression", "encrypted"):
            with self.subTest(mode=mode):
                patched = bytearray(original)
                local = patched.index(b"PK\x03\x04")
                central = patched.index(b"PK\x01\x02")
                if mode == "unsupported-compression":
                    struct.pack_into("<H", patched, local + 8, 99)
                    struct.pack_into("<H", patched, central + 10, 99)
                else:
                    struct.pack_into("<H", patched, local + 6, 1)
                    struct.pack_into("<H", patched, central + 8, 1)
                self.boot.write_bytes(patched)
                self.output.write_text("retained report")
                with contextlib.redirect_stderr(io.StringIO()) as stderr:
                    exit_code = inventory.main(["--tree", str(self.tree), "--boot-jar", str(self.boot),
                                               "--repository", str(self.repository), "--output", str(self.output)])
                self.assertEqual(2, exit_code)
                self.assertEqual("Inventory failed: invalid, unsafe or unavailable evidence input\n", stderr.getvalue())
                self.assertEqual("retained report", self.output.read_text())

    def test_oversized_or_zip64_central_directory_is_rejected_before_zipfile_allocates(self):
        path = self.root / "declared-bounds.jar"
        cases = {
            "central-size": struct.pack("<4s4H2LH", b"PK\x05\x06", 0, 0, 1, 1, 17 * 1024 * 1024, 0, 0),
            "entry-count": struct.pack("<4s4H2LH", b"PK\x05\x06", 0, 0, 20_001, 20_001, 0, 0, 0),
            "zip64-sentinel": struct.pack("<4s4H2LH", b"PK\x05\x06", 0, 0, 0xffff, 0xffff, 0xffffffff, 0xffffffff, 0),
            "zip64-locator": b"PK\x06\x07" + bytes(16) + struct.pack("<4s4H2LH", b"PK\x05\x06", 0, 0, 0, 0, 0, 0, 0),
            "multi-disk": struct.pack("<4s4H2LH", b"PK\x05\x06", 1, 1, 0, 0, 0, 0, 0),
        }
        for name, content in cases.items():
            with self.subTest(name=name):
                path.write_bytes(content)
                with mock.patch.object(inventory.zipfile, "ZipFile") as constructor:
                    with self.assertRaises(inventory.InventoryError):
                        inventory.packaged_libraries(path)
                    constructor.assert_not_called()

    def test_competing_eocd_signature_in_comment_is_rejected_before_zipfile(self):
        path = self.root / "competing-eocd.jar"
        # The earlier EOCD reaches EOF; the last signature does not. ZipFile
        # selects the last signature regardless, so preflight must not fall back.
        fake = struct.pack("<4s4H2LH", b"PK\x05\x06", 0, 0, 1, 1, 17 * 1024 * 1024, 0, 1)
        earlier = struct.pack("<4s4H2LH", b"PK\x05\x06", 0, 0, 0, 0, 0, 0, len(fake))
        path.write_bytes(earlier + fake)
        with mock.patch.object(inventory.zipfile, "ZipFile") as constructor:
            with self.assertRaises(inventory.InventoryError):
                inventory.packaged_libraries(path)
            constructor.assert_not_called()

    def test_real_unsupported_extract_version_uses_generic_failure_and_keeps_report(self):
        self.tree.write_text(json.dumps(self.project))
        patched = bytearray(jar_bytes({"BOOT-INF/lib/tool.jar": b"evidence bytes"}))
        local = patched.index(b"PK\x03\x04")
        central = patched.index(b"PK\x01\x02")
        struct.pack_into("<H", patched, local + 4, 255)
        struct.pack_into("<H", patched, central + 6, 255)
        self.boot.write_bytes(patched)
        self.output.write_text("retained report")
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            exit_code = inventory.main(["--tree", str(self.tree), "--boot-jar", str(self.boot),
                                       "--repository", str(self.repository), "--output", str(self.output)])
        self.assertEqual(2, exit_code)
        self.assertEqual("Inventory failed: invalid, unsafe or unavailable evidence input\n", stderr.getvalue())
        self.assertEqual("retained report", self.output.read_text())

    def test_real_corrupt_deflate_and_lzma_members_use_generic_failure_and_keep_report(self):
        self.tree.write_text(json.dumps(self.project))
        for compression in (zipfile.ZIP_DEFLATED, zipfile.ZIP_LZMA):
            with self.subTest(compression=compression):
                output = io.BytesIO()
                with zipfile.ZipFile(output, "w", compression=compression) as archive:
                    archive.writestr("BOOT-INF/lib/tool.jar", b"compressed evidence bytes" * 8)
                patched = bytearray(output.getvalue())
                local = patched.index(b"PK\x03\x04")
                name_size, extra_size = struct.unpack_from("<HH", patched, local + 26)
                data_start = local + 30 + name_size + extra_size
                if compression == zipfile.ZIP_DEFLATED:
                    # BFINAL=1/BTYPE=3 is an invalid DEFLATE block header.
                    patched[data_start] = 0x07
                else:
                    # First LZMA property byte follows the four-byte ZIP-LZMA header.
                    patched[data_start + 4] = 0xff
                self.boot.write_bytes(patched)
                self.output.write_text("retained report")
                with contextlib.redirect_stderr(io.StringIO()) as stderr:
                    exit_code = inventory.main(["--tree", str(self.tree), "--boot-jar", str(self.boot),
                                               "--repository", str(self.repository), "--output", str(self.output)])
                self.assertEqual(2, exit_code)
                self.assertEqual("Inventory failed: invalid, unsafe or unavailable evidence input\n", stderr.getvalue())
                self.assertEqual("retained report", self.output.read_text())


if __name__ == "__main__":
    unittest.main()
