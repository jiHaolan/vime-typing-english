#include <jni.h>
#include <rime_api.h>
#include <string>
#include <list>
#include <vector>
#include <memory>
#include <algorithm>
#include <locale>
#include <codecvt>
#include <fstream>
// Only the ABI-stable Candidate/Menu declarations are required. These original
// BSD headers are pinned to the exact librime source used by the static provider.
#define RIME_COMMON_H_
namespace rime {
using std::string; using std::list; using std::vector;
template<class T> using an = std::shared_ptr<T>;
template<class T> using of = std::shared_ptr<T>;
}
#include <rime/menu.h>

static RimeApi* api = nullptr;
static RimeSessionId session = 0;
static std::string shared_dir, user_dir;
static bool nine_mode = false;
static std::string utf(JNIEnv* e, jstring s) {
    if (!s) return {};
    const jchar* p = e->GetStringChars(s, nullptr);
    std::u16string value(reinterpret_cast<const char16_t*>(p), e->GetStringLength(s));
    e->ReleaseStringChars(s,p);
    return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>().to_bytes(value);
}
static jstring java_string(JNIEnv* e, const std::string& value) {
    auto s = std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>().from_bytes(value);
    return e->NewString(reinterpret_cast<const jchar*>(s.data()),s.size());
}
static void fail(JNIEnv* e, const char* s) {
    e->ThrowNew(e->FindClass("java/lang/IllegalStateException"), s);
}
static bool prepare(const std::string& raw) {
    if (!api || !session) return false;
    bool nine = !raw.empty() && std::all_of(raw.begin(), raw.end(), [](char c) { return c >= '2' && c <= '9'; });
    if (nine != nine_mode) {
        if (!api->select_schema(session, nine ? "vime_nine" : "vime_pinyin")) return false;
        nine_mode = nine;
    }
    api->clear_composition(session);
    api->set_option(session, "incognito_mode", true);
    return api->set_input(session, raw.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_vime_ime_engine_RimeNative_initialize(JNIEnv* e, jobject, jstring shared, jstring user) {
    if (!api) {
        api = rime_get_api(); shared_dir = utf(e, shared); user_dir = utf(e, user);
        RIME_STRUCT(RimeTraits, traits);
        traits.shared_data_dir = shared_dir.c_str(); traits.user_data_dir = user_dir.c_str();
        traits.distribution_name = "Vime"; traits.distribution_code_name = "vime";
        traits.distribution_version = "0.14"; traits.app_name = "rime.vime";
        traits.min_log_level = 3; traits.log_dir = "";
        api->setup(&traits); api->initialize(&traits);
        bool compiled = std::ifstream(shared_dir + "/build/vime_pinyin.table.bin").good() &&
            std::ifstream(shared_dir + "/build/vime_nine.prism.bin").good();
        if (!compiled && api->start_maintenance(false)) api->join_maintenance_thread();
        session = api->create_session();
        if (!session || !api->select_schema(session, "vime_pinyin")) {
            if (session) api->destroy_session(session);
            session = 0; api->finalize(); api = nullptr;
            fail(e, "Rime schema deployment failed"); return nullptr;
        }
        RimeCandidateListIterator probe{};
        bool ready = prepare("nihao") && api->candidate_list_from_index(session, &probe, 0);
        if (ready) { ready = api->candidate_list_next(&probe); api->candidate_list_end(&probe); }
        api->clear_composition(session);
        if (!ready) {
            api->destroy_session(session); session = 0; api->finalize(); api = nullptr;
            fail(e, "Rime dictionary is unavailable"); return nullptr;
        }
    }
    return e->NewStringUTF(api->get_version());
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vime_ime_engine_RimeNative_candidates(JNIEnv* e, jobject, jstring raw_value, jint offset, jint limit) {
    std::vector<std::string> rows; const auto raw = utf(e, raw_value);
    if (prepare(raw)) {
        RimeCandidateListIterator it{};
        if (api->candidate_list_from_index(session, &it, std::max(0, offset))) {
            while ((int)rows.size() < std::min(256, limit) && api->candidate_list_next(&it)) {
                auto candidate = reinterpret_cast<rime::Menu*>(it.ptr)->GetCandidateAt(it.index);
                if (!candidate) break;
                std::string word = it.candidate.text ? it.candidate.text : "";
                if (word.empty() || word.find_first_of("\t\r\n") != std::string::npos) continue;
                auto consumed = std::min(raw.size(), candidate->end());
                rows.push_back(word + "\t" + std::to_string(consumed) + "\t" + std::to_string(it.index));
            }
            api->candidate_list_end(&it);
        }
    }
    auto result = e->NewObjectArray(rows.size(), e->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < rows.size(); ++i) {
        auto value = java_string(e,rows[i]); e->SetObjectArrayElement(result, i, value); e->DeleteLocalRef(value);
    }
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_vime_ime_engine_RimeNative_learn(JNIEnv* e, jobject, jstring raw_value, jstring word_value, jint consumed) {
    auto raw = utf(e, raw_value); auto word = utf(e, word_value);
    raw = raw.substr(0, std::min(raw.size(), (size_t)std::max(0, consumed)));
    if (!prepare(raw)) return;
    RimeCandidateListIterator it{}; int chosen = -1;
    if (api->candidate_list_begin(session, &it)) {
        while (it.index < 256 && api->candidate_list_next(&it)) {
            if (it.candidate.text && word == it.candidate.text) { chosen = it.index; break; }
        }
        api->candidate_list_end(&it);
    }
    if (chosen >= 0) {
        api->set_option(session, "incognito_mode", false);
        api->select_candidate(session, chosen);
        api->commit_composition(session);
        RIME_STRUCT(RimeCommit, commit); if (api->get_commit(session, &commit)) api->free_commit(&commit);
        api->set_option(session, "incognito_mode", true);
    }
    api->clear_composition(session);
}
