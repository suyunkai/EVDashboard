#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
project_dir=$PWD
ndk_dir=${ANDROID_NDK_HOME:-/Users/syk/Library/Android/sdk/ndk/28.2.13676358}
llvm_dir="$ndk_dir/toolchains/llvm/prebuilt/darwin-x86_64/bin"
export CC="$llvm_dir/aarch64-linux-android30-clang -std=gnu23"
export AR="$llvm_dir/llvm-ar" RANLIB="$llvm_dir/llvm-ranlib" STRIP="$llvm_dir/llvm-strip"
export CFLAGS='-O2 -g0 -fPIE' LDFLAGS='-Wl,-z,max-page-size=16384'
mkdir -p build/native app/src/main/jniLibs/arm64-v8a
for variant in audit legacy; do
  dest="$project_dir/build/native/$variant"
  fingerprint=$( { rg --files --hidden "vendor/erofs-$variant" -g '*.c' -g '*.h' -g '*.ac' -g '*.am' -g '*.in' -g 'configure' -g "!vendor/erofs-$variant/config.h" | sort | xargs shasum -a 256; shasum -a 256 tools/build-native.sh; printf '%s' "$CC"; } | shasum -a 256 | cut -d ' ' -f1)
  if [ ! -f "$dest/.configured" ] || [ "$(cat "$dest/.configured")" != "$fingerprint" ]; then
    mkdir -p "$dest"
    cp -R "vendor/erofs-$variant/." "$dest/"
    (cd "$dest"; make distclean >/dev/null 2>&1 || true
     ./configure --host=aarch64-linux-android --disable-lz4 --disable-lzma --without-zlib --without-libdeflate --without-libzstd --without-uuid --without-selinux --without-libcurl --without-openssl --without-libxml2 --without-json-c --without-libnl3 --without-xxhash --disable-fuse --disable-multithreading --disable-shared --enable-static > configure-output.log 2>&1
     printf '%s\n' "$fingerprint" > .configured)
  fi
  make -C "$dest" -j4 > "$dest/build-output.log" 2>&1 || { tail -80 "$dest/build-output.log"; exit 1; }
done
out="$project_dir/app/src/main/jniLibs/arm64-v8a"
$CC $CFLAGS $LDFLAGS -DHAVE_CONFIG_H -Ibuild/native/audit/include -Ibuild/native/audit native/image_tool.c build/native/audit/lib/.libs/liberofs.a -o "$out/libimage_tool.so"
$CC $CFLAGS $LDFLAGS native/fip_pack.c -o "$out/libfip_pack.so"
$CC $CFLAGS $LDFLAGS native/fip_verify.c -o "$out/libfip_verify.so"
cp build/native/audit/fsck/fsck.erofs "$out/libfsck_erofs.so"
cp build/native/legacy/mkfs/mkfs.erofs "$out/libmkfs_erofs.so"
"$STRIP" "$out/"*.so
echo 'Android arm64 native tools ready (16 KiB ELF alignment).'
