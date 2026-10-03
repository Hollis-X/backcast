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
BRIDGE_VERSION = "7.0.13-backcast.2"
PATCH_DESCRIPTION = (
    "Objection Java bridge: use JNI/ART fallback without optional JVMTI plugin "
    "loading; tolerate unknown copied-methods offset (upstream PR #407 backport); "
    "preserve Frida bundle headers and UTF-8 module byte lengths"
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


PACKAGE_MARKER = "📦\n".encode()
DELIMITER = "\n✄\n".encode()


def parse_bundle(source):
    """Read Frida's size-delimited ES modules, including aliases/entrypoints.

    The official parser counts UTF-8 bytes, not Python characters:
    https://github.com/frida/frida-gum/blob/17.2.14/bindings/gumjs/gumquickscriptbackend.c
    Keep sources opaque: a delimiter inside a module is ordinary source text.
    """
    data = source.encode()
    if not data.startswith(PACKAGE_MARKER) or b"\0" in data:
        raise ValueError("Unexpected Frida bundle header")
    cursor = len(PACKAGE_MARKER)
    groups, names = [], set()
    while cursor < len(data):
        end = data.find(DELIMITER, cursor)
        if end == -1:
            raise ValueError("Missing Frida bundle module separator")
        headers = data[cursor:end].decode().split("\n")
        declarations = []
        for header in headers:
            if header.startswith("↻ "):
                if not declarations or len(header) <= 2:
                    raise ValueError("Invalid Frida bundle module alias")
                name = header[2:]
                declarations[-1]["aliases"].append(name)
            else:
                count, separator, name = header.partition(" ")
                if not separator or not count.isascii() or not count.isdigit() or not name or int(count) <= 0:
                    raise ValueError("Invalid Frida bundle module declaration")
                declarations.append({"name": name, "aliases": [], "bytes": int(count)})
            if name in names:
                raise ValueError("Duplicate Frida bundle module name")
            names.add(name)
        if not declarations or not any(item["name"].endswith(".js") for item in declarations):
            raise ValueError("Missing Frida bundle JavaScript entrypoint")
        cursor = end
        for item in declarations:
            if not data.startswith(DELIMITER, cursor):
                raise ValueError("Invalid Frida bundle module boundary")
            cursor += len(DELIMITER)
            boundary = cursor + item.pop("bytes")
            if boundary > len(data):
                raise ValueError("Truncated Frida bundle module")
            item["source"] = data[cursor:boundary].decode()
            cursor = boundary
        groups.append(declarations)
        if cursor != len(data):
            if not data.startswith(DELIMITER, cursor):
                raise ValueError("Stale Frida bundle module byte length")
            cursor += len(DELIMITER)
    return groups


def format_bundle(groups):
    packages = []
    for group in groups:
        headers = []
        for item in group:
            headers.append(str(len(item["source"].encode())) + " " + item["name"])
            headers.extend("↻ " + alias for alias in item["aliases"])
        packages.append("\n".join(headers) + DELIMITER.decode()
                        + DELIMITER.decode().join(item["source"] for item in group))
    result = PACKAGE_MARKER.decode() + DELIMITER.decode().join(packages)
    parse_bundle(result)
    return result


def recover_legacy_bundle(source):
    """Only repair the exact single-module payload our previous patch shipped.

    That patch added a prefix outside the bundle and changed the module without
    updating its declaration. Its exact replacement delta authenticates the
    stale length; arbitrary malformed upstream bundles remain rejected.
    """
    data = source.encode()
    end = data.find(DELIMITER, len(PACKAGE_MARKER))
    if end == -1 or not data.startswith(PACKAGE_MARKER):
        raise ValueError("Invalid legacy Objection bundle")
    header = data[len(PACKAGE_MARKER):end].decode()
    count, separator, name = header.partition(" ")
    body = data[end + len(DELIMITER):].decode()
    delta = sum(len(after.encode()) - len(before.encode()) for before, after in REPLACEMENTS)
    if (not separator or not count.isascii() or not count.isdigit() or name != "/src/index.js"
            or DELIMITER in body.encode() or len(body.encode()) != int(count) + delta):
        raise ValueError("Unexpected legacy Objection bundle byte length")
    return [[{"name": name, "aliases": [], "source": body}]]


def patch_module(source, previously_patched=False):
    patched = previously_patched or source.startswith(PREFIX)
    if source.startswith(PREFIX):
        source = source[len(PREFIX):]
    if PREFIX in source:
        raise ValueError("Unexpected Objection compatibility prefix location")
    for before, after in REPLACEMENTS:
        if patched:
            if source.count(after) != 1 or before in source.replace(after, "", 1):
                raise ValueError("Partially patched or changed Objection ART agent")
        else:
            if source.count(before) != 1 or after in source:
                raise ValueError("Unexpected Objection Java bridge patch site")
            source = source.replace(before, after, 1)
    return PREFIX + source


def patch_agent(source):
    """Patch the Java bridge inside its module; never precede the 📦 header."""
    legacy = source.startswith(PREFIX)
    if legacy:
        source = source[len(PREFIX):]
    if not source.startswith(PACKAGE_MARKER.decode()):
        return patch_module(source, legacy)
    try:
        groups = parse_bundle(source)
    except ValueError:
        if not legacy:
            raise
        groups = recover_legacy_bundle(source)
    targets = [item for group in groups for item in group
               if "function tryGetEnvJvmti(" in item["source"]]
    if len(targets) != 1:
        raise ValueError("Unexpected Objection Java bridge module")
    targets[0]["source"] = patch_module(targets[0]["source"], legacy)
    return format_bundle(groups)


def patch_common(common):
    agent = common / "python-site/objection/agent.js"
    agent.write_text(patch_agent(agent.read_text()))
