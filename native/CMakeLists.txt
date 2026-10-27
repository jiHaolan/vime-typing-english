cmake_minimum_required(VERSION 3.22)
project(vime_rime LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
add_library(vime_rime SHARED rime_bridge.cpp)
target_include_directories(vime_rime PRIVATE include "${RIME_PREBUILT}/librime/${ANDROID_ABI}/include")
set(PRE "${RIME_PREBUILT}")
target_link_libraries(vime_rime PRIVATE
    -Wl,--whole-archive "${PRE}/librime/${ANDROID_ABI}/lib/librime.a" -Wl,--no-whole-archive
    -Wl,--start-group
    "${PRE}/glog/${ANDROID_ABI}/lib/libglog.a"
    "${PRE}/leveldb/${ANDROID_ABI}/lib/libleveldb.a"
    "${PRE}/lua/${ANDROID_ABI}/lib/liblua_static.a"
    "${PRE}/marisa/${ANDROID_ABI}/lib/libmarisa.a"
    "${PRE}/opencc/${ANDROID_ABI}/lib/libopencc.a"
    "${PRE}/yaml-cpp/${ANDROID_ABI}/lib/libyaml-cpp.a"
    -Wl,--end-group log android)
target_link_options(vime_rime PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--build-id=sha1")
