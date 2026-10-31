"""Copy authentic native notices without changing existing licence documents.

Every excerpt retains its original bytes. The receipt identifies its source
member and full-source hash; this is not an automatic legal conclusion.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def prepare(args):
    lock = json.loads(args.lock.read_bytes())
    cache = {}
    origins = {}

    def read(component, name):
        if component not in cache:
            row = next(row for row in lock["sources"] if row["id"] == component)
            archive = args.archives / (component + ".tar.gz")
            if sha(archive.read_bytes()) != row["archive_sha256"]:
                raise ValueError("Source archive checksum differs")
            with tarfile.open(archive) as source:
                cache[component] = {member.name.split("/", 1)[1]: source.extractfile(member).read()
                                    for member in source if member.isfile() and "/" in member.name}
        data = cache[component][name]
        origins[component + ":" + name] = sha(data)
        return data

    def leading_comment(data):
        if not data.startswith(b"/*") or b"*/" not in data:
            raise ValueError("Expected a complete leading copyright comment")
        return data[:data.index(b"*/") + 2]

    def lua_notice(data):
        # Compatibility files begin with a short label comment followed by
        # another comment containing the actual licence. Keep both blocks.
        end = data.find(b"#include")
        if end < 0 or b"Permission is hereby granted" not in data[:end]:
            raise ValueError("Complete Lua compatibility licence not found")
        return data[:end]

    values = {}
    for component, name in (("librime-lua", "librime-lua-BSD-3-Clause.txt"),
                            ("librime-predict", "librime-predict-BSD-3-Clause.txt"),
                            ("librime-octagram", "legacy-octagram-GPL-3.0.txt")):
        values[name] = read(component, "LICENSE")
    values["darts-clone-BSD-3-Clause.txt"] = read("librime", "include/COPYING.darts-clone")
    utf8 = read("librime", "include/utf8.h")
    values["utf8cpp-embedded-NOTICE.txt"] = utf8[:utf8.index(b"#ifndef")]
    values["x11-keysyms-NOTICE.txt"] = b"".join(
        ("Source: librime/include/X11/" + name + "\n\n").encode() + leading_comment(read("librime", "include/X11/" + name)) + b"\n\n"
        for name in ("keysym.h", "keysymdef.h"))
    values["lua-compat-NOTICE.txt"] = b"".join(
        ("Source: librime-lua/src/lib/" + name + "\n\n").encode() + lua_notice(read("librime-lua", "src/lib/" + name)) + b"\n\n"
        for name in ("lauxlib-compat.c", "lutf8lib-compat.c"))
    # The complete upstream RapidJSON notice lists additional test-only terms.
    # No jsonchecker test corpus is imported into OpenCC's bundled headers.
    rapidjson = args.rapidjson_license.read_bytes()
    if sha(rapidjson) != "a140e5d46fe734a1c78f1a3c3ef207871dd75648be71fdda8e309b23ab8b1f32":
        raise ValueError("Pinned RapidJSON notice differs")
    values["rapidjson-upstream-NOTICE.txt"] = rapidjson
    llvm = args.sdk / "ndk/28.0.13004108/toolchains/llvm/prebuilt/windows-x86_64/NOTICE"
    values["ndk-llvm-full-NOTICE.txt"] = llvm.read_bytes()
    # Preserve year/author-specific glog notices in addition to its root COPYING.
    read("glog", "COPYING")
    notices = {}
    for name, data in sorted(cache["glog"].items()):
        if name.startswith("src/") and name.endswith((".h", ".cc")) and data.startswith(b"// Copyright"):
            marker = b"// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."
            if marker in data[:6000]:
                end = data.index(marker) + len(marker)
                excerpt = data[:end]
                notices.setdefault(excerpt, []).append(name)
                origins["glog:" + name] = sha(data)
    values["glog-embedded-NOTICE.txt"] = b"\n\n".join(
        ("Source files: " + ", ".join(names) + "\n\n").encode() + excerpt for excerpt, names in notices.items())
    args.output.mkdir(parents=True, exist_ok=True)
    for name, data in values.items():
        if not 100 <= len(data) <= 256 * 1024:
            raise ValueError("Notice size exceeds reader bounds: " + name)
        destination = args.output / name
        if destination.exists() and destination.read_bytes() != data:
            raise ValueError("Never overwrite an existing different licence document: " + name)
    for name, data in values.items():
        destination = args.output / name
        destination.write_bytes(data)
    result = {"format": "vime-native-notice-receipt-1", "source_lock_sha256": sha(args.lock.read_bytes()),
              "files": {name: sha(data) for name, data in sorted(values.items())}, "source_members": origins,
              "rapidjson_notice_source": "https://github.com/Tencent/rapidjson/blob/f54b0e47a08782a6131cc3d60f94d038fa6e0a51/license.txt",
              "ndk_notice_sha256": sha(values["ndk-llvm-full-NOTICE.txt"]),
              "source_and_binary_audit_complete": False}
    args.receipt.write_text(json.dumps(result, indent=2) + "\n", "utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("lock", "archives", "rapidjson-license", "sdk", "output", "receipt"):
        parser.add_argument("--" + name, required=True, type=Path)
    result = prepare(parser.parse_args())
    print(json.dumps({"documents": len(result["files"]), "source_members": len(result["source_members"])}))
