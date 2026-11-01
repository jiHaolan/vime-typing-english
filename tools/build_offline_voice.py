"""Build the isolated CPU JNI recognizer from pinned, unchanged upstream sources."""
from pathlib import Path
import argparse,hashlib,json,os,re,subprocess

SOURCE_PIN='1dd0bf6fb9e7a8bf5849cb7a14605b2234e6ee93'
GGML_PIN='e2ae679a563014f46c546ce6c6448e8601e4dea6'
NDK_VERSION='28.0.13004108'
EXPORTS={'Java_com_vime_ime_voice_NativeOfflineSpeech_'+name for name in ['nativeCreate','nativeCancel','nativeDestroy','nativeActiveJobs','nativeStarted','nativeTranscribe']}

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--sdk',type=Path,required=True)
 parser.add_argument('--source',type=Path,required=True)
 parser.add_argument('--output',type=Path,required=True)
 parser.add_argument('--abis',nargs='+',choices=['arm64-v8a','x86_64'],default=['arm64-v8a','x86_64'])
 args=parser.parse_args()
 app=Path(__file__).resolve().parents[1];recipe=app/'tools/offline-asr-android'
 sdk=args.sdk.resolve();source=args.source.resolve();out=args.output.resolve()
 assert not out.is_relative_to(app) and not out.is_relative_to(source),'Use an isolated output directory'
 def git(*command,cwd=source):return subprocess.check_output(['git',*command],cwd=cwd,text=True).strip()
 def verify_source():
  assert git('rev-parse','HEAD')==SOURCE_PIN
  assert git('rev-parse','HEAD',cwd=source/'ggml')==GGML_PIN
  assert not git('diff','--name-only','HEAD') and not git('diff','--name-only','HEAD',cwd=source/'ggml'),'Upstream sources changed'
 verify_source();out.mkdir(parents=True,exist_ok=True);(out/'tmp').mkdir(exist_ok=True)
 sha=lambda p:hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
 inputs={p.name:sha(p) for p in [recipe/'CMakeLists.txt',recipe/'jni.cpp',recipe/'xasr_adapter.cpp',Path(__file__)]}
 ndk=sdk/'ndk'/NDK_VERSION
 assert f'Pkg.Revision = {NDK_VERSION}' in (ndk/'source.properties').read_text()
 suffix='.exe' if os.name=='nt' else ''
 host='windows-x86_64' if os.name=='nt' else 'linux-x86_64'
 tools=ndk/'toolchains/llvm/prebuilt'/host/'bin'
 cmake=sdk/'cmake/3.31.6/bin'/('cmake'+suffix);ninja=cmake.with_name('ninja'+suffix)
 strip=tools/('llvm-strip'+suffix);readelf=tools/('llvm-readelf'+suffix)
 env=os.environ|{'TEMP':str(out/'tmp'),'TMP':str(out/'tmp')}
 receipt={'format':'vime-offline-jni-build-1','source_commit':SOURCE_PIN,'ggml_commit':GGML_PIN,'upstream_sources_changed':False,'recipe':inputs,'abis':{},'not_runtime_or_commercial_acceptance':True}
 for abi in args.abis:
  build=out/'build'/abi;logs=out/'logs'/abi;logs.mkdir(parents=True,exist_ok=True)
  flags=['-DCMAKE_MAKE_PROGRAM='+ninja.as_posix(),'-DCMAKE_TOOLCHAIN_FILE='+(ndk/'build/cmake/android.toolchain.cmake').as_posix(),'-DANDROID_ABI='+abi,'-DANDROID_PLATFORM=android-24','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=MinSizeRel','-DCMAKE_C_FLAGS_MINSIZEREL=-Oz -DNDEBUG','-DCMAKE_CXX_FLAGS_MINSIZEREL=-Oz -DNDEBUG','-DCMAKE_EXPORT_COMPILE_COMMANDS=ON','-DCRISPASR_SOURCE='+source.as_posix()]
  for command,name in [([cmake,'-S',recipe,'-B',build,'-G','Ninja',*flags],'configure.log'),([cmake,'--build',build,'--target','vime_xasr','-j','4'],'build.log')]:
   with (logs/name).open('wb') as log:r=subprocess.run([str(v) for v in command],cwd=out,env=env,stdout=log,stderr=subprocess.STDOUT)
   if r.returncode:raise RuntimeError('Build failed: '+str(logs/name))
  destination=out/'jniLibs'/abi/'libvime_xasr.so';destination.parent.mkdir(parents=True,exist_ok=True)
  subprocess.run([str(strip),'--strip-all','-o',str(destination),str(build/'libvime_xasr.so')],check=True)
  def elf(*options):return subprocess.check_output([str(readelf),*options,str(destination)],text=True)
  headers=elf('-l','--wide');dynamic=elf('-d','--wide');symbols=elf('--dyn-syms','--wide');file_header=elf('-h')
  for name,text in [('headers',headers),('dynamic',dynamic),('symbols',symbols)]: (logs/(name+'.txt')).write_text(text,'utf-8')
  alignments=[int(line.split()[-1],16) for line in headers.splitlines() if re.match(r'\s*LOAD\s',line)]
  assert alignments and min(alignments)>=16384
  assert ('AArch64' if abi=='arm64-v8a' else 'X86-64') in file_header
  needed=set(re.findall(r'Shared library: \[([^]]+)\]',dynamic));assert needed<= {'libc.so','libm.so','libdl.so','liblog.so'}
  defined={parts[-1] for line in symbols.splitlines() if len(parts:=line.split())>=8 and parts[4] in ['GLOBAL','WEAK'] and parts[5]=='DEFAULT' and parts[6].isdigit()}
  assert defined==EXPORTS,('Unexpected exported symbols',defined-EXPORTS,EXPORTS-defined)
  receipt['abis'][abi]={'file':destination.relative_to(out).as_posix(),'bytes':destination.stat().st_size,'sha256':sha(destination),'load_alignments':alignments,'needed':sorted(needed),'only_expected_jni_exports':True,'compile_commands_sha256':sha(build/'compile_commands.json')}
  print(json.dumps({'abi':abi,**receipt['abis'][abi]}),flush=True)
 verify_source();assert all(sha(recipe/name)==digest for name,digest in inputs.items() if name!='build_offline_voice.py')
 (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n','utf-8')

if __name__=='__main__':main()
