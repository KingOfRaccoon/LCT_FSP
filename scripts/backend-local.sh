#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASK_JAVA_HOME="${JAVA_HOME:-}"
if [[ -z "$TASK_JAVA_HOME" && -x /usr/libexec/java_home ]]; then
  TASK_JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
if [[ -z "$TASK_JAVA_HOME" || ! -x "$TASK_JAVA_HOME/bin/java" ]]; then
  printf 'Install JDK 21 and set JAVA_HOME.\n' >&2
  exit 1
fi
cd "$ROOT/backend"
./gradlew --no-daemon --max-workers=1 "-Dorg.gradle.java.home=$TASK_JAVA_HOME" bootJar
mkdir -p "$ROOT/local/runtime"
RUN_JAR="$(mktemp "$ROOT/local/runtime/backend-run.XXXXXX")"
trap 'rm -f "$RUN_JAR"' EXIT
cp build/libs/fsp-talent-backend-0.1.0.jar "$RUN_JAR"
# A private copy prevents later builds from replacing the running JVM archive.
"$TASK_JAVA_HOME/bin/java" -jar "$RUN_JAR" --server.address=127.0.0.1 --fsp.seed-demo=true "$@"
