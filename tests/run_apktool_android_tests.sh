#!/bin/sh
root=${1:?Pass the absolute project root}
apktool=${2:?Pass the upstream Apktool 2.9.3 JAR}
pngj=${3:?Pass the PNGJ 2.1.0 JAR}
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT HUP INT TERM
javac -proc:none -encoding UTF-8 -source 7 -target 7 -Xlint:-options -cp "$apktool:$pngj" -d "$build" \
    "$root"/tools/apktool_android/brut/androlib/res/decoder/Res9patchStreamDecoder.java \
    "$root"/tools/apktool_android/brut/util/OSDetection.java \
    "$root"/tests/ApktoolNinePatchRegressionTest.java \
    "$root"/tests/ApktoolOsDetectionRegressionTest.java \
    "$root"/tests/ApktoolDecodeRegressionTest.java || exit "$?"
java -Djava.awt.headless=true -cp "$build:$apktool:$pngj" ApktoolNinePatchRegressionTest "$apktool"
nine_status=$?
java -cp "$build:$apktool:$pngj" ApktoolOsDetectionRegressionTest "$build" "$apktool"
os_status=$?
java -cp "$build:$apktool:$pngj" ApktoolDecodeRegressionTest "$apktool" "$build"
decode_status=$?
if [ "$nine_status" -ne 0 ] || [ "$os_status" -ne 0 ] || [ "$decode_status" -ne 0 ]; then exit 1; fi
