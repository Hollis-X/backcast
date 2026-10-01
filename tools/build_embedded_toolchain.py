#!/usr/bin/env python3
"""Rebuild the APK's offline Android toolchain from upstream artifacts.

Run with a disposable --work directory and a separately downloaded official R8
compiler. Network access is only required by this build utility, never the app.
"""
import argparse
import concurrent.futures
import email
import gzip
import hashlib
import io
import json
import os
import pathlib
import shutil
import struct
import subprocess
import tarfile
import urllib.request
import zipfile

BASE = "https://packages.termux.dev/apt/"
APKTOOL = "https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar"
R2 = "https://github.com/radareorg/radare2/releases/download/6.2.2/radare2-6.2.2-android-{}.tar.gz"
REQUIRED = ["binutils", "python", "aapt2", "termux-licenses"]
PYTHON_PACKAGES = ["objection==1.12.5", "click", "delegator-py", "flask", "litecli", "packaging", "prompt-toolkit", "pygments", "requests", "semver", "setuptools", "tabulate", "colorama", "rich", "websockets", "pexpect", "ptyprocess", "wcwidth", "blinker", "itsdangerous", "jinja2", "werkzeug", "markdown-it-py", "mdurl", "certifi", "charset-normalizer", "idna", "urllib3", "terminaltables", "pymysql", "sqlparse", "configobj", "cli_helpers"]


def fetch(url, destination, expected=None):
    if not destination.exists():
        with urllib.request.urlopen(url, timeout=90) as src, destination.open("wb") as dst:
            shutil.copyfileobj(src, dst)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    if expected and digest != expected:
        raise ValueError("SHA-256 mismatch: " + url)
    return {"url": url, "sha256": digest, "bytes": destination.stat().st_size}


def index(url):
    text = gzip.decompress(urllib.request.urlopen(url, timeout=45).read()).decode()
    records = {}
    for block in text.split("\n\n"):
        record = dict(line.split(": ", 1) for line in block.splitlines() if ": " in line and not line.startswith(" "))
        if "Package" in record:
            records[record["Package"]] = record
    return records


def unpack_deb(path, destination):
    # ar is only used at build time; the Android app receives portable tar.gz.
    member = next(name for name in subprocess.check_output(["ar", "t", str(path)], text=True).splitlines() if name.startswith("data.tar"))
    with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(["ar", "p", str(path), member]))) as archive:
        members = []
        for item in archive.getmembers():
            if "/usr/var/" in item.name:
                continue
            if item.issym() and item.linkname.startswith("/data/data/com.termux/files/usr/"):
                item.linkname = os.path.relpath(item.linkname.lstrip("/"), pathlib.PurePosixPath(item.name).parent)
            members.append(item)
        archive.extractall(destination, members=members, filter="data")


