#!/usr/bin/env python3
"""Execute the ART fallback code extracted from the actual APK payload.

No Android target is attached. Node executes the packaged JS decision and GCC
executes the packaged native visitor counting blocks; neither result promises
that an untested device's other ART layouts or method hooks are compatible.
"""
import argparse
import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

sys.dont_write_bytecode = True
REPO = pathlib.Path(__file__).resolve().parents[1]
NODE = None


class ObjectionArtCompatibilityRegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tarfile.open(REPO / "app/src/main/assets/toolchain/common.tar.gz") as archive:
            cls.agent = archive.extractfile("python-site/objection/agent.js").read().decode()
        cls.manifest = json.loads((REPO / "app/src/main/assets/toolchain/manifest.json").read_text())
        spec = importlib.util.spec_from_file_location("objection_android", REPO / "tools/objection_android.py")
        cls.patch = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.patch)

    def javascript(self, source):
        completed = subprocess.run([NODE, "-"], input=source, text=True, capture_output=True, timeout=10)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def jvmti_function(self):
        begin = self.agent.index("function tryGetEnvJvmti(")
        end = self.agent.index("\nfunction ensureClassInitialized(", begin)
        return self.agent[begin:end]

    def test_packaged_default_never_enters_vm_or_native_plugin_loader(self):
        # These traps would fail the test if the old dangerous branch ran at all.
        self.javascript(self.agent[:self.agent.index("\n", self.agent.index("globalThis.")) + 1]
                        + self.jvmti_function() + "\n"
                        + "globalThis.NativeFunction = function () { throw new Error('native loader entered'); };\n"
                        + "const vm = { perform() { throw new Error('VM transition entered'); } };\n"
                        + "if (tryGetEnvJvmti(vm, null) !== null) throw new Error('optional env was not absent');\n")

    def test_opt_out_is_scoped_and_preserves_the_upstream_missing_symbol_path(self):
        self.javascript(self.jvmti_function() + "\n"
                        + "globalThis.FRIDA_JAVA_BRIDGE_DISABLE_JVMTI = false;\n"
                        + "let transitions = 0;\n"
                        + "function getApi() { return { find() { return null; } }; }\n"
                        + "const vm = { perform(fn) { transitions++; fn(); } };\n"
                        + "if (tryGetEnvJvmti(vm, null) !== null || transitions !== 1) throw new Error('upstream optional path changed');\n")

    def test_unknown_copied_method_offset_is_a_sentinel_without_overwriting_known_offsets(self):
        begin = self.agent.index("      if (offsetCopiedMethods === -1) {")
        end = self.agent.index("\n      spec =", begin)
        block = self.agent[begin:end]
        self.javascript("function probe(offsetCopiedMethods) {\n" + block + "\nreturn offsetCopiedMethods; }\n"
                        + "if (probe(-1) !== 0 || probe(24) !== 24) throw new Error('unknown sentinel or known offset changed');\n")

    def test_both_packaged_native_visitors_use_array_bounds_when_the_field_is_unknown(self):
        blocks = []
        for object_name, indent in (("class_object", "    "), ("klass", "  ")):
            start = self.agent.index(indent + "elements = read_art_array (" + object_name + ", art_api.class_offset_methods,")
            end = self.agent.index("\n" + indent + "for (i = 0;", start)
            blocks.append(self.agent[start:end])
        source = """
#include <assert.h>
#include <stdint.h>
#include <string.h>
typedef void * gpointer;
typedef uintptr_t gsize;
typedef uint16_t guint16;
static unsigned int actual_array_length;
static void * read_art_array(void * object, unsigned int offset, gsize element_size, unsigned int * n) {
  (void) object; (void) offset; (void) element_size;
  if (n != 0) *n = actual_array_length;
  return 0;
}
struct { unsigned int class_offset_methods, class_offset_copied_methods_offset; } art_api;
"""
        for index, block in enumerate(blocks):
            source += ("unsigned int visitor%d(void * object) {\nunsigned int n = 0; void * elements;\n"
                       "void * class_object = object; void * klass = object; (void)class_object; (void)klass;\n"
                       % index) + block + "\n(void)elements; return n; }\n"
        source += """
int main(void) {
  unsigned char object[32]; memset(object, 255, sizeof(object));
  art_api.class_offset_copied_methods_offset = 0;
  actual_array_length = 3;
  assert(visitor0(object) == 3 && visitor1(object) == 3);
  actual_array_length = 0;
  assert(visitor0(object) == 0 && visitor1(object) == 0);
  art_api.class_offset_copied_methods_offset = 8;
  *(guint16 *)(object + 8) = 2;
  actual_array_length = 3;
  assert(visitor0(object) == 2 && visitor1(object) == 2);
  return 0;
}
"""
        with tempfile.TemporaryDirectory(prefix="backcast-objection-native-test-") as directory:
            binary = pathlib.Path(directory) / "native-visitors"
            completed = subprocess.run(["cc", "-x", "c", "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-o", str(binary), "-"],
                                       input=source, text=True, capture_output=True, timeout=20)
            self.assertEqual(0, completed.returncode, completed.stderr)
            subprocess.run([str(binary)], check=True, timeout=10)

    def test_rebuilder_is_idempotent_and_refuses_changed_or_partial_agents(self):
        self.assertEqual(self.agent, self.patch.patch_agent(self.agent))
        original = self.agent[len(self.patch.PREFIX):]
        for before, after in self.patch.REPLACEMENTS:
            original = original.replace(after, before, 1)
        self.assertEqual(self.agent, self.patch.patch_agent(original))
        for changed in (original.replace("function tryGetEnvJvmti(vm3, runtime4)", "function tryGetEnvJvmti(vm3, different)"),
                        self.agent.replace(self.patch.REPLACEMENTS[2][1], self.patch.REPLACEMENTS[2][0], 1)):
            with self.assertRaises(ValueError):
                self.patch.patch_agent(changed)

    def test_manifest_describes_a_local_backport_and_null_fallback_is_present(self):
        self.assertEqual("jni-no-jvmti", self.manifest["objection_art_mode"])
        self.assertEqual("7.0.13-backcast.1", self.manifest["java_bridge"])
        self.assertEqual("https://github.com/frida/frida-java-bridge/pull/407", self.manifest["objection_art_patch_source"])
        self.assertIn(self.patch.PATCH_DESCRIPTION, self.manifest["patches"])
        # The compiled bridge must keep its supported ART visitor / JNI paths.
        self.assertIn("if (api4.jvmti !== null)", self.agent)
        self.assertIn("makeArtController(temporaryApi, vm3)", self.agent)
        self.assertIn("ArtMethodMangler", self.agent)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--node", default=shutil.which("node"), help="Path to the host Node.js test runtime")
    options = parser.parse_args()
    if not options.node:
        parser.error("--node is required when Node.js is not on PATH")
    NODE = options.node
    unittest.main(argv=[sys.argv[0]], verbosity=2)
