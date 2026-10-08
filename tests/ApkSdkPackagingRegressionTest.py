#!/usr/bin/env python3
"""Check actual APK DEX budgets and official SDK classes after a full Android build."""
import argparse
import json
import pathlib
import struct
import subprocess
import zipfile


def uint(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def class_names(data):
    string_offsets = uint(data, 60)
    type_offsets = uint(data, 68)
    class_count, class_offset = uint(data, 96), uint(data, 100)
    result = set()
    for index in range(class_count):
        type_index = uint(data, class_offset + index * 32)
        string_index = uint(data, type_offsets + type_index * 4)
        cursor = uint(data, string_offsets + string_index * 4)
        while data[cursor] & 0x80:
            cursor += 1
        cursor += 1
        # Java/SDK class descriptors are ASCII; other names are irrelevant here.
        result.add(data[cursor:data.index(b"\0", cursor)].decode("ascii", errors="replace"))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--aapt", required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as archive:
        assert archive.testzip() is None, "APK ZIP CRC failure"
        overhead = pathlib.Path(args.apk).stat().st_size - sum(item.compress_size for item in archive.infolist())
        assert overhead < 4 * 1024 * 1024, "APK retains large unreachable data from incremental packaging"
        assert "resources.arsc" in archive.namelist(), "Compiled Android resources missing"
        dex_files = sorted(name for name in archive.namelist()
                           if name.startswith("classes") and name.endswith(".dex") and "/" not in name)
        assert dex_files, "No application DEX files"
        classes = set()
        for name in dex_files:
            data = archive.read(name)
            assert data[:4] == b"dex\n", "Invalid DEX header: " + name
            fields, methods = uint(data, 80), uint(data, 88)
            assert fields <= 65536 and methods <= 65536, "DEX reference overflow: " + name
            names = class_names(data)
            assert not classes.intersection(names), "Duplicate classes across application DEX files"
            classes.update(names)
            print("PASS", name, "fields=", fields, "methods=", methods, "classes=", len(names))
        required = {
            "Lcom/mkei/backcast/GlobalApplication;",
            "Lcom/mkei/backcast/agent/LlmClient;",
            "Lcom/mkei/backcast/agent/NetworkRouting;",
            "Lcom/mkei/backcast/agent/ConnectionRace;",
            "Lcom/mkei/backcast/agent/InternetReachability;",
            "Lcom/mkei/backcast/net/DeviceNetworks;",
            "Lcom/mkei/backcast/ui/MarkdownRenderQueue;",
            "Lcom/mkei/backcast/SQLiteSubAgentStore;",
            "Lcom/mkei/backcast/tool/ToolchainDownloader;",
            "Lcom/mkei/backcast/tool/ObjectionBootstrap;",
            "Lcom/mkei/backcast/tool/FindFilesTool;",
            "Lcom/mkei/backcast/tool/RootShell;",
            "Lcom/mkei/backcast/McpConfigActivity;",
            "Lcom/mkei/backcast/mcp/McpClient;",
            "Lcom/mkei/backcast/mcp/McpServer;",
            "Lcom/mkei/backcast/mcp/McpStore;",
            "Lcom/mkei/backcast/mcp/McpToolInfo;",
            "Lcom/mkei/backcast/mcp/McpTools;",
            "Lcom/mkei/backcast/mcp/McpCatalog;",
            "Lcom/mkei/backcast/mcp/McpSelection;",
            "Lcom/mkei/backcast/ui/SlashMenuPopup;",
            "Lcom/mkei/backcast/ui/McpToolPicker;",
            "Lcom/mkei/backcast/ui/MessageActions;",
            "Lcom/openai/client/okhttp/OpenAIOkHttpClient;",
            "Lcom/openai/models/chat/completions/ChatCompletionChunk;",
            "Lcom/openai/services/blocking/chat/ChatCompletionServiceImpl;",
            "Lokhttp3/OkHttpClient;",
            "Lokio/BufferedSource;",
            "Lcom/fasterxml/jackson/databind/ObjectMapper;",
            "Lkotlin/jvm/internal/Intrinsics;",
        }
        assert required.issubset(classes), "SDK/runtime classes missing: " + str(required - classes)
        assert "Lcom/mkei/backcast/agent/LlmClient$RequestActivity;" not in classes, "Removed header timing bridge still packaged"
        assert "Lcom/mkei/backcast/agent/FileSubAgentStore;" not in classes, "Removed file checkpoint store still packaged"
        print("PASS official SDK, transport, JSON/Kotlin runtime and application classes packaged")
        manifest = json.loads(archive.read("assets/toolchain/manifest.json"))
        assert not any(name.startswith("assets/toolchain/") and name.endswith((".tar.gz", ".tar", ".zip"))
                       for name in archive.namelist()), "Tool payloads must be downloaded, not packaged in the APK"
        for artifact in manifest["artifacts"]:
            assert artifact["url"] == ("https://github.com/Hollis-X/backcast/releases/download/toolchain-"
                                        + manifest["version"] + "/" + artifact["file"]), "Unpinned tool release URL"
            assert len(artifact["sha256"]) == 64 and len(artifact["prefix_sha256"]) == 64, "Missing package hashes"
            assert len(artifact["chunk_sha256"]) == (artifact["bytes"] + artifact["chunk_bytes"] - 1) // artifact["chunk_bytes"], "Missing resumable chunk hashes"
        assert any(name.startswith("assets/toolchain/licenses/") for name in archive.namelist()), "Tool licenses missing"
        print("PASS tool release manifest and licenses retained; all tool payloads excluded from APK")
    manifest = subprocess.run([args.aapt, "dump", "badging", args.apk],
                              check=True, capture_output=True, text=True).stdout
    assert "sdkVersion:'26'" in manifest, "APK minimum API is not Android 8"
    assert "package: name='com.mkei.backcast'" in manifest, "Wrong application ID"
    for permission in ("android.permission.ACCESS_NETWORK_STATE", "android.permission.CHANGE_NETWORK_STATE"):
        assert permission in manifest, "Multi-network permission missing: " + permission
    print("PASS actual compiled manifest uses Android 8 and the existing application ID")


if __name__ == "__main__":
    main()
