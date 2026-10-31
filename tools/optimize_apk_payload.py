"""Recompress a built APK without changing payload bytes; output MUST be aligned/signed.

Only ZIP-deflated entries are recompressed. Stored native libraries/resources
remain stored. Signature entries are removed because any ZIP rewrite invalidates
APK signatures. This tool never invents, loads or chooses a signing identity.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path,PurePosixPath
import re
import tempfile
import zipfile


def optimize(source,output):
    source=source.resolve();output=output.resolve()
    if output.exists() or source==output:raise ValueError("Use a fresh unsigned output")
    signature=re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))",re.I)
    expected={};removed=[]
    with zipfile.ZipFile(source) as archive:
        entries=archive.infolist()
        if len({e.filename for e in entries})!=len(entries):raise ValueError("Duplicate APK entries")
        if sum(e.file_size for e in entries)>512*1024**2:raise ValueError("APK expanded size exceeds bound")
        output.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(output,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=9) as dest:
            for entry in entries:
                name=entry.filename;path=PurePosixPath(name)
                if path.is_absolute() or '..' in path.parts or '\\' in name or entry.flag_bits&1:raise ValueError("Unsafe APK entry")
                if signature.fullmatch(name):removed.append(name);continue
                if entry.compress_type not in (zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED):raise ValueError("Unsupported compression")
                data=archive.read(entry);expected[name]=hashlib.sha256(data).hexdigest()
                info=zipfile.ZipInfo(name,date_time=entry.date_time);info.compress_type=entry.compress_type
                info.external_attr=entry.external_attr;info.create_system=entry.create_system
                dest.writestr(info,data,compresslevel=9)
    with zipfile.ZipFile(output) as archive:
        if set(archive.namelist())!=set(expected):raise ValueError("APK payload set differs")
        for name,expected_hash in expected.items():
            if hashlib.sha256(archive.read(name)).hexdigest()!=expected_hash:raise ValueError("APK payload changed")
    return {'passed':True,'payload_files':len(expected),'all_payload_bytes_unchanged':True,
            'signature_entries_removed':removed,'input_bytes':source.stat().st_size,'unsigned_output_bytes':output.stat().st_size,
            'saved_bytes_before_alignment_and_signing':source.stat().st_size-output.stat().st_size,
            'unsigned':True,'requires_zipalign_and_sign':True}


def sign_optimized(source,output,sdk,java_home,store,alias,run,
                   store_password_env='VIME_RELEASE_STORE_PASSWORD',key_password_env='VIME_RELEASE_KEY_PASSWORD'):
    """Use the caller's existing identity; no key creation and no password arguments."""
    output=output.resolve()
    if output.exists():raise ValueError('Signed destination already exists')
    tools=sdk/'build-tools/36.0.0'
    work=output.parent/'optimizer-work';work.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='candidate-',dir=work) as folder:
        stage=Path(folder).resolve()
        if not stage.is_relative_to(work.resolve()):raise ValueError('Unexpected optimizer staging path')
        unsigned=stage/'unsigned.apk';aligned=stage/'aligned.apk'
        result=optimize(source,unsigned)
        run([tools/('zipalign.exe' if os.name=='nt' else 'zipalign'),'-f','-P','16','4',unsigned,aligned])
        # Running the JAR directly avoids cmd.exe interpretation of key aliases
        # or keystore paths. Password values remain solely in the environment.
        run([java_home/'bin'/('java.exe' if os.name=='nt' else 'java'),'-jar',tools/'lib/apksigner.jar',
             'sign','--ks',store,'--ks-key-alias',alias,'--ks-pass','env:'+store_password_env,
             '--key-pass','env:'+key_password_env,'--out',output,aligned])
    result.update(unsigned=False,requires_zipalign_and_sign=False,aligned_and_resigned=True,signed_bytes=output.stat().st_size)
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args();result=optimize(args.source,args.output)
    args.report.write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result),flush=True)
