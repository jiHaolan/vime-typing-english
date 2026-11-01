"""Bundle pinned public Git histories and the exact shipped offline JNI recipe."""
from pathlib import Path
import argparse,hashlib,json,subprocess,zipfile

ROOT=Path(__file__).resolve().parents[1]
def sha(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def git(path,*args):return subprocess.check_output(['git','-C',str(path),*args],stderr=subprocess.STDOUT,text=True,encoding='utf-8').strip()

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--source',required=True,type=Path)
 parser.add_argument('--output',required=True,type=Path)
 args=parser.parse_args();source=args.source.resolve();output=args.output.resolve()
 assert not output.exists(),'Preserve prior source archives'
 metadata=ROOT/'app/src/main/assets/offline-native-sources.json';build=json.loads(metadata.read_text('utf-8'))
 assert build['format']=='vime-offline-jni-build-1' and not build['upstream_sources_changed']
 stage=output.with_suffix('.work');assert not stage.exists();stage.mkdir(parents=True)
 upstream=stage/'upstream';upstream.mkdir()
 pins={'CrispASR':(source,build['source_commit']),'ggml':(source/'ggml',build['ggml_commit'])}
 payload={}
 def add(name,path,expected=None):
  assert not name.startswith('/') and '..' not in Path(name).parts and name not in payload
  assert path.is_file()
  if expected:assert sha(path)==expected,('Input changed',name)
  payload[name]=path
 for name,(repo,pin) in pins.items():
  assert git(repo,'rev-parse','HEAD')==pin and not git(repo,'diff','--name-only','HEAD')
  bundle=upstream/(name+'.bundle')
  git(repo,'bundle','create',str(bundle),'HEAD')
  git(repo,'bundle','verify',str(bundle))
  assert git(repo,'bundle','list-heads',str(bundle)).split()[0]==pin
  add('upstream/'+bundle.name,bundle)
 for name in ['CMakeLists.txt','jni.cpp','xasr_adapter.cpp']:
  add('tools/offline-asr-android/'+name,ROOT/'tools/offline-asr-android'/name,build['recipe'][name])
 add('tools/build_offline_voice.py',ROOT/'tools/build_offline_voice.py',build['recipe']['build_offline_voice.py'])
 add('tools/offline-asr-android/README.md',ROOT/'tools/offline-asr-android/README.md')
 add('tools/package_offline_voice_sources.py',Path(__file__))
 add('app/src/main/assets/offline-native-sources.json',metadata)
 for row in build['abis'].values():
  add('current-binaries/'+row['file'],ROOT/'app/src/main'/row['file'],row['sha256'])
 for name in ['crispasr-MIT.txt','ggml-MIT.txt','offline-voice-NOTICE.txt','Apache-2.0.txt','libcxx-LICENSE.txt','ndk-llvm-full-NOTICE.txt']:
  add('notices/'+name,ROOT/'app/src/main/assets/licenses'/name)
 readme=('''# Jianyi 0.26.0 offline speech source/rebuild kit

This kit contains public upstream Git bundles, the unchanged pinned sources,
the exact JNI recipe and current binaries. No separately distributed X-ASR
weights, Jianyi user recordings, learning data, credentials, machine-specific
Git configuration or signing keys are included. Public upstream histories may
contain their own example/test assets, with their original notices retained.
Full upstream per-file licences and third-party notices remain in the bundles.
The app's other native engine/dictionaries retain their separate source kits.

With Git installed, from the extracted kit (no network needed):

git clone --no-checkout upstream/CrispASR.bundle CrispASR
git -C CrispASR checkout --detach SOURCE_PIN
git clone --no-checkout upstream/ggml.bundle CrispASR/ggml
git -C CrispASR/ggml checkout --detach GGML_PIN

Other unused submodules need not be initialized. Install Android NDK
28.0.13004108, CMake3.31.6 and Ninja before the following offline build.
Use Python3.12 and an output directory outside this extracted kit and source:

python tools/build_offline_voice.py --sdk <SDK> --source <CrispASR> --output <NEW-OUTPUT>

Only ARM64 and x86_64 are built. The current recipe uses CPU, one decoding
thread, private static C++ runtime, hidden symbols, MinSizeRel/Oz and LTO.
The existing Rime shared runtime is not replaced. Build IDs can depend on paths;
neither this archive nor a successful hash check proves byte-reproducible builds.

The optional model is distributed separately under Apache-2.0. Its pinned
source/revision/size/SHA256 are in notices/offline-voice-NOTICE.txt.
Fixed synthetic PCM, simulator checks and build receipts do not qualify cloud
ARM64 microphone recognition or commercial/store readiness.
'''.replace('SOURCE_PIN',build['source_commit']).replace('GGML_PIN',build['ggml_commit'])).encode('utf-8')
 manifest={'format':'jianyi-offline-source-kit-1','version':'0.26.0','source_commit':build['source_commit'],'ggml_commit':build['ggml_commit'],'files':{name:{'bytes':path.stat().st_size,'sha256':sha(path)} for name,path in sorted(payload.items())}}
 manifest['files']['README.md']={'bytes':len(readme),'sha256':hashlib.sha256(readme).hexdigest()}
 with zipfile.ZipFile(output,'x') as z:
  for name,path in sorted(payload.items()):
   info=zipfile.ZipInfo(name,(2000,1,1,0,0,0));info.compress_type=zipfile.ZIP_STORED if name.endswith('.bundle') else zipfile.ZIP_DEFLATED
   z.writestr(info,path.read_bytes(),compresslevel=9)
  for name,raw in [('README.md',readme),('MANIFEST.json',json.dumps(manifest,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode('utf-8'))]:
   info=zipfile.ZipInfo(name,(2000,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,raw,compresslevel=9)
 result={'file':output.name,'bytes':output.stat().st_size,'sha256':sha(output),'payload_files':len(manifest['files']),'source_commit':build['source_commit'],'ggml_commit':build['ggml_commit']}
 output.with_suffix('.receipt.json').write_text(json.dumps(result,indent=2)+'\n','utf-8');print(json.dumps(result),flush=True)
if __name__=='__main__':main()
