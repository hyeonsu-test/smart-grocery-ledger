#!/usr/bin/env sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi
mkdir -p bin test-bin test-log doc/api resources lib
for classes in bin/sgl test-bin/sgl; do
  if [ -d "$classes" ]; then find "$classes" -maxdepth 1 -type f -name '*.class' -delete; fi
done
find src/sgl -name '*.java' -print | sort > test-log/sources.args
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -d bin @test-log/sources.args > test-log/build.log 2>&1 || { cat test-log/build.log; exit 1; }
javadoc --release 17 -encoding UTF-8 -docencoding UTF-8 -charset UTF-8 -Xdoclint:all -Werror -quiet -notimestamp -windowtitle 'Smart Grocery Ledger Java API' -doctitle 'Smart Grocery Ledger Java API' -d doc/api @test-log/sources.args > test-log/javadoc.log 2>&1 || { cat test-log/javadoc.log; exit 1; }
jar --create --file resources/api.zip -C doc/api .
jar --create --file SmartGroceryLedger.jar --main-class sgl.Main -C bin . -C resources .
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror -cp bin -d test-bin test/sgl/ServiceTests.java test/sgl/UiSmokeTests.java test/sgl/UiRenderEvidence.java > test-log/test-compile.log 2>&1 || { cat test-log/test-compile.log; exit 1; }
cp test/fixtures/legacy-ledger-v1.json test-bin/legacy-ledger-v1.json
java -Dfile.encoding=UTF-8 -cp bin:test-bin sgl.ServiceTests > test-log/service-tests.log 2>&1 || { cat test-log/service-tests.log; exit 1; }
cat test-log/service-tests.log
java -Dfile.encoding=UTF-8 -jar SmartGroceryLedger.jar --smoke-test > test-log/jar-smoke.log 2>&1 || { cat test-log/jar-smoke.log; exit 1; }
cat test-log/jar-smoke.log
printf '%s\n' 'Built SmartGroceryLedger.jar and doc/api/index.html (Java 17+)'
