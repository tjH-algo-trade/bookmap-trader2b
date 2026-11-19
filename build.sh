#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
for tool in java javac jar; do
  command -v "$tool" >/dev/null || { echo "A full JDK 21 or newer is required ($tool missing)." >&2; exit 1; }
done
javac_version=$(javac -version 2>&1)
if [[ ! "$javac_version" =~ ^javac\ ([0-9]+) ]] || (( BASH_REMATCH[1] < 21 )); then
  echo "A full JDK 21 or newer is required; found $javac_version. Set JAVA_HOME to your JDK." >&2
  exit 1
fi
mkdir -p lib dist
for artifact in api-core api-simplified; do
  if [[ ! -f "lib/$artifact.jar" ]]; then
    curl -fSL "https://maven.bookmap.com/maven2/releases/com/bookmap/api/$artifact/7.6.0.20/$artifact-7.6.0.20.jar" -o "lib/$artifact.jar.tmp"
    mv "lib/$artifact.jar.tmp" "lib/$artifact.jar"
  fi
done
# Never include classes left behind by a previous source tree.
rm -rf build/classes build/tests
mkdir -p build/classes build/tests
cp_api='lib/api-core.jar:lib/api-simplified.jar'
javac --release 21 -encoding UTF-8 -cp "$cp_api" -d build/classes src/main/java/ai/tradingassist/bookmap/*.java
javac --release 21 -encoding UTF-8 -cp build/classes -d build/tests src/test/java/ai/tradingassist/bookmap/*.java
java -Djava.awt.headless=true -cp build/classes:build/tests ai.tradingassist.bookmap.BridgeClientTest
java -Djava.awt.headless=true -cp build/classes:build/tests ai.tradingassist.bookmap.ResponseLimitsTest
# No Bookmap API classes are bundled into the add-on.
jar --create --file dist/tradingassist-trader2b-paper.jar -C build/classes .
echo 'Built dist/tradingassist-trader2b-paper.jar (Java 21 target)'
