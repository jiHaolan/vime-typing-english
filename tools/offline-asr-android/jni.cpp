#include <jni.h>
#include "xasr.h"
#include "ggml.h"
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

extern "C" void vime_xasr_set_abort(xasr_context *, ggml_abort_callback, void *);
namespace {
struct Job { std::atomic<bool> cancelled{false}, started{false}; };
std::mutex registry_mutex, inference_mutex;
std::unordered_map<jlong,std::shared_ptr<Job>> jobs;
std::atomic<jlong> next_job{1};
std::shared_ptr<Job> lookup(jlong id) {
 std::lock_guard<std::mutex> lock(registry_mutex);
 auto it=jobs.find(id);return it==jobs.end()?nullptr:it->second;
}
void fail(JNIEnv *env) {
 if(!env->ExceptionCheck())env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"离线语音未完成，请重试");
}
bool aborted(void *data) { auto *job=static_cast<Job*>(data);job->started.store(true,std::memory_order_relaxed);return job->cancelled.load(std::memory_order_relaxed); }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_vime_ime_voice_NativeOfflineSpeech_nativeCreate(JNIEnv *env,jobject) {
 try { auto id=next_job.fetch_add(1);auto job=std::make_shared<Job>();std::lock_guard<std::mutex> lock(registry_mutex);jobs.emplace(id,job);return id; }
 catch(...) { fail(env);return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_vime_ime_voice_NativeOfflineSpeech_nativeCancel(JNIEnv *,jobject,jlong id) {
 if(auto job=lookup(id))job->cancelled.store(true,std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL Java_com_vime_ime_voice_NativeOfflineSpeech_nativeDestroy(JNIEnv *,jobject,jlong id) {
 std::lock_guard<std::mutex> lock(registry_mutex);jobs.erase(id);
}
extern "C" JNIEXPORT jint JNICALL Java_com_vime_ime_voice_NativeOfflineSpeech_nativeActiveJobs(JNIEnv *,jobject) {
 std::lock_guard<std::mutex> lock(registry_mutex);return static_cast<jint>(jobs.size());
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_vime_ime_voice_NativeOfflineSpeech_nativeStarted(JNIEnv *,jobject,jlong id) {
 auto job=lookup(id);return job && job->started.load(std::memory_order_relaxed)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_vime_ime_voice_NativeOfflineSpeech_nativeTranscribe(JNIEnv *env,jobject,jlong id,jstring path,jshortArray input) {
 try {
  auto job=lookup(id);if(!job || job->cancelled.load())return nullptr;
  std::lock_guard<std::mutex> inference_lock(inference_mutex);
  if(job->cancelled.load())return nullptr;
  if(!path || !input){fail(env);return nullptr;}
  const jsize count=env->GetArrayLength(input);
  if(count<=0 || count>16000*30){fail(env);return nullptr;}
  std::vector<jshort> pcm(count);env->GetShortArrayRegion(input,0,count,pcm.data());
  if(env->ExceptionCheck())return nullptr;
  std::vector<float> audio(count);for(jsize i=0;i<count;++i)audio[i]=pcm[i]/32768.f;
  const char *file=env->GetStringUTFChars(path,nullptr);if(!file)return nullptr;
  auto params=xasr_context_default_params();params.use_gpu=false;params.n_threads=1;params.verbosity=0;
  xasr_context *raw=nullptr;
  try { raw=xasr_init_from_file(file,params); }
  catch(...) { env->ReleaseStringUTFChars(path,file);throw; }
  env->ReleaseStringUTFChars(path,file);
  std::unique_ptr<xasr_context,decltype(&xasr_free)> context(raw,xasr_free);
  if(!context){fail(env);return nullptr;}
  if(job->cancelled.load())return nullptr;
  vime_xasr_set_abort(context.get(),aborted,job.get());
  std::unique_ptr<char,decltype(&std::free)> text(xasr_transcribe(context.get(),audio.data(),count),std::free);
  if(job->cancelled.load())return nullptr;
  if(!text){fail(env);return nullptr;}
  const size_t bytes=std::strlen(text.get());if(bytes>65536){fail(env);return nullptr;}
  auto result=env->NewByteArray(static_cast<jsize>(bytes));
  if(result)env->SetByteArrayRegion(result,0,static_cast<jsize>(bytes),reinterpret_cast<const jbyte*>(text.get()));
  return result;
 } catch(...) { fail(env);return nullptr; }
}
