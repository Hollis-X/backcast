#!/bin/sh
root=${1:?Pass the absolute project root}
apktool=${2:?Pass the upstream Apktool 2.9.3 JAR}
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT HUP INT TERM
javac -proc:none -encoding UTF-8 -source 7 -target 7 -Xlint:-options -cp "$apktool" -d "$build" \
    "$root"/tests/support/android/graphics/*.java \
    "$root"/tools/apktool_android/brut/androlib/res/decoder/Res9patchStreamDecoder.java \
    "$root"/tests/ApktoolNinePatchRegressionTest.java || exit "$?"
java -Djava.awt.headless=true -cp "$build:$apktool" ApktoolNinePatchRegressionTest "$apktool"
