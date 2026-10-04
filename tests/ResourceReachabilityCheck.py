"""Check application resources through Java, manifest and XML references."""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET


def references(text):
    return set(re.findall(r"@\+?(?!android:)([\w]+)/([\w.]+)", text))


def unused_resources(root):
    definitions, edges = {}, {}
    for path in (root / "app/src/main/res").rglob("*"):
        if not path.is_file():
            continue
        kind = path.parent.name.split("-")[0]
        if kind == "values":
            for element in ET.parse(path).getroot():
                name = element.get("name")
                resource_type = element.get("type") if element.tag == "item" else element.tag
                if not name:
                    continue
                if resource_type.endswith("-array"):
                    resource_type = "array"
                key = (resource_type, name)
                definitions.setdefault(key, []).append(path)
                links = edges.setdefault(key, set())
                links.update(references(ET.tostring(element, encoding="unicode")))
                if element.tag == "style":
                    parent = element.get("parent", "")
                    if parent and not parent.startswith(("@", "android:")):
                        links.add(("style", parent))
                    elif not parent and "." in name:
                        links.add(("style", name.rsplit(".", 1)[0]))
        else:
            name = path.stem.removesuffix(".9")
            key = (kind, name)
            definitions.setdefault(key, []).append(path)
            if path.suffix == ".xml":
                edges.setdefault(key, set()).update(references(path.read_text()))
    roots = references((root / "app/src/main/AndroidManifest.xml").read_text())
    for path in (root / "app/src/main/java").rglob("*.java"):
        roots.update(re.findall(r"(?<![\w.])R\.([\w]+)\.([\w]+)", path.read_text()))
    reached, pending = set(), list(roots)
    while pending:
        key = pending.pop()
        if key not in reached:
            reached.add(key)
            pending.extend(edges.get(key, ()))
    return {key: definitions[key] for key in sorted(definitions.keys() - reached)}


if __name__ == "__main__":
    project = pathlib.Path(sys.argv[1]).resolve()
    unused = unused_resources(project)
    if "--list-json" in sys.argv[2:]:
        print(json.dumps({"/".join(key): [str(path) for path in paths]
                          for key, paths in unused.items()}))
    elif unused:
        for key, paths in unused.items():
            print("UNUSED", "/".join(key), *(str(path.relative_to(project)) for path in paths))
        raise SystemExit(1)
    else:
        print("PASS all application resources are reachable from Java or manifest through XML")
