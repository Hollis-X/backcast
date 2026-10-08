# Toolchain 2026.10.01 sources and notices

Release archives contain the license and copyright files supplied by upstream
projects, including `usr/share/doc/*`, `usr/share/LICENSES`, Python distribution
metadata and the radare2 license notices. This APK also retains the principal
Apktool, PNGJ, Objection, Frida, Python and radare2 license texts.

The shipped `manifest.json` records exact versions, binary input URLs and hashes.
The toolchain archives are Android builds, not desktop binaries. Backcast's
compatibility changes and packaging scripts are available with the application
source at https://github.com/Hollis-X/backcast/tree/toolchain-2026.10.01/tools .

Source locations for the principal programs:

* Apktool 2.9.3: https://github.com/iBotPeaches/Apktool/tree/v2.9.3
* PNGJ 2.1.0: https://repo.maven.apache.org/maven2/ar/com/hjg/pngj/2.1.0/pngj-2.1.0-sources.jar
* radare2 6.2.2: https://github.com/radareorg/radare2/tree/6.2.2
* Objection 1.12.5: https://github.com/sensepost/objection/tree/1.12.5
* Frida 17.2.14: https://github.com/frida/frida/tree/17.2.14
* Python 3.14.6: https://github.com/python/cpython/tree/v3.14.6
* GNU binutils 2.47 (GPLv3): https://mirrors.kernel.org/gnu/binutils/binutils-2.47.tar.xz
  SHA-256: `154ab23b60070e8f27013c22977f1129425d67d1e8acd6e13010e617811e4cff`.
  The Android build also uses Termux's recipe and patches:
  https://github.com/termux/termux-packages/tree/5b3fed70e498621ad3d66e233dfa2339a561063c/packages/binutils
  and the build scripts in the same repository revision.

Other native libraries originate from the Termux packages listed by name and
version in the manifest. Their upstream source declarations and Android patches
are in https://github.com/termux/termux-packages/tree/5b3fed70e498621ad3d66e233dfa2339a561063c/packages . Python
dependencies retain their distribution metadata and licenses in the installed
toolchain; their exact input distributions are listed in the manifest.

Publish this notice beside the binary release archives and preserve source
access for the corresponding release. A `.deb` download in the input provenance
list is a binary package and does not replace the corresponding source code.
