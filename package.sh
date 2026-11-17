#!/bin/bash
# Build the tarball you copy to the server.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=$(node -p "require('./package.json').version")
NAME="dsh-watch-bridge-$VERSION"
rm -rf "dist/$NAME" "dist/$NAME.tar.gz"
mkdir -p "dist/$NAME"

cp -r package.json lib cordis.patch.yml README.md install.sh package.sh android "dist/$NAME/"
rm -rf "dist/$NAME/android/build" "dist/$NAME/android/keystore.jks" "dist/$NAME/android/build.sh.bak"
mkdir -p "dist/$NAME/dist"
cp dist/dsh-watch.apk "dist/$NAME/dist/"

tar -czf "dist/$NAME.tar.gz" -C dist "$NAME"
rm -rf "dist/$NAME"
ls -lh "dist/$NAME.tar.gz"