def compress(source, output):
    with output.open("wb") as file, gzip.GzipFile(fileobj=file, mode="wb", mtime=0, compresslevel=9) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for path in sorted(source.rglob("*")):
                if path.is_file() and path.suffix != ".pyc" and "__pycache__" not in path.parts:
                    name = path.relative_to(source).as_posix()
                    info = archive.gettarinfo(str(path), name)
                    info.mtime = 0
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    with path.open("rb") as stream:
                        archive.addfile(info, stream)
    return {"sha256": hashlib.sha256(output.read_bytes()).hexdigest(), "bytes": output.stat().st_size}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--work", type=pathlib.Path, required=True)
    parser.add_argument("--r8", type=pathlib.Path, required=True)
    parser.add_argument("--android-jar", type=pathlib.Path, required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    work = args.work
    work.mkdir(parents=True, exist_ok=True)
    repo = pathlib.Path(__file__).resolve().parents[1]
    output = repo / "app/src/main/assets/toolchain"
    output.mkdir(parents=True, exist_ok=True)
    sources = []
    licenses = output / "licenses"
    licenses.mkdir(exist_ok=True)
    for name, url in [("Apktool-Apache-2.0.txt", "https://raw.githubusercontent.com/iBotPeaches/Apktool/v2.9.3/LICENSE.md"),
                      ("radare2-COPYING.txt", "https://raw.githubusercontent.com/radareorg/radare2/6.2.2/COPYING.md"),
                      ("Frida-COPYING.txt", "https://raw.githubusercontent.com/frida/frida-core/17.2.14/COPYING"),
                      ("Objection-GPL-3.0.txt", "https://raw.githubusercontent.com/sensepost/objection/1.12.5/LICENSE"),
                      ("Python-LICENSE.txt", "https://raw.githubusercontent.com/python/cpython/v3.14.6/LICENSE")]:
        sources.append(fetch(url, licenses / name))
    common = work / "common"
    (common / "apktool").mkdir(parents=True, exist_ok=True)
    jar = work / "apktool.jar"
    sources.append(fetch(APKTOOL, jar, "7956eb04194300ce0d0a84ad18771eebc94b89fb8d1ddcce8ea4c056818646f4"))
    replacements = work / "apktool-android-classes"
    replacements.mkdir(exist_ok=True)
    java_sources = list((repo / "tools/apktool_android").rglob("*.java"))
    if not java_sources:
        raise ValueError("Missing the Android Bitmap compatibility source")
    javac = str(pathlib.Path(args.java).with_name("javac"))
    subprocess.run([javac, "-source", "7", "-target", "7", "-encoding", "UTF-8", "-cp", str(jar) + os.pathsep + str(args.android_jar), "-d", str(replacements)] + [str(path) for path in java_sources], check=True)
    patched_jar = work / "apktool-android.jar"
    with zipfile.ZipFile(jar) as original, zipfile.ZipFile(patched_jar, "w", zipfile.ZIP_DEFLATED) as patched:
        for item in original.infolist():
            replacement = replacements / item.filename
            patched.writestr(item.filename, replacement.read_bytes() if replacement.is_file() else original.read(item))
    dex = work / "android-dex"
    dex.mkdir(exist_ok=True)
    subprocess.run([args.java, "-cp", str(args.r8), "com.android.tools.r8.D8", "--release", "--min-api", "26", "--lib", str(args.android_jar), "--output", str(dex), str(patched_jar)], check=True)
    with zipfile.ZipFile(jar) as original, zipfile.ZipFile(common / "apktool/apktool-dex.jar", "w", zipfile.ZIP_DEFLATED) as android:
        for item in original.infolist():
            if not item.filename.endswith(".class") and not item.filename.startswith(("META-INF/", "prebuilt/")) and not item.is_dir():
                android.writestr(item.filename, original.read(item))
        for path in sorted(dex.glob("classes*.dex")):
            android.write(path, path.name)
    wheels = work / "wheels"
    wheels.mkdir(exist_ok=True)
    subprocess.run(["python3", "-m", "pip", "download", "--no-deps", "--dest", str(wheels), "--only-binary=:all:", "--platform", "any", "--python-version", "314"] + [p for p in PYTHON_PACKAGES if not p.startswith("objection") and p not in ("flask", "websockets", "litecli")], check=True)
    # Packages with optional C accelerators are bundled as Python source. Frida
    # itself comes from Termux's Android build, never a glibc desktop wheel.
    for name, version in [("objection", "1.12.5"), ("flask", None), ("markupsafe", None), ("websockets", None), ("litecli", None), ("cli_helpers", None), ("configobj", None), ("sqlparse", None)]:
        metadata = json.load(urllib.request.urlopen("https://pypi.org/pypi/" + name + ("/" + version if version else "") + "/json"))
        pure = [entry for entry in metadata["urls"] if entry["filename"].endswith("-none-any.whl")]
        entry = pure[0] if pure else next(item for item in metadata["urls"] if item["packagetype"] == "sdist")
        path = wheels / entry["filename"]
        sources.append(fetch(entry["url"], path, entry["digests"]["sha256"]))
    site = common / "python-site"
    site.mkdir(exist_ok=True)
    for path in sorted(wheels.iterdir()):
        if path.suffix == ".whl":
            with zipfile.ZipFile(path) as wheel:
                wheel.extractall(site)
        elif path.name.endswith(".tar.gz"):
            with tarfile.open(path) as source:
                package = path.name.split("-", 1)[0].replace("-", "_")
                for member in source.getmembers():
                    marker = "/src/" + package + "/"
                    if member.isfile() and marker in member.name:
                        relative = package + "/" + member.name.split(marker, 1)[1]
                        target = site / relative
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(source.extractfile(member).read())
        if not any(source["sha256"] == hashlib.sha256(path.read_bytes()).hexdigest() for source in sources):
            with zipfile.ZipFile(path) as wheel:
                package_metadata = email.message_from_bytes(wheel.read(next(item for item in wheel.namelist() if item.endswith(".dist-info/METADATA"))))
            release = json.load(urllib.request.urlopen("https://pypi.org/pypi/" + package_metadata["Name"] + "/" + package_metadata["Version"] + "/json"))
            entry = next(item for item in release["urls"] if item["filename"] == path.name)
            sources.append(fetch(entry["url"], path, entry["digests"]["sha256"]))
    artifacts = [{"asset": "toolchain/common.tar.gz", "abi": "any", "min_sdk": 26, **compress(common, output / "common.tar.gz")}]
    for arch, abi, rarch in [("aarch64", "arm64-v8a", "aarch64"), ("arm", "armeabi-v7a", "arm")]:
        packages = index(BASE + "termux-main/dists/stable/main/binary-" + arch + "/Packages.gz")
        root_packages = index(BASE + "termux-root/dists/root/stable/binary-" + arch + "/Packages.gz")
        packages.update(root_packages)
        selected = set()
        def dependencies(name):
            if name in selected:
                return
            selected.add(name)
            for dep in packages[name].get("Depends", "").split(","):
                dep = dep.strip().split(" ")[0].split("|")[0]
                if dep:
                    dependencies(dep)
        for name in REQUIRED + ["frida-python"]:
            dependencies(name)
        raw = work / (arch + "-packages")
        raw.mkdir(exist_ok=True)
        native = work / (arch + "-payload")
        if native.exists():
            shutil.rmtree(native)
        (native / "usr").mkdir(parents=True, exist_ok=True)
        def obtain(name):
            record = packages[name]
            filename = record["Filename"]
            endpoint = "termux-root/" if name in root_packages else "termux-main/"
            path = work / pathlib.Path(filename).name
            receipt = fetch(BASE + endpoint + filename, path, record["SHA256"])
            receipt.update({"package": name, "version": record["Version"], "abi": abi})
            return path, receipt
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            for path, receipt in executor.map(obtain, sorted(selected)):
                sources.append(receipt)
                unpack_deb(path, raw)
        prefix = raw / "data/data/com.termux/files/usr"
        for directory in ("bin", "lib", "etc", "share"):
            if (prefix / directory).exists():
                shutil.copytree(prefix / directory, native / "usr" / directory, dirs_exist_ok=True, symlinks=False)
        subprocess_module = native / "usr/lib/python3.14/subprocess.py"
        subprocess_module.write_text(subprocess_module.read_text().replace("/data/data/com.termux/files/usr/bin/sh", "/system/bin/sh"))
        for path in (native / "usr").rglob("*"):
            if path.is_file() and (path.suffix in (".a", ".pyc") or "/include/" in str(path) or "/pkgconfig/" in str(path)):
                path.unlink()
        for path in [native / "usr/share/man", native / "usr/lib/python3.14/test", native / "usr/lib/python3.14/site-packages/pip", native / "usr/lib/python3.14/site-packages/frida_tools/tracer_ui", native / "usr/bin/frida-gadget", native / "usr/bin/frida-inject", native / "usr/bin/frida-portal"]:
            if path.is_dir():
                shutil.rmtree(path)
            elif path.exists():
                path.unlink()
        needed = set()
        for path in native.rglob("*"):
            if path.is_file() and path.open("rb").read(4) == b"\x7fELF":
                dynamic = subprocess.check_output(["readelf", "-d", str(path)], text=True)
                for line in dynamic.splitlines():
                    if "(NEEDED)" in line:
                        needed.add(line.split("[", 1)[1].split("]", 1)[0])
        hashes = {}
        for path in sorted((native / "usr/lib").glob("*.so*")):
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest in hashes and path.name not in needed:
                path.unlink()
            elif digest in hashes and hashes[digest].name not in needed:
                hashes[digest].unlink()
                hashes[digest] = path
            else:
                hashes[digest] = path
        r2archive = work / ("radare2-" + rarch + ".tar.gz")
        sources.append(fetch(R2.format(rarch), r2archive, "228bf58c44fbd9f3afd37ba545ef73155415150849dbd576ad1fd89789082c9f" if arch == "aarch64" else "1ecca02220f0309a7a6d5349fc914e1d930b73dca09a27567abc862dd9df6c2b"))
        with tarfile.open(r2archive) as archive:
            for member in archive.getmembers():
                marker = "data/data/org.radare.radare2installer/radare2/"
                if (member.isfile() or member.issym() or member.islnk()) and member.name.startswith(marker):
                    target = native / "radare2" / member.name[len(marker):]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    stream = archive.extractfile(member)
                    if stream is None:
                        continue
                    target.write_bytes(stream.read())
                    target.chmod(member.mode)
        r2_needed = set()
        for path in (native / "radare2").rglob("*"):
            if path.is_file() and path.open("rb").read(4) == b"\x7fELF":
                dynamic = subprocess.check_output(["readelf", "-d", str(path)], text=True)
                for line in dynamic.splitlines():
                    if "(NEEDED)" in line:
                        r2_needed.add(line.split("[", 1)[1].split("]", 1)[0])
        for path in (native / "radare2/lib").glob("*.so*"):
            if path.name not in r2_needed and ".so." in path.name:
                path.unlink()
        expected_machine = 183 if arch == "aarch64" else 40
        excluded = []
        for path in native.rglob("*"):
            if not path.is_file():
                continue
            with path.open("rb") as stream:
                header = stream.read(20)
            if len(header) == 20 and header[:4] == b"\x7fELF" and struct.unpack("<H", header[18:20])[0] != expected_machine:
                if path.relative_to(native).as_posix() != "radare2/bin/r2sdb":
                    raise ValueError("Wrong ABI in native payload: " + str(path))
                # The upstream Android archive includes this x86_64 build-time
                # SDB helper. Runtime radare2 links its Android libr_sdb.so.
                excluded.append(path.relative_to(native).as_posix())
                path.unlink()
        artifact = {"asset": "toolchain/" + abi + ".tar.gz", "abi": abi, "min_sdk": 24, **compress(native, output / (abi + ".tar.gz"))}
        artifact["excluded_upstream_host_tools"] = excluded
        if artifact["bytes"] >= 100 * 1024 * 1024:
            raise ValueError("Asset exceeds GitHub's file limit: " + abi)
        artifacts.append(artifact)
    manifest = {"version": "2026.10.01", "apktool": "2.9.3", "radare2": "6.2.2", "objection": "1.12.5", "patches": ["Apktool Res9patchStreamDecoder: replace desktop AWT/ImageIO with Android Bitmap APIs", "Python subprocess.py: use /system/bin/sh instead of the Termux installation prefix", "Materialize upstream internal symlinks as regular copies for private extraction", "Exclude desktop aapt binaries; include Android aapt2", "Exclude unneeded pip and Frida tracer web UI"], "converter": {"tool": "Google R8/D8 8.3.37", "sha256": hashlib.sha256(args.r8.read_bytes()).hexdigest(), "dex_min_api": 26}, "artifacts": artifacts, "sources": sources}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(artifacts, indent=2))


if __name__ == "__main__":
    main()
