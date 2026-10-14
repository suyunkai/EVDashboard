#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
project_dir=$PWD
export CC=${CC:-cc} CFLAGS='-O2 -std=gnu2x'
mkdir -p build/host
for variant in audit legacy; do
  dest="$project_dir/build/host/$variant"
  if [ ! -f "$dest/.host-configured" ]; then
    mkdir -p "$dest"
    cp -R "vendor/erofs-$variant/." "$dest/"
    (cd "$dest"; make distclean >/dev/null 2>&1 || true
      ./configure --disable-lz4 --disable-lzma --without-zlib --without-libdeflate --without-libzstd --without-uuid --without-selinux --without-libcurl --without-openssl --without-libxml2 --without-json-c --without-libnl3 --without-xxhash --disable-fuse --disable-multithreading --disable-shared --enable-static > configure-output.log 2>&1
      touch .host-configured)
  fi
  make -C "$dest" -j4 > "$dest/build-output.log" 2>&1 || { tail -60 "$dest/build-output.log"; exit 1; }
done
$CC $CFLAGS -DHAVE_CONFIG_H -Ibuild/host/audit/include -Ibuild/host/audit native/image_tool.c build/host/audit/lib/.libs/liberofs.a -o build/host/image_tool
$CC $CFLAGS native/fip_pack.c -o build/host/fip_pack
$CC $CFLAGS native/fip_verify.c -o build/host/fip_verify
cp build/host/audit/fsck/fsck.erofs build/host/fsck_erofs
cp build/host/legacy/mkfs/mkfs.erofs build/host/mkfs_erofs
echo 'Host regular-file test tools ready.'
