#!/usr/bin/env bash
# Build JEdit.dmg — requires JDK 21+ with jpackage on PATH
set -euo pipefail

VERSION="${VERSION:-1.0.0}"
JAR="target/jedit-${VERSION}.jar"

echo "==> Building fat JAR…"
mvn -q package -DskipTests

echo "==> Running jpackage…"
rm -rf target/jpackage-out
jpackage \
  --type dmg \
  --name "JEdit" \
  --app-version "$VERSION" \
  --vendor "jrun" \
  --description "Lightweight Java scratchpad" \
  --input target \
  --main-jar "jedit-${VERSION}.jar" \
  --main-class Editor \
  --dest target/jpackage-out \
  --java-options "-Dapple.awt.application.name=JEdit" \
  --add-modules java.desktop,java.compiler,jdk.compiler,java.sql,java.naming \
  --mac-package-identifier io.jrun.jedit \
  --mac-package-name JEdit

DMG=$(ls target/jpackage-out/*.dmg | head -1)
echo "==> DMG ready: $DMG"
