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
cmake --build zygisk/build/native --target chromext
rm -rf zygisk/build/module
rm -f zygisk/build/ChromeXt-Zygisk-arm64-exp.zip
mkdir -p zygisk/build/module/zygisk zygisk/build/module/payload zygisk/build/module/licenses zygisk/build/module/manager
cp -a zygisk/module/. zygisk/build/module/
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
cp zygisk/build/native/libchromext.so zygisk/build/module/zygisk/arm64-v8a.so
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
cp "$APK" zygisk/build/ChromeXt-Zygisk-Manager-debug.apk
cp "$APK" zygisk/build/module/manager/ChromeXt-Zygisk-Manager-debug.apk
for script in customize.sh service.sh install-manager.sh scope.sh; do
  sh -n "zygisk/build/module/$script"
  chmod 755 "zygisk/build/module/$script"
done
(cd zygisk/build/module && zip -qr ../ChromeXt-Zygisk-arm64-exp.zip .)
python3 - "$APK" <<'PY'
import io, pathlib, sys, zipfile
path = pathlib.Path("zygisk/build/ChromeXt-Zygisk-arm64-exp.zip")
with zipfile.ZipFile(path) as outer:
    required = {"module.prop", "customize.sh", "service.sh", "install-manager.sh",
                "zygisk/arm64-v8a.so", "payload/classes.dex",
                "manager/ChromeXt-Zygisk-Manager-debug.apk", "supported.list", "scope.sh",
                "webroot/index.html", "webroot/style.css", "webroot/app.js"}
    assert required <= set(outer.namelist()), required - set(outer.namelist())
    embedded = outer.read("manager/ChromeXt-Zygisk-Manager-debug.apk")
    assert embedded == pathlib.Path(sys.argv[1]).read_bytes()
    with zipfile.ZipFile(io.BytesIO(embedded)) as inner:
        assert "assets/xposed_init" not in inner.namelist()
        for name in inner.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                assert outer.read("payload/" + name) == inner.read(name), name
print("Verified embedded manager and native payload:", path)
PY
