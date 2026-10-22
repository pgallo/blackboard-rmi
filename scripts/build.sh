#!/usr/bin/env bash
# Dependency-free build: javac + jar only. Compiles main sources, compiles the tests against a
# small JUnit-compatible shim (src/testkit), and runs them with the in-repo test runner.
#   scripts/build.sh            build and test
#   scripts/build.sh --no-test  build only
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/testkit" "$OUT/test-classes"

find src/main/java -name '*.java' > "$OUT/main.txt"
javac --release 21 -Xlint:all -Werror -d "$OUT/classes" @"$OUT/main.txt"
jar --create --file "$OUT/blackboard-rmi.jar" --main-class io.github.pgallo.blackboard.app.Main -C "$OUT/classes" .
echo "built $OUT/blackboard-rmi.jar"

if [[ "${1:-}" == "--no-test" ]]; then exit 0; fi

find src/testkit/java -name '*.java' > "$OUT/testkit.txt"
javac --release 21 -d "$OUT/testkit" @"$OUT/testkit.txt"
find src/test/java -name '*.java' > "$OUT/test.txt"
javac --release 21 -Xlint:all -d "$OUT/test-classes" -cp "$OUT/classes:$OUT/testkit" @"$OUT/test.txt"
java -cp "$OUT/classes:$OUT/testkit:$OUT/test-classes" testkit.TestRunner "$OUT/test-classes"
