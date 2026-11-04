#!/bin/sh
# Compiles the shard sync code and runs its tests. Needs a JDK and the Kotlin compiler jars of Gradle.
# Usage: tools/shardsync-test/run.sh [gradle-lib-dir]   (default: /opt/gradle-*/lib)
set -e
L=${1:-$(ls -d /opt/gradle-*/lib | head -1)}
K=$(ls $L/kotlin-compiler-embeddable-*.jar | head -1)
V=$(basename $K .jar | sed 's/kotlin-compiler-embeddable-//')
CP=$K:$L/kotlin-stdlib-$V.jar:$L/kotlin-reflect-$V.jar:$L/kotlin-script-runtime-$V.jar:$L/kotlin-daemon-embeddable-$V.jar:$(ls $L/annotations-*.jar | head -1):$(ls $L/kotlinx-coroutines-core-jvm-*.jar $L/trove4j*.jar 2>/dev/null | tr '\n' ':')
G=$(ls $L/gson-*.jar | head -1)
OUT=$(mktemp -d)
HERE=$(cd "$(dirname "$0")" && pwd)
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp $L/kotlin-stdlib-$V.jar:$G -d $OUT \
  $HERE/../../src/main/kotlin/phonon/nodes/shardsync/StateMerge.kt $HERE/../../src/main/kotlin/phonon/nodes/shardsync/SyncStore.kt $HERE/../../src/main/kotlin/phonon/nodes/shardsync/SyncEngine.kt $HERE/MergeTest.kt
java -cp $OUT:$L/kotlin-stdlib-$V.jar:$G MergeTestKt
