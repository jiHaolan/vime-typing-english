"""Distribution integrity and release-gate regressions against actual source assets."""
import copy
import json
from pathlib import Path
import csv
import tempfile
import unittest
from verify_notices import inspect
from prepare_levels import levels

ROOT = Path(__file__).resolve().parents[1]


class NoticesChecks(unittest.TestCase):
    def setUp(self):
        self.assets = ROOT / "app/src/main/assets"
        self.catalog = json.loads((self.assets / "notices.json").read_text("utf-8"))

    def read(self, name):
        return (self.assets / name).read_bytes()

    def test_preview_integrity_does_not_imply_commercial_permission(self):
        preview = inspect(self.read)
        self.assertTrue(preview["passed"])
        self.assertFalse(preview["commercial_distribution_ready"])
        release = inspect(self.read, commercial=True)
        self.assertFalse(release["passed"])
        self.assertIn("commercial-source-review-complete", release["failed_checks"])

    def test_truncated_license_is_rejected(self):
        result = inspect(lambda name: b"short replacement" if name == "licenses/GNU-GPL-3.0.txt" else self.read(name))
        self.assertFalse(result["passed"])
        self.assertIn("gpl-companion-is-complete", result["failed_checks"])

    def test_compatibility_label_comment_is_not_a_complete_license(self):
        result = inspect(lambda name: b"/* Copyright notice in lua.h */" if name == "licenses/lua-compat-NOTICE.txt" else self.read(name))
        self.assertIn("lua-compat-notices-are-complete", result["failed_checks"])

    def test_provider_plugin_regression_and_stale_native_bytes_are_rejected(self):
        source = json.loads(self.read("native-sources.json")); source["octagram_included"] = True
        result = inspect(lambda name: json.dumps(source).encode() if name == "native-sources.json" else self.read(name))
        self.assertIn("native-source-and-plugin-policy", result["failed_checks"])
        result = inspect(self.read, native_libraries={"x86_64/libvime_rime.so": b"old binary", "x86_64/libc++_shared.so": b"wrong runtime"})
        self.assertIn("native-binary-bytes:x86_64/libvime_rime.so", result["failed_checks"])

    def test_license_missing_from_package_is_rejected(self):
        def read(name):
            if name == "licenses/ecdict-MIT.txt":
                raise FileNotFoundError("fixture removed license")
            return self.read(name)
        self.assertFalse(inspect(read)["passed"])

    def test_new_runtime_dependency_requires_notice_refresh(self):
        inventory = {"modules": self.catalog["runtime_modules"] + ["unreviewed:library:1.0"],
                     "artifacts": self.catalog["runtime_artifacts"]}
        result = inspect(self.read, inventory)
        self.assertIn("actual-runtime-module-coverage", result["failed_checks"])

    def test_changed_runtime_binary_is_rejected(self):
        inventory = {"modules": self.catalog["runtime_modules"], "artifacts": copy.deepcopy(self.catalog["runtime_artifacts"])}
        inventory["artifacts"][0]["sha256"] = "0" * 64
        result = inspect(self.read, inventory)
        self.assertIn("actual-runtime-artifact-hashes", result["failed_checks"])

    def test_exam_tag_edit_without_source_receipt_update_is_rejected(self):
        dictionary = json.loads(self.read("english/levels.json"))
        dictionary["entries"]["unverified-exam-tag"] = "CET4"
        result = inspect(lambda name: json.dumps(dictionary).encode() if name == "english/levels.json" else self.read(name))
        self.assertIn("exam-tag-asset-hashes", result["failed_checks"])

    def test_corrupt_mapped_input_is_rejected(self):
        data = bytearray(self.read("input/input.lex"))
        data[-1] ^= 1
        result = inspect(lambda name: bytes(data) if name == "input/input.lex" else self.read(name))
        self.assertIn("input-asset-bytes:input.lex", result["failed_checks"])

    def test_corrupt_mapped_english_is_rejected(self):
        data = bytearray(self.read("english/english.lex")); data[-1] ^= 1
        result = inspect(lambda name: bytes(data) if name == "english/english.lex" else self.read(name))
        self.assertIn("english-asset-bytes:english.lex", result["failed_checks"])

    def test_incomplete_english_and_changed_runtime_are_rejected(self):
        data = json.loads(self.read("english/manifest.json")); data["counts"]["senses"] -= 1
        result = inspect(lambda name: json.dumps(data).encode() if name == "english/manifest.json" else self.read(name))
        self.assertIn("complete-english-and-tonal-forms", result["failed_checks"])
        name = "rime/build/vime_pinyin.table.bin"
        result = inspect(lambda path: b"truncated table" if path == name else self.read(path))
        self.assertIn("runtime-asset-bytes:build/vime_pinyin.table.bin", result["failed_checks"])

    def test_input_attribution_cannot_be_removed_from_the_catalog(self):
        path = "licenses/input-data-NOTICE.txt"
        self.catalog["files"].pop(path)
        for component in self.catalog["components"]:
            component["documents"] = [name for name in component["documents"] if name != path]
        result = inspect(lambda name: json.dumps(self.catalog).encode() if name == "notices.json" else self.read(name))
        self.assertIn("required-license-documents", result["failed_checks"])

    def test_smaller_input_manifest_does_not_pass_as_the_complete_vocabulary(self):
        data = json.loads(self.read("input/manifest.json"))
        data["counts"]["entries"] = 100
        result = inspect(lambda name: json.dumps(data).encode() if name == "input/manifest.json" else self.read(name))
        self.assertIn("complete-clean-input-source-counts", result["failed_checks"])

    def test_unsafe_document_path_stops_verification(self):
        self.catalog["files"]["licenses/../../private.txt"] = "0" * 64
        result = inspect(lambda name: json.dumps(self.catalog).encode() if name == "notices.json" else self.read(name))
        self.assertFalse(result["passed"])
        self.assertIn("Unsafe", result["error"])

    def test_level_policy_keeps_basics_prioritizes_lower_tiers_and_excludes_untagged(self):
        # Real CSV columns with enough tagged rows to satisfy source-completeness bounds.
        rows=[]
        for tag in ("cet4", "cet6", "toefl"):
            for i in range(1002):
                suffix=chr(97+i//26//26)+chr(97+i//26%26)+chr(97+i%26)
                rows.append(("fixture"+tag.replace("4","four").replace("6","six")+suffix,tag))
        rows += [("HELLO","zk gk"),("study","gk"),("contemplate","cet6 toefl"),
                 ("reclassified","toefl"),("reclassified","cet4"),("untagged",""),("bad/path","cet4")]
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"input.csv"
            with path.open("w",encoding="utf-8",newline="") as stream:
                writer=csv.writer(stream);writer.writerow(("word","tag"));writer.writerows(rows)
            result=levels(path)
        self.assertEqual("CET4",result["hello"])
        self.assertEqual("CET4",result["study"])
        self.assertEqual("CET6",result["contemplate"])
        self.assertEqual("CET4",result["reclassified"])
        self.assertNotIn("untagged",result);self.assertNotIn("bad/path",result)


if __name__ == "__main__":
    unittest.main()
