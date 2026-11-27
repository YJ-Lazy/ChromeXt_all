#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p deps/zygisk deps/xdl-wrapper
clone_pin() {
  local url="$1" dst="$2" sha="$3"
  if [ ! -d "$dst/.git" ]; then git init "$dst"; git -C "$dst" remote add origin "$url"; fi
  git -C "$dst" fetch --depth 1 origin "$sha"
  git -C "$dst" checkout --detach FETCH_HEAD
}
# Pin a revision before the upstream NDK r30 / libc++ modules migration.
clone_pin https://github.com/LSPosed/LSPlant.git deps/lsplant d8b5d1dbb664abc606644036822e4bb64547edf6
git -C deps/lsplant submodule update --init --recursive --depth 1 lsplant/src/main/jni/external/dex_builder
clone_pin https://github.com/LSPosed/Dobby.git deps/dobby c1da0315d7a2069bde2ab432e45d4ab6df91237a
clone_pin https://github.com/hexhacking/xDL.git deps/xdl-wrapper/external/xDL 1e0b6254165a2ddcbd32f77a371700c69155acf8
for file in xdl.c xdl.h; do
  curl --fail --location --retry 3 "https://raw.githubusercontent.com/LSPosed/LSPlant/3a240637e621677457c202150100d3ebcab924c6/test/src/main/jni/$file" -o "deps/xdl-wrapper/$file"
done
curl --fail --location --retry 3 https://raw.githubusercontent.com/topjohnwu/zygisk-module-sample/8ce26128f81baaed0b969aaf7f52f886b61af4ab/module/jni/zygisk.hpp -o deps/zygisk/zygisk.hpp
