// Vime verification utility. Operates only on copied emulator snapshots.
#include <leveldb/db.h>
#include <iostream>
#include <memory>

static void hex(const leveldb::Slice& value) {
    static const char digits[] = "0123456789abcdef";
    for (unsigned char c : value.ToString()) {
        std::cout.put(digits[c >> 4]);
        std::cout.put(digits[c & 15]);
    }
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    leveldb::Options options;
    options.create_if_missing = false;
    options.paranoid_checks = true;
    options.max_open_files = 32;
    leveldb::DB* handle = nullptr;
    auto status = leveldb::DB::Open(options, argv[1], &handle);
    if (!status.ok()) { std::cerr << status.ToString(); return 3; }
    std::unique_ptr<leveldb::DB> database(handle);
    leveldb::ReadOptions read;
    read.verify_checksums = true;
    std::unique_ptr<leveldb::Iterator> iterator(database->NewIterator(read));
    size_t count = 0;
    for (iterator->SeekToFirst(); iterator->Valid(); iterator->Next()) {
        if (++count > 1000000 || iterator->key().size() > 1048576 || iterator->value().size() > 1048576) return 4;
        hex(iterator->key()); std::cout.put('\t'); hex(iterator->value()); std::cout.put('\n');
    }
    if (!iterator->status().ok()) { std::cerr << iterator->status().ToString(); return 5; }
    return 0;
}
