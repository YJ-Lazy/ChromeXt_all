#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:?}/ndk/29.0.14206865}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
test -f "$APK"
bash zygisk/fetch-deps.sh
cmake -S zygisk/native -B zygisk/build/native -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-27 -DANDROID_STL=c++_static \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
cmake --build zygisk/build/native --target chromext cx-snapshot
rm -rf zygisk/build/module
rm -f zygisk/build/ChromeXt-Zygisk-arm64-exp.zip
mkdir -p zygisk/build/module/zygisk zygisk/build/module/payload zygisk/build/module/licenses
cp -a zygisk/module/. zygisk/build/module/
git rev-parse HEAD > zygisk/build/module/build-revision
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
echo "Checking native module ELF architecture and exported entry"
"$READELF" -h zygisk/build/native/libchromext.so > zygisk/build/zygisk-header.txt
"$READELF" --dyn-syms --wide zygisk/build/native/libchromext.so > zygisk/build/zygisk-symbols.txt
python3 - <<'PY'
from pathlib import Path
import re
header = Path("zygisk/build/zygisk-header.txt").read_text()
symbols = Path("zygisk/build/zygisk-symbols.txt").read_text()
if not re.search(r"Machine:\s*AArch64\s*$", header, re.M):
    raise SystemExit("ERROR: Native module is not AArch64")
matches = [line for line in symbols.splitlines() if line.split() and line.split()[-1] == "zygisk_module_entry"]
if not any(len(line.split()) >= 8 and line.split()[4] == "GLOBAL" and line.split()[5] == "DEFAULT" and line.split()[6].isdigit() for line in matches):
    raise SystemExit("ERROR: Exported Zygisk module entry missing: " + repr(matches))
print("Verified AArch64 Zygisk module entry:", matches[0].strip())
PY
python3 - <<'PY'
import pathlib, re
native = pathlib.Path("zygisk/native/entry.cpp").read_text()
targets = re.search(r'constexpr std::array targets\{([^\n]+)\};', native)
assert targets
packages = re.findall(r'"([A-Za-z0-9_.]+)"', targets.group(1))
assert packages and len(packages) == len(set(packages))
pathlib.Path("zygisk/build/module/supported.list").write_text("\n".join(packages) + "\n")
PY
node --check zygisk/build/module/webroot/app.js
node --check zygisk/build/module/webroot/library.js
bash zygisk/test-library.sh
cp zygisk/build/native/libchromext.so zygisk/build/module/zygisk/arm64-v8a.so
cp zygisk/build/native/cx-snapshot zygisk/build/module/cx-snapshot
chmod 755 zygisk/build/module/cx-snapshot
cp LICENSE zygisk/build/module/licenses/ChromeXt-LICENSE
for entry in lsplant dobby xdl-wrapper/external/xDL; do
  name="$(basename "$entry")"
  for f in LICENSE LICENSE.txt COPYING; do
    if [ -f "zygisk/deps/$entry/$f" ]; then cp "zygisk/deps/$entry/$f" "zygisk/build/module/licenses/$name-$f"; fi
  done
done
cp zygisk/README.md zygisk/build/module/README.md
python3 - "$APK" <<'PY'
import pathlib, re, sys, zipfile
dest = pathlib.Path("zygisk/build/module/payload")
with zipfile.ZipFile(sys.argv[1]) as z:
    assert "assets/xposed_init" not in z.namelist()
    names = [n for n in z.namelist() if re.fullmatch(r"classes(?:[2-9]|1[0-6])?\.dex", n)]
    assert "classes.dex" in names
    assert len(names) == len([n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)])
    for n in names:
        (dest / n).write_bytes(z.read(n))
PY
# APK is a build intermediate only; the resource archive is not installed.
cp "$APK" zygisk/build/module/payload/resources.bin
for script in customize.sh scope.sh library.sh diagnostics.sh service.sh; do
  sh -n "zygisk/build/module/$script"
  chmod 755 "zygisk/build/module/$script"
done
(cd zygisk/build/module && zip -qr ../ChromeXt-Zygisk-arm64-exp.zip .)
python3 - "$APK" <<'PY'
import io, pathlib, sys, zipfile
path = pathlib.Path("zygisk/build/ChromeXt-Zygisk-arm64-exp.zip")
with zipfile.ZipFile(path) as outer:
    required = {"module.prop", "customize.sh", "library.sh", "cx-snapshot", "diagnostics.sh", "service.sh", "build-revision",
                "zygisk/arm64-v8a.so", "payload/classes.dex",
                "payload/resources.bin", "supported.list", "scope.sh",
                "webroot/index.html", "webroot/style.css", "webroot/app.js", "webroot/library.js"}
    assert required <= set(outer.namelist()), required - set(outer.namelist())
    assert not any(n.endswith(".apk") or n.endswith("install-manager.sh") for n in outer.namelist())
    embedded = outer.read("payload/resources.bin")
    assert embedded == pathlib.Path(sys.argv[1]).read_bytes()
    with zipfile.ZipFile(io.BytesIO(embedded)) as inner:
        assert "assets/xposed_init" not in inner.namelist()
        for name in inner.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                assert outer.read("payload/" + name) == inner.read(name), name
print("Verified standalone WebUI and runtime payload:", path)
PY
