"""Inspect the produced APK, not source declarations. Standard library + Android SDK tools."""
from __future__ import annotations
import argparse, hashlib, json, os, re, shutil, struct, subprocess, tempfile, zipfile
from pathlib import Path

PREVIEW_CERT = 'c41ce7fe52931687d7ef365d4dd6e4f7f4cd82ad1041f9616479ff30155adc29'
REQUIRED_PERMISSIONS = {'android.permission.RECORD_AUDIO','android.permission.VIBRATE','android.permission.INTERNET'}

def elf_loads(data: bytes) -> list[dict]:
    if data[:4] != b'\x7fELF' or data[5] != 1: raise ValueError('unsupported ELF header')
    if data[4] == 2:
        offset=struct.unpack_from('<Q',data,32)[0]; size,count=struct.unpack_from('<HH',data,54)
        fmt='<IIQQQQQQ'; index=(2,3,7)
    elif data[4] == 1:
        offset=struct.unpack_from('<I',data,28)[0]; size,count=struct.unpack_from('<HH',data,42)
        fmt='<IIIIIIII'; index=(1,2,7)
    else: raise ValueError('unsupported ELF class')
    if size < struct.calcsize(fmt) or offset+size*count > len(data): raise ValueError('truncated ELF program headers')
    result=[]
    for i in range(count):
        header=struct.unpack_from(fmt,data,offset+i*size)
        if header[0] != 1: continue
        file_offset,address,alignment=(header[j] for j in index)
        result.append({'offset':file_offset,'address':address,'alignment':alignment,
            'page_16k':alignment>=16384 and alignment & (alignment-1)==0 and file_offset%16384==address%16384})
    if not result: raise ValueError('ELF contains no LOAD segments')
    return result

def zip_data_offset(source, entry):
    source.seek(entry.header_offset); header=source.read(30)
    if header[:4] != b'PK\x03\x04': raise ValueError('invalid ZIP local header')
    name,extra=struct.unpack_from('<HH',header,26)
    return entry.header_offset+30+name+extra

