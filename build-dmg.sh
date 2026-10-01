#!/usr/bin/env bash
# Build JEdit.dmg - requires JDK 21+ with jpackage on PATH
set -euo pipefail

VERSION="${VERSION:-1.0.0}"
JAR="target/jedit-${VERSION}.jar"

echo "==> Building fat JAR…"
mvn -q package -DskipTests

echo "==> Linking runtime…"
rm -rf target/runtime target/jpackage-in target/jpackage-out
mkdir -p target/jpackage-in
cp "$JAR" target/jpackage-in/
jlink \
  --add-modules java.se,jdk.compiler,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.httpserver,jdk.charsets \
  --strip-debug --no-man-pages --no-header-files \
  --output target/runtime

echo "==> Running jpackage…"
jpackage \
  --type dmg \
  --name "JEdit" \
  --app-version "$VERSION" \
  --vendor "jrun" \
  --description "Lightweight Java scratchpad" \
  --input target/jpackage-in \
  --main-jar "jedit-${VERSION}.jar" \
  --main-class Editor \
  --dest target/jpackage-out \
  --java-options "-Dapple.awt.application.name=JEdit" \
  --runtime-image target/runtime \
  --mac-package-identifier io.jrun.jedit \
  --mac-package-name JEdit

DMG=$(ls target/jpackage-out/*.dmg | head -1)
echo "==> DMG ready: $DMG"
