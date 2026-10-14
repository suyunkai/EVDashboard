#!/bin/bash
# Corresponding source bundle only: no vehicle images, local settings or build products.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p dist
bundle="dist/instrument-workshop-0.1.0-source.tar.gz"
COPYFILE_DISABLE=1 tar -czf "$bundle" \
  --exclude='.DS_Store' --exclude='*/build' --exclude='*/jniLibs' \
  --exclude='*/.deps' --exclude='*/.libs' --exclude='*/autom4te.cache' \
  --exclude='*.o' --exclude='*.lo' --exclude='*.la' --exclude='*.a' \
  --exclude='*/config.log' --exclude='*/config.status' --exclude='*/libtool' \
  --exclude='*/Makefile' --exclude='*/stamp-h1' --exclude='*/.dirstamp' \
  --exclude='vendor/erofs-audit/config.h' --exclude='vendor/erofs-legacy/config.h' \
  --exclude='vendor/*/*/*.erofs' --exclude='*~' \
  app native vendor tools gradle gradlew gradlew.bat build.gradle.kts \
  settings.gradle.kts gradle.properties .gitignore README.md THIRD_PARTY.md docs/verification.md
tar -tzf "$bundle" >/dev/null
shasum -a 256 "$bundle"
