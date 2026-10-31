"""Link all source-built dependencies and run public fixtures on our16KB emulator.

No APK installation, app-data access, IME setting changes or physical devices.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import uuid

from build_native_engine import sha
from verify_native_dependencies import verify


def run(args):
    if args.device != "emulator-5580" or args.output.exists():
        raise ValueError("Use the owned16KB emulator and a fresh evidence directory")
    root = Path(__file__).resolve().parents[1]
    verified = verify(args.build, args.lock, args.sdk)
    adb = args.sdk / "platform-tools/adb.exe"
    base = [str(adb), "-s", args.device]

    def device(*values):
        return subprocess.check_output(base + list(values), timeout=45).decode("utf-8", errors="strict").strip()

    if (device("get-state") != "device" or device("shell", "getprop", "ro.kernel.qemu") != "1" or
            device("shell", "getprop", "ro.product.cpu.abi") != "x86_64" or
            device("shell", "getconf", "PAGE_SIZE") != "16384"):
        raise ValueError("A real16KB x86_64 emulator is required")
    args.output.mkdir(parents=True)
    (args.output / "tmp").mkdir()
    environment = os.environ | {"TEMP": str(args.output / "tmp"), "TMP": str(args.output / "tmp")}
    ndk = args.sdk / "ndk/28.0.13004108"
    cmake = args.sdk / "cmake/3.31.6/bin/cmake.exe"
    ninja = cmake.with_name("ninja.exe")
    compiler_output = args.output / "build"
    receipt = {"passed": False, "device": args.device, "abi": "x86_64",
               "physical_phone_used": False, "app_data_accessed": False, "app_installed": False,
               "dependency_build_verification": verified,
               "fingerprint": device("shell", "getprop", "ro.build.fingerprint"),
               "remote_temporary_files_removed": False}
    remote = "/data/local/tmp/vime-dependency-probe-" + uuid.uuid4().hex
    remote_created = False

    def host(command, log):
        with log.open("wb") as stream:
            status = subprocess.run([str(v) for v in command], cwd=args.output, env=environment,
                                    stdout=stream, stderr=subprocess.STDOUT)
        if status.returncode:
            raise RuntimeError("Probe build failed; inspect " + str(log))

    try:
        host([cmake, "-S", root / "tools/native-dependency-probe", "-B", compiler_output, "-G", "Ninja",
              "-DCMAKE_MAKE_PROGRAM=" + ninja.as_posix(),
              "-DCMAKE_TOOLCHAIN_FILE=" + (ndk / "build/cmake/android.toolchain.cmake").as_posix(),
              "-DANDROID_ABI=x86_64", "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_shared",
              "-DCMAKE_BUILD_TYPE=Release", "-DVIME_DEPENDENCY_BUILD=" + args.build.resolve().as_posix()],
             args.output / "configure.log")
        host([cmake, "--build", compiler_output, "-j", "4"], args.output / "build.log")
        executable = compiler_output / "vime_dependency_probe"
        runtime = ndk / "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/x86_64-linux-android/libc++_shared.so"
        link_map = (compiler_output / "link.map").read_text("utf-8")
        for key, info in json.loads((args.build / "receipt.json").read_bytes())["builds"].items():
            if key.endswith("/x86_64") and info["archive"] not in link_map.replace("\\", "/"):
                raise ValueError("Source-built dependency missing from actual link map: " + key)
        receipt.update(executable_sha256=sha(executable), runtime_sha256=sha(runtime),
                       all6_source_archives_in_actual_link_map=True)
        device("shell", "mkdir", "-m", "700", remote)
        remote_created = True
        for path in (executable, runtime):
            device("push", str(path), remote + "/" + path.name)
            if device("shell", "sha256sum", remote + "/" + path.name).split()[0] != sha(path):
                raise ValueError("Probe transport checksum differs")
        device("shell", "chmod", "700", remote + "/vime_dependency_probe")
        command = "LD_LIBRARY_PATH=" + shlex.quote(remote) + " " + shlex.quote(remote + "/vime_dependency_probe")
        command += " " + shlex.quote(remote) + " 16384"
        process = subprocess.run(base + ["shell", command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 timeout=90)
        text = process.stdout.decode("utf-8")
        (args.output / "probe.log").write_text(text, "utf-8")
        match = re.search(r"^VIME_PROBE_RESULT=(\{.*\})\s*$", text, re.M)
        if not match:
            raise ValueError("No actual native probe result")
        result = json.loads(match[1])
        receipt["result"] = result
        receipt["passed"] = (process.returncode == 0 and result["passed"] == result["expected"] == 7 and
                             result["failed"] == 0 and result["process_page_size"] == 16384)
        if not receipt["passed"]:
            raise ValueError("One or more native dependency runtime cases failed")
    except Exception as error:
        receipt["error"] = str(error)
    finally:
        if remote_created:
            if not re.fullmatch(r"/data/local/tmp/vime-dependency-probe-[a-f0-9]{32}", remote):
                raise ValueError("Unexpected owned probe directory")
            try:
                device("shell", "rm", "-rf", "--", remote)
                receipt["remote_temporary_files_removed"] = device("shell", "test ! -e " + shlex.quote(remote)) == ""
            except Exception as error:
                receipt.update(passed=False, cleanup_error=str(error))
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", "utf-8")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("build", "lock", "sdk", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--device", required=True)
    result = run(parser.parse_args())
    print(json.dumps({k: v for k, v in result.items() if k != "dependency_build_verification"}))
    raise SystemExit(0 if result["passed"] else 1)
