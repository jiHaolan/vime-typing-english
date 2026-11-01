#include "xasr.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <sys/resource.h>
// Local engineering probe: 16 kHz mono PCM16 input; no microphone or network.
int main(int argc, char **argv) {
 std::setvbuf(stderr,nullptr,_IONBF,0);
 if(argc < 3 || argc > 4) { std::fprintf(stderr,"usage: model.gguf audio.pcm16 [threads]\n"); return 2; }
 auto begin=std::chrono::steady_clock::now();
 FILE *file=std::fopen(argv[2],"rb"); if(!file)return 3;
 std::fseek(file,0,SEEK_END);long bytes=std::ftell(file);std::rewind(file);
 if(bytes<=0 || bytes>16000*2*30 || bytes%2){std::fclose(file);return 4;}
 std::vector<short> pcm(bytes/2); if(std::fread(pcm.data(),1,bytes,file)!=(size_t)bytes){std::fclose(file);return 5;}std::fclose(file);
 std::vector<float> audio(pcm.size());for(size_t i=0;i<pcm.size();++i)audio[i]=pcm[i]/32768.f;
 auto params=xasr_context_default_params();params.use_gpu=false;params.n_threads=argc==4?std::atoi(argv[3]):4;params.verbosity=0;
 std::fprintf(stderr,"PHASE:model_load\n");
 auto *ctx=xasr_init_from_file(argv[1],params);if(!ctx)return 6;
 std::fprintf(stderr,"PHASE:inference THREADS:%d\n",params.n_threads);
 auto loaded=std::chrono::steady_clock::now();
 char *text=xasr_transcribe(ctx,audio.data(),(int)audio.size());
 std::fprintf(stderr,"PHASE:inference_done\n");
 auto end=std::chrono::steady_clock::now();struct rusage mem{};getrusage(RUSAGE_SELF,&mem);
 if(text)std::printf("TRANSCRIPT:%s\n",text);
 std::printf("LOAD_MS:%lld\nDECODE_MS:%lld\nMAX_RSS_KIB:%ld\n",(long long)std::chrono::duration_cast<std::chrono::milliseconds>(loaded-begin).count(),(long long)std::chrono::duration_cast<std::chrono::milliseconds>(end-loaded).count(),mem.ru_maxrss);
 std::free(text);xasr_free(ctx);return text?0:7;
}
