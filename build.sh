#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p lib build/classes build/tests dist
for artifact in api-core api-simplified; do
  if [[ ! -f "lib/$artifact.jar" ]]; then
    curl -fSL "https://maven.bookmap.com/maven2/releases/com/bookmap/api/$artifact/7.6.0.20/$artifact-7.6.0.20.jar" -o "lib/$artifact.jar"
  fi
done
cp_api='lib/api-core.jar:lib/api-simplified.jar'
java com.sun.tools.javac.Main -cp "$cp_api" -d build/classes src/main/java/ai/tradingassist/bookmap/*.java
java com.sun.tools.javac.Main -cp build/classes -d build/tests src/test/java/ai/tradingassist/bookmap/*.java
java -cp build/classes:build/tests ai.tradingassist.bookmap.BridgeClientTest
# Full JDK required. No Bookmap API classes are bundled into the add-on.
java sun.tools.jar.Main --create --file dist/tradingassist-trader2b-paper.jar -C build/classes .
echo 'Built dist/tradingassist-trader2b-paper.jar'
