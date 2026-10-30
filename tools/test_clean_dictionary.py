"""Protect meaning, pronunciation and source isolation during dictionary rebuilds."""
import argparse
import gzip
import json
from pathlib import Path
import tempfile
import unittest

import compile_clean_dictionary as compiler
import prepare_clean_dictionary as data


def source(*rows):
    return gzip.compress(("# Attribution-ShareAlike 4.0\n" + "\n".join(rows) + "\n").encode("utf-8"))


class CleanDictionaryTests(unittest.TestCase):
    def test_luna_reading_percentages_do_not_become_equal_common_frequencies(self):
        source = "...\n我\te\t0%\n我\two\t100%\n的\tde\t99.97%\n的\tdi\t0.03%\n给\tgei\t99.99%\n给\tji\t0.01%\n".encode()
        rows = data.luna_entries(source, {"我": 100000, "的": 1000000, "给": 200000})
        self.assertEqual(rows["我", "e"], 0)
        self.assertEqual(rows["我", "wo"], 100000)
        self.assertEqual(rows["的", "de"], 999700)
        self.assertEqual(rows["的", "di"], 300)
        self.assertEqual(rows["给", "gei"], 199980)
        self.assertEqual(rows["给", "ji"], 20)

    def test_luna_duplicate_variants_do_not_inflate_code_weight(self):
        rows = data.luna_entries("...\n樂\tle\t95%\n乐\tle\t95%\n樂\tyue\t5%\n".encode(), {"乐": 1000})
        self.assertEqual(rows, {("乐", "le"): 950, ("乐", "yue"): 50})
        self.assertEqual(data.reading_weight("12.5", 1000), data.Decimal("12.5"))
        with self.assertRaises(ValueError): data.reading_weight("NaN", 1000)

    def forms(self, *rows):
        records, skipped = data.load_cedict(source(*rows), len(rows))
        self.assertEqual(skipped, {})
        return {(r[0], r[1]): r for r in data.derive_senses(records)}

    def test_polyphonic_meanings_follow_their_reading(self):
        forms = self.forms("重 重 [chong2] /to repeat/repetition/again/", "重 重 [zhong4] /heavy/serious/")
        self.assertEqual(forms["重", "chong"][3:6], ["to repeat", "to repeat", "repetition"])
        self.assertEqual(forms["重", "zhong"][3:6], ["heavy", "heavy", "serious"])
        self.assertEqual(forms["重", "chong"][6], [1])
        self.assertEqual(forms["重", "zhong"][6], [2])

    def test_equivalent_reference_uses_the_referenced_reading_and_retains_provenance(self):
        forms = self.forms("長 长 [chang2] /long/length/", "長 长 [zhang3] /to grow/chief/",
                           "仧 仧 [zhang3] /old variant of 長|长[zhang3]/")
        self.assertEqual(forms["仧", "zhang"][3:6], ["to grow", "to grow", "chief"])
        self.assertEqual(forms["仧", "zhang"][6], [2, 3])

    def test_used_in_does_not_borrow_the_whole_words_meaning(self):
        forms = self.forms("龥 龥 [yu4] /used in 龠龥[yue4 yu4]/", "龠龥 龠龥 [yue4 yu4] /harmony/")
        self.assertEqual(forms["龥", "yu"][3], "")
        self.assertEqual(forms["龥", "yu"][4], "used in 龠龥[yue4 yu4]")

    def test_reference_cycles_terminate_without_inventing_a_gloss(self):
        forms = self.forms("甲 甲 [jia3] /variant of 乙[yi3]/", "乙 乙 [yi3] /variant of 甲[jia3]/")
        self.assertEqual(forms["甲", "jia"][3], "")
        self.assertEqual(forms["乙", "yi"][3], "")

    def test_common_sense_precedes_surname_with_same_code(self):
        forms = self.forms("樂 乐 [Le4] /surname Le/", "樂 乐 [le4] /happy/pleased/")
        self.assertEqual(forms["乐", "le"][2:6], ["le4", "happy", "happy", "pleased"])
        self.assertEqual(forms["乐", "le"][6], [1, 2])

    def test_classifiers_are_preserved_in_source_but_not_used_as_english_labels(self):
        forms = self.forms("書 书 [shu1] /book/CL:本[ben3],冊|册[ce4]/letter/")
        self.assertEqual(forms["书", "shu"][3:6], ["book", "book", "letter"])

    def test_tone_and_umlaut_normalization_retains_mixed_headwords(self):
        records, skipped = data.load_cedict(source("女 女 [nu:3] /female/woman/", "X光 X光 [X guang1] /X-ray/"), 2)
        self.assertEqual(records["女"][0]["code"], "nv")
        self.assertEqual(records["X光"][0]["code"], "xguang")
        self.assertFalse(skipped)

    def test_unspellable_source_is_counted_not_fabricated(self):
        records, skipped = data.load_cedict(source("兙 兙 [shi2 ke4] /decagram/", "兣 兣 [???] /centigram/"), 2)
        self.assertIn("兙", records)
        self.assertNotIn("兣", records)
        self.assertEqual(skipped, {"unsupported-phonetics-or-headword-length": 1})

    def test_wrong_license_or_record_count_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "license"):
            data.load_cedict(gzip.compress(b"# CC-BY-SA-3.0\n"), 0)
        with self.assertRaisesRegex(ValueError, "record count"):
            data.load_cedict(source("你 你 [ni3] /you/"), 2)

    def test_controls_and_malformed_records_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Control character"):
            data.load_cedict(source("你 你 [ni3] /you\tthere/"), 1)
        with self.assertRaisesRegex(ValueError, "Malformed"):
            data.load_cedict(source("not a dictionary record"), 1)

    def test_existing_output_is_preserved_before_any_input_read(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            sentinel = output / "evidence.txt"
            sentinel.write_text("keep")
            lock = output / "lock.json"
            lock.write_text('{"sources":{}}')
            with self.assertRaisesRegex(ValueError, "existing data is preserved"):
                data.build(argparse.Namespace(lock=lock, output=output))
            self.assertEqual(sentinel.read_text(), "keep")

    def test_delivered_snapshot_requires_the_exact_canonical_blob(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            canonical = b"upstream\nlicense bytes\n"
            (folder / "LICENSE").write_bytes(canonical)
            spec = {"commit": "0" * 40, "files": {"LICENSE": data.sha(canonical)}}
            self.assertEqual(data.source_blob(folder, spec, "LICENSE"), canonical)
            (folder / "LICENSE").write_bytes(canonical.replace(b"\n", b"\r\n"))
            with self.assertRaisesRegex(ValueError, "blob mismatch"):
                data.source_blob(folder, spec, "LICENSE")

    def test_compiler_refuses_old_compiled_or_extra_user_files(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            hashes = {}
            for name in sorted(compiler.INPUTS):
                (folder / name).write_bytes(b"public-source")
                hashes[name] = compiler.sha(b"public-source")
            manifest = {"format": "vime-rime-2", "compiled": False, "files": hashes,
                        "source_id": compiler.sha(compiler.encode(hashes))}
            (folder / "manifest.json").write_text(json.dumps(manifest))
            self.assertEqual(compiler.check_source(folder), manifest)
            (folder / "private.userdb").mkdir()
            with self.assertRaisesRegex(ValueError, "extra files"):
                compiler.check_source(folder)
            (folder / "private.userdb").rmdir()
            (folder / "essay.txt").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "differs"):
                compiler.check_source(folder)


if __name__ == "__main__":
    unittest.main()
