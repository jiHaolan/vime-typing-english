"""Cross-compile the bounded native ASR probe, not an application/release APK."""
from pathlib import Path
import argparse,hashlib,json,os,re,subprocess

SOURCE_COMMIT='1dd0bf6fb9e7a8bf5849cb7a14605b2234e6ee93'
GGML_COMMIT='e2ae679a563014f46c546ce6c6448e8601e4dea6'
NDK_VERSION='28.0.13004108'

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--sdk',required=True,type=Path)
 parser.add_argument('--source',required=True,type=Path)
 parser.add_argument('--build',required=True,type=Path)
 parser.add_argument('--abi',choices=['x86_64','arm64-v8a'],required=True)
 args=parser.parse_args()
 source=args.source.resolve();build=args.build.resolve();sdk=args.sdk.resolve()
 root=Path(__file__).resolve().parent
 assert not build.is_relative_to(source) and not build.is_relative_to(root), 'Use a separate build directory'
 def git(*a,cwd=source):return subprocess.check_output(['git',*a],cwd=cwd,text=True).strip()
 assert git('rev-parse','HEAD')==SOURCE_COMMIT,'CrispASR source pin differs'
 assert git('rev-parse','HEAD',cwd=source/'ggml')==GGML_COMMIT,'GGML submodule pin differs'
 assert not git('diff','--name-only','HEAD') and not git('diff','--name-only','HEAD',cwd=source/'ggml'),'Tracked native sources changed'
 build.mkdir(parents=True,exist_ok=True);(build/'tmp').mkdir(exist_ok=True)
 env=os.environ|{'TEMP':str(build/'tmp'),'TMP':str(build/'tmp')}
 suffix='.exe' if os.name=='nt' else ''
 cmake=sdk/'cmake/3.31.6/bin'/('cmake'+suffix)
 ninja=cmake.with_name('ninja'+suffix)
 ndk=sdk/'ndk'/NDK_VERSION
 assert f'Pkg.Revision = {NDK_VERSION}' in (ndk/'source.properties').read_text()
 flags=['-DCMAKE_MAKE_PROGRAM='+ninja.as_posix(),'-DCMAKE_TOOLCHAIN_FILE='+(ndk/'build/cmake/android.toolchain.cmake').as_posix(),'-DANDROID_ABI='+args.abi,'-DANDROID_PLATFORM=android-24','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release','-DFETCHCONTENT_FULLY_DISCONNECTED=ON','-DCMAKE_EXPORT_COMPILE_COMMANDS=ON','-DCRISPASR_SOURCE='+source.as_posix()]
 commands=[[str(cmake),'-S',str(root),'-B',str(build),'-G','Ninja',*flags],[str(cmake),'--build',str(build),'--target','jianyi-xasr-probe','-j','4']]
 for command,name in zip(commands,['configure.log','build.log']):
  with (build/name).open('wb') as log:status=subprocess.run(command,cwd=build,env=env,stdout=log,stderr=subprocess.STDOUT)
  if status.returncode:raise RuntimeError(f'Build failed: {name}, exit {status.returncode}')
 executable=build/'jianyi-xasr-probe'
 host='windows-x86_64' if os.name=='nt' else 'linux-x86_64'
 readelf=ndk/'toolchains/llvm/prebuilt'/host/'bin'/('llvm-readelf'+suffix)
 headers=subprocess.check_output([str(readelf),'-l','--wide',str(executable)],text=True)
 (build/'program-headers.txt').write_text(headers)
 aligns=[int(line.split()[-1],16) for line in headers.splitlines() if re.match(r'\s*LOAD\s',line)]
 assert aligns and min(aligns)>=16384,'ELF LOAD alignment is below 16KB'
 sha=lambda p:hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
 receipt={'scope':'native build only; no APK/microphone/physical-device acceptance','abi':args.abi,'api':24,'ndk':NDK_VERSION,'crispasr_commit':SOURCE_COMMIT,'ggml_commit':GGML_COMMIT,'upstream_sources_changed':False,'probe_sources':{p.name:sha(p) for p in [root/'CMakeLists.txt',root/'probe.cpp',Path(__file__)]},'executable':{'name':executable.name,'bytes':executable.stat().st_size,'sha256':sha(executable)},'load_alignments':aligns,'commands':commands}
 (build/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n','utf-8')
 print(json.dumps({'abi':args.abi,'build_passed':True,'elf_16kb':True,'bytes':executable.stat().st_size}),flush=True)

if __name__=='__main__':main()