def verify(apk: Path, sdk: Path, tools: str, certificate: str, version: str|None=None, code: int|None=None):
    apk=apk.resolve(); sdk=sdk.resolve()
    certificate=certificate.lower().replace(':','')
    if not re.fullmatch('[a-f0-9]{64}',certificate): raise ValueError('Expected certificate SHA-256 must contain 64 hex digits')
    with apk.open('rb') as source:
        apk_sha256=hashlib.file_digest(source,'sha256').hexdigest()
    report={'file':apk.name,'bytes':apk.stat().st_size,'sha256':apk_sha256,'checks':[],'native':[]}
    def check(name, passed, detail=None): report['checks'].append({'name':name,'pass':bool(passed),'detail':detail})
    folder=sdk/'build-tools'/tools
    suffix='.exe' if os.name=='nt' else ''
    aapt=folder/('aapt2'+suffix)
    signer=folder/('apksigner.bat' if os.name=='nt' else 'apksigner')
    aligner=folder/('zipalign'+suffix)
    for tool in (aapt,signer,aligner):
        if not tool.is_file(): raise FileNotFoundError(f'Missing Android build tool: {tool.name}')
    def run(args):
        p=subprocess.run([str(a) for a in args],capture_output=True,timeout=90)
        return p.returncode,(p.stdout+p.stderr).decode('utf-8','replace')
    with tempfile.TemporaryDirectory(prefix='vime-release-') as staging:
        # SDK native tools on Windows can fail with non-ASCII artifact paths.
        staged=Path(staging)/'candidate.apk'; shutil.copyfile(apk,staged)
        rc,badging=run([aapt,'dump','badging',staged])
        if rc: raise RuntimeError('Cannot inspect APK manifest')
        package=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
        if not package: raise ValueError('Missing APK package metadata')
        manifest={'package':package[1],'version_code':int(package[2]),'version_name':package[3]}
        for key,pattern in [('min_sdk',r"(?:minSdkVersion|sdkVersion):'(\d+)'"),('target_sdk',r"targetSdkVersion:'(\d+)'")]:
            found=re.search(pattern,badging); manifest[key]=int(found[1]) if found else 0
        report['manifest']=manifest
        check('application_id',manifest['package']=='com.vime.ime',manifest['package'])
        check('target_android_16',manifest['target_sdk']>=36,manifest['target_sdk'])
        check('minimum_android_7',manifest['min_sdk']==24,manifest['min_sdk'])
        check('version_name',version is None or manifest['version_name']==version,manifest['version_name'])
        check('version_code',code is None or manifest['version_code']==code,manifest['version_code'])
        check('not_debuggable','application-debuggable' not in badging)
        permissions=set(re.findall(r"uses-permission: name='([^']+)'",badging))
        allowed=REQUIRED_PERMISSIONS | {'com.vime.ime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
        check('permission_set',REQUIRED_PERMISSIONS<=permissions and permissions<=allowed,sorted(permissions))
        rc,xml=run([aapt,'dump','xmltree',staged,'--file','AndroidManifest.xml'])
        if rc: raise RuntimeError('Cannot inspect compiled manifest')
        check('no_test_instrumentation','E: instrumentation' not in xml)
        check('not_test_only',not any('testOnly' in line and '=true' in line.replace(' ','') for line in xml.splitlines()))
        check('backup_disabled',any('allowBackup' in line and '=false' in line.replace(' ','') for line in xml.splitlines()))
        # Release resource optimization renames packaged XML paths. Resolve the
        # manifest's resource ID through resources.arsc instead of assuming a path.
        network_id=re.search(r'networkSecurityConfig\([^)]*\)=@(0x[0-9a-fA-F]+)',xml)
        rc,resources=run([aapt,'dump','resources',staged])
        blocks=re.split(r'(?m)^\s*resource ',resources) if rc==0 else []
        network_block=next((b for b in blocks if network_id and b.startswith(network_id[1]+' ')), '')
        network_paths=re.findall(r'\(file\)\s+(\S+)\s+type=XML',network_block)
        report['network_security_config_paths']=network_paths
        secure=bool(network_paths)
        for path in network_paths:
            rc,network=run([aapt,'dump','xmltree',staged,'--file',path])
            compact=network.replace(' ','')
            secure=secure and rc==0 and 'base-config' in network and 'cleartextTrafficPermitted=false' in compact and 'cleartextTrafficPermitted=true' not in compact
        check('https_only_network_config',secure)
        rc,signature=run([signer,'verify','--verbose','--print-certs',staged])
        fingerprints=re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]+)',signature)
        report['certificate_sha256']=fingerprints
        check('signature_verified',rc==0)
        check('expected_certificate',len(fingerprints)==1 and fingerprints[0].lower()==certificate)
        check('not_preview_certificate',bool(fingerprints) and all(f.lower()!=PREVIEW_CERT for f in fingerprints) and 'CN=Android Debug' not in signature)
        check('v2_signature','Verified using v2 scheme (APK Signature Scheme v2): true' in signature)
        rc,_=run([aligner,'-c','-P','16','4',staged]); check('sdk_zipalign_16k',rc==0)
    with apk.open('rb') as source,zipfile.ZipFile(apk) as archive:
        names=archive.namelist(); check('no_duplicate_zip_entries',len(names)==len(set(names)))
        for entry in archive.infolist():
            if not entry.filename.startswith('lib/') or not entry.filename.endswith('.so'): continue
            offset=zip_data_offset(source,entry)
            data=archive.read(entry)
            segments=elf_loads(data)
            # Android's 16 KB ELF requirement covers arm64-v8a/x86_64. The
            # separate armeabi-v7a package retains the 4 KB legacy ABI.
            native_abi=entry.filename.split('/')[1]
            required_page=4096 if native_abi=='armeabi-v7a' else 16384
            alignment_ok=all(s['alignment']>=required_page and s['alignment'] & (s['alignment']-1)==0 and s['offset']%required_page==s['address']%required_page for s in segments)
            result={'name':entry.filename,'zip_offset':offset,'uncompressed':entry.compress_type==zipfile.ZIP_STORED,
                'zip_16k':offset%16384==0,'load_segments':segments,'required_elf_page_bytes':required_page,
                'elf_alignment_ok':alignment_ok,'elf_class':data[4]}
            report['native'].append(result)
        abi=set(re.findall(r"native-code: (.+)",badging)[0].replace("'",'').split()) if 'native-code:' in badging else set()
        report['abis']=sorted(abi)
        check('single_supported_abi',len(abi)==1 and abi<=set(('arm64-v8a','armeabi-v7a','x86_64')),sorted(abi))
        expected={f'lib/{a}/{name}' for a in abi for name in ('libc++_shared.so','libvime_rime.so')}
        check('rime_libraries_present',expected<={n['name'] for n in report['native']})
        check('all_native_uncompressed',bool(report['native']) and all(n['uncompressed'] for n in report['native']))
        check('all_native_zip_16k',bool(report['native']) and all(n['zip_16k'] for n in report['native']))
        check('native_elf_alignment',bool(report['native']) and all(n['elf_alignment_ok'] for n in report['native']))
        check('native_elf_class_matches_abi',bool(report['native']) and all(n['elf_class']==(1 if n['name'].split('/')[1]=='armeabi-v7a' else 2) for n in report['native']))
        check('no_embedded_private_signing_files',not any(n.lower().endswith(('.jks','.keystore','.p12','.pfx','.pk8')) for n in names))
        check('apk_within_32_mib',apk.stat().st_size<=32*1024*1024)
    report['passed']=all(c['pass'] for c in report['checks'])
    report['failed_checks']=[c['name'] for c in report['checks'] if not c['pass']]
    return report

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk',type=Path); p.add_argument('--sdk',type=Path,required=True)
    p.add_argument('--build-tools',default='36.0.0'); p.add_argument('--certificate-sha256',required=True)
    p.add_argument('--version-name'); p.add_argument('--version-code',type=int); p.add_argument('--report',type=Path)
    a=p.parse_args()
    try: result=verify(a.apk,a.sdk,a.build_tools,a.certificate_sha256,a.version_name,a.version_code)
    except Exception as e: result={'passed':False,'error':str(e)}
    if a.report: a.report.write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({'passed':result['passed'],'failed_checks':result.get('failed_checks',[]),'error':result.get('error')}))
    raise SystemExit(0 if result['passed'] else 1)

if __name__=='__main__': main()
