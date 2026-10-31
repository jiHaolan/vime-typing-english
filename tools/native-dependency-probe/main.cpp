// Public synthetic fixtures only; standalone probe never opens app data.
#include <glog/logging.h>
#include <leveldb/db.h>
#include <leveldb/write_batch.h>
#include <lua.hpp>
#include <marisa.h>
#include <opencc/DictConverter.hpp>
#include <opencc/SimpleConverter.hpp>
#include <yaml-cpp/yaml.h>
#include <unistd.h>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

void require(bool ok, const char* reason) {
  if (!ok) throw std::runtime_error(reason);
}
void write(const std::string& path, const std::string& data) {
  std::ofstream stream(path, std::ios::binary);
  stream << data;
  require(stream.good(), "fixture write failed");
}
int main(int argc, char** argv) {
  if (argc != 3) return 2;
  const std::string root(argv[1]);
  const long expected_page = std::stol(argv[2]);
  if (root.find("/data/local/tmp/vime-dependency-probe-") != 0 || expected_page != 16384) return 2;
  int passed = 0;
  std::vector<std::pair<std::string, bool>> cases;
  auto test = [&](const std::string& name, const std::function<void()>& action) {
    try { action(); cases.emplace_back(name, true); ++passed; }
    catch (const std::exception& e) { cases.emplace_back(name, false); std::cerr << name << ": " << e.what() << '\n'; }
  };
  test("actual-process-page-size", [&] { require(sysconf(_SC_PAGESIZE) == expected_page, "unexpected page size"); });
  test("glog-initialize-log-shutdown", [&] {
    FLAGS_logtostderr = true;
    google::InitGoogleLogging(argv[0]);
    require(google::IsGoogleLoggingInitialized(), "glog not initialized");
    LOG(INFO) << "Vime public dependency fixture";
    google::ShutdownGoogleLogging();
    require(!google::IsGoogleLoggingInitialized(), "glog not shut down");
  });
  test("leveldb-sync-batch-close-reopen", [&] {
    leveldb::Options options; options.create_if_missing = true; options.paranoid_checks = true;
    leveldb::DB* raw = nullptr;
    require(leveldb::DB::Open(options, root + "/fixture-db", &raw).ok(), "create DB failed");
    std::unique_ptr<leveldb::DB> db(raw);
    leveldb::WriteOptions sync; sync.sync = true;
    leveldb::WriteBatch batch; batch.Put("fixture/hello", "你好 Hello"); batch.Put("fixture/delete", "temporary");
    require(db->Write(sync, &batch).ok(), "sync batch failed");
    require(db->Delete(sync, "fixture/delete").ok(), "delete failed");
    db.reset(); raw = nullptr; options.create_if_missing = false;
    require(leveldb::DB::Open(options, root + "/fixture-db", &raw).ok(), "reopen DB failed");
    db.reset(raw);
    std::string value; leveldb::ReadOptions read; read.verify_checksums = true;
    require(db->Get(read, "fixture/hello", &value).ok() && value == "你好 Hello", "persisted value differs");
    require(db->Get(read, "fixture/delete", &value).IsNotFound(), "deleted value returned");
  });
  test("lua-utf8-standard-libraries", [&] {
    std::unique_ptr<lua_State, decltype(&lua_close)> state(luaL_newstate(), lua_close);
    require(state != nullptr, "new Lua state failed");
    luaL_openlibs(state.get());
    require(luaL_dostring(state.get(), "return ('hello'):upper(),utf8.len('你好'),math.sqrt(81)") == LUA_OK, "Lua script failed");
    require(std::string(lua_tostring(state.get(), -3)) == "HELLO" &&
            lua_tointeger(state.get(), -2) == 2 && lua_tonumber(state.get(), -1) == 9, "Lua results differ");
  });
  test("marisa-prefix-save-mmap", [&] {
    marisa::Keyset keys; keys.push_back("hello"); keys.push_back("help"); keys.push_back("你好");
    marisa::Trie trie; trie.build(keys);
    const auto path = root + "/fixture.marisa"; trie.save(path.c_str());
    marisa::Trie mapped; mapped.mmap(path.c_str());
    marisa::Agent agent; agent.set_query("hel");
    size_t count = 0; while (mapped.predictive_search(agent)) ++count;
    require(count == 2, "prefix results differ");
    agent.set_query("你好"); require(mapped.lookup(agent), "UTF8 exact key missing");
  });
  test("opencc-text-binary-conversion", [&] {
    write(root + "/fixture.txt", "汉\t漢\n语\t語\n");
    opencc::ConvertDictionary(root + "/fixture.txt", root + "/fixture.ocd2", "text", "ocd2");
    write(root + "/fixture.json", R"({"name":"public fixture","segmentation":{"type":"mmseg","dict":{"type":"ocd2","file":"fixture.ocd2"}},"conversion_chain":[{"dict":{"type":"ocd2","file":"fixture.ocd2"}}]})");
    opencc::SimpleConverter converter(root + "/fixture.json", {root});
    require(converter.Convert(std::string("汉语 Hello")) == "漢語 Hello", "OpenCC binary conversion differs");
  });
  test("yaml-unicode-typed-and-malformed-input", [&] {
    auto node = YAML::Load("word: 你好\nweights: [3, 7]\nenabled: true\n");
    require(node["word"].as<std::string>() == "你好" && node["weights"][1].as<int>() == 7 &&
            node["enabled"].as<bool>(), "YAML typed values differ");
    bool rejected = false;
    try { YAML::Load("items: [broken"); } catch (const YAML::ParserException&) { rejected = true; }
    require(rejected, "malformed YAML accepted");
  });
  std::cout << "VIME_PROBE_RESULT={\"expected\":7,\"passed\":" << passed << ",\"failed\":" << 7-passed
            << ",\"process_page_size\":" << sysconf(_SC_PAGESIZE) << ",\"cases\":[";
  for (size_t i = 0; i < cases.size(); ++i) {
    if (i) std::cout << ',';
    std::cout << "{\"name\":\"" << cases[i].first << "\",\"pass\":" << (cases[i].second ? "true" : "false") << '}';
  }
  std::cout << "]}" << std::endl;
  return passed == 7 ? 0 : 1;
}
