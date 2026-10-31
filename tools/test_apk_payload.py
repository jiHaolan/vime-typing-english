"""APK payload optimization must preserve content and leave signing explicit."""
import tempfile
from pathlib import Path
import unittest
import zipfile
from optimize_apk_payload import optimize


class PayloadChecks(unittest.TestCase):
    def test_recompression_preserves_native_resources_and_non_signature_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'input.apk';output=root/'unsigned.apk'
            values={'classes.dex':b'dex\n'+b'full vocabulary '*1000,'lib/arm64-v8a/libtest.so':b'\x7fELF'+bytes(range(256))*10,
                    'META-INF/test.kotlin_module':b'module metadata','resources.arsc':b'resource bytes'}
            with zipfile.ZipFile(source,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=1) as z:
                for name,data in values.items():z.writestr(name,data,compress_type=zipfile.ZIP_STORED if name.endswith(('.so','.arsc')) else zipfile.ZIP_DEFLATED)
                z.writestr('META-INF/CERT.SF',b'obsolete signature');z.writestr('META-INF/CERT.RSA',b'obsolete certificate')
            result=optimize(source,output)
            with zipfile.ZipFile(output) as z:
                self.assertEqual(set(z.namelist()),set(values))
                for name,data in values.items():self.assertEqual(z.read(name),data)
                self.assertEqual(z.getinfo('lib/arm64-v8a/libtest.so').compress_type,zipfile.ZIP_STORED)
            self.assertTrue(result['unsigned'] and result['requires_zipalign_and_sign'])
            self.assertTrue(result['all_payload_bytes_unchanged'])

    def test_existing_delivery_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);output=root/'keep.apk';output.write_bytes(b'keep')
            with self.assertRaises(ValueError):optimize(root/'absent.apk',output)
            self.assertEqual(output.read_bytes(),b'keep')

    def test_duplicate_apk_entries_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'input.apk'
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter('ignore',UserWarning)
                with zipfile.ZipFile(source,'w') as z:z.writestr('classes.dex',b'a');z.writestr('classes.dex',b'b')
            with self.assertRaises(ValueError):optimize(source,root/'unsigned.apk')


if __name__=='__main__':unittest.main()
