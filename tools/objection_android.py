"""Backcast's scoped ART compatibility patch for Objection's compiled agent.

The upstream Java bridge's JVMTI environment is optional: its callers already
fall back to JNI / ART class visitors when it is absent. Loading the plugin in
a live vendor ART VM can abort the target before a Java command can execute.
The opt-out and unknown copied-methods sentinel follow upstream PR #407:
https://github.com/frida/frida-java-bridge/pull/407

This is a local backport, not a claim that upstream merged that pull request.
Keep exact-match guards so a future Objection agent cannot be silently patched
at the wrong call site. Native Frida client/server versions are unchanged.
"""

MODE = "jni-no-jvmti"
BRIDGE_VERSION = "7.0.13-backcast.1"
PATCH_DESCRIPTION = (
    "Objection Java bridge: use JNI/ART fallback without optional JVMTI plugin "
    "loading; tolerate unknown copied-methods offset (upstream PR #407 backport)"
)
PREFIX = (
    "// Backcast: optional JVMTI loading can abort a live vendor ART VM.\n"
    "// JNI/ART class visitors and ArtMethodMangler remain available.\n"
    "globalThis.FRIDA_JAVA_BRIDGE_DISABLE_JVMTI = true;\n"
)
REPLACEMENTS = (
    (
        "function tryGetEnvJvmti(vm3, runtime4) {\n  let env3 = null;\n",
        "function tryGetEnvJvmti(vm3, runtime4) {\n  let env3 = null;\n"
        "  if (globalThis.FRIDA_JAVA_BRIDGE_DISABLE_JVMTI === true) {\n"
        "    return env3;\n  }\n",
    ),
    (
        '      if (offsetCopiedMethods === -1) {\n'
        '        throw new Error("Unable to find copied methods in java/lang/Thread; please file a bug");\n'
        '      }',
        '      if (offsetCopiedMethods === -1) {\n'
        '        // Zero is the class header, never a copied-methods field.\n'
        '        // Native class visitors use the full methods-array length.\n'
        '        offsetCopiedMethods = 0;\n'
        '      }',
    ),
    (
        '    elements = read_art_array (class_object, art_api.class_offset_methods, sizeof (gsize), NULL);\n'
        '    n = *(guint16 *) (class_object + art_api.class_offset_copied_methods_offset);',
        '    elements = read_art_array (class_object, art_api.class_offset_methods, sizeof (gsize), &n);\n'
        '    if (art_api.class_offset_copied_methods_offset != 0)\n'
        '      n = *(guint16 *) (class_object + art_api.class_offset_copied_methods_offset);',
    ),
    (
        '  elements = read_art_array (klass, art_api.class_offset_methods, sizeof (gsize), NULL);\n'
        '  n = *(guint16 *) ((gpointer) klass + art_api.class_offset_copied_methods_offset);',
        '  elements = read_art_array (klass, art_api.class_offset_methods, sizeof (gsize), &n);\n'
        '  if (art_api.class_offset_copied_methods_offset != 0)\n'
        '    n = *(guint16 *) ((gpointer) klass + art_api.class_offset_copied_methods_offset);',
    ),
)


def patch_agent(source):
    """Apply a complete patch or refuse an unexpected compiled upstream agent."""
    patched = source.startswith(PREFIX)
    for before, after in REPLACEMENTS:
        if patched:
            if source.count(after) != 1 or before in source.replace(after, "", 1):
                raise ValueError("Partially patched or changed Objection ART agent")
        else:
            if source.count(before) != 1 or after in source:
                raise ValueError("Unexpected Objection Java bridge patch site")
            source = source.replace(before, after, 1)
    return source if patched else PREFIX + source


def patch_common(common):
    agent = common / "python-site/objection/agent.js"
    agent.write_text(patch_agent(agent.read_text()))
