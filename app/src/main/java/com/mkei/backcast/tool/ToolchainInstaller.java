package com.mkei.backcast.tool;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Installs fixed official artifacts without running archive contents or installer scripts. */
public final class ToolchainInstaller {
    public interface Cancellation { void check() throws Exception; }
    interface Downloads { InputStream open(String url) throws Exception; }
    private static final long MAX_EXPANDED = 512L * 1024 * 1024;
    private final ToolchainStore store;
    private final Downloads downloads;
    private volatile InputStream active;

    public ToolchainInstaller(ToolchainStore store) { this(store, new HttpsDownloads()); }
    ToolchainInstaller(ToolchainStore store, Downloads downloads) { this.store = store; this.downloads = downloads; }

    public void abort() {
        InputStream input = active;
        if (input != null) try { input.close(); } catch (IOException ignored) { }
    }

    public File install(String id, String abi, Cancellation cancellation) throws Exception {
        if (store.bundled(id)) return store.prepareBundled(cancellation);
        return installArtifact(ToolCatalog.artifact(id, abi), cancellation);
    }

    File installArtifact(ToolCatalog.Artifact artifact, Cancellation cancellation) throws Exception {
        synchronized (store.toolLock(artifact.id)) {
            cancellation.check();
            File destination = store.managed(new File(store.root(), artifact.id + "-" + artifact.version + "-" + artifact.abi).getPath());
            File receipt = new File(destination, ".verified-sha256");
            store.managed(receipt.getPath());
            if (receipt.isFile() && artifact.sha256.equals(new String(ToolPaths.readBytes(receipt, 128, false), "UTF-8"))) {
                store.installed(artifact, destination); return destination;
            }
            if (destination.exists()) throw new IllegalArgumentException("工具版本目录已存在但校验记录不一致，请检查私有工具目录。");
            if (!store.root().isDirectory() && !store.root().mkdirs()) throw new IOException("无法创建私有工具目录。");
            File stage = store.managed(new File(store.root(), ".install-" + UUID.randomUUID()).getPath());
            if (!stage.mkdir()) throw new IOException("无法创建安装暂存目录。");
            File archive = new File(stage, ".download");
            File extracted = new File(stage, "payload");
            boolean published = false;
            try {
                download(artifact, archive, cancellation);
                cancellation.check();
                if (!extracted.mkdir()) throw new IOException("无法创建工具解包目录。");
                if ("jar".equals(artifact.format)) {
                    if (!archive.renameTo(new File(extracted, "apktool.jar"))) throw new IOException("无法保存 Apktool JAR。");
                } else {
                    InputStream input = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive)));
                    try { extractTar(input, extracted, artifact.prefix, cancellation); }
                    finally { input.close(); }
                    if (!new File(extracted, "bin/radare2").isFile() || !new File(extracted, "bin/rabin2").isFile()) {
                        throw new IOException("官方包缺少 radare2/rabin2 入口。");
                    }
                }
                ToolPaths.writeBytes(new File(extracted, ".verified-sha256"), artifact.sha256.getBytes("UTF-8"), false);
                cancellation.check(); store.managed(destination.getPath());
                if (!extracted.renameTo(destination)) throw new IOException("无法发布工具版本目录。");
                published = true;
                cancellation.check();
                store.installed(artifact, destination);
                return destination;
            } catch (Exception failure) {
                if (published) remove(destination, store.root());
                throw failure;
            } finally {
                active = null; remove(stage, store.root());
            }
        }
    }

    private void download(ToolCatalog.Artifact artifact, File destination, Cancellation cancellation) throws Exception {
        InputStream input = downloads.open(artifact.url); active = input;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileOutputStream output = new FileOutputStream(destination);
        long total = 0;
        try {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) >= 0) {
                cancellation.check(); if (count == 0) continue;
                total += count;
                if (total > artifact.maxBytes) throw new IOException("工具下载超过大小限制。");
                digest.update(buffer, 0, count); output.write(buffer, 0, count);
            }
            output.getFD().sync();
        } finally {
            try { input.close(); } finally { output.close(); active = null; }
        }
        if (!artifact.sha256.equals(hex(digest.digest()))) throw new IOException("SHA-256 校验失败，下载已丢弃。");
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) { int v = value & 255; if (v < 16) result.append('0'); result.append(Integer.toHexString(v)); }
        return result.toString();
    }

    private static final class HttpsDownloads implements Downloads {
        @Override public InputStream open(String address) throws Exception {
            URL url = new URL(address);
            for (int redirects = 0; redirects <= 5; redirects++) {
                String host = url.getHost();
                if (!"https".equals(url.getProtocol()) || !("github.com".equals(host)
                        || "release-assets.githubusercontent.com".equals(host) || "objects.githubusercontent.com".equals(host))) {
                    throw new IOException("工具下载只允许官方 HTTPS 发布源。");
                }
                final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false); connection.setRequestProperty("User-Agent", "Backcast-Toolchain");
                int code = connection.getResponseCode();
                if (code >= 300 && code <= 399) {
                    String location = connection.getHeaderField("Location"); connection.disconnect();
                    if (location == null) throw new IOException("下载重定向缺少地址。");
                    url = new URL(url, location); continue;
                }
                if (code != 200) { connection.disconnect(); throw new IOException("工具下载 HTTP " + code); }
                return new FilterInputStream(connection.getInputStream()) {
                    @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
                };
            }
            throw new IOException("工具下载重定向过多。");
        }
    }

    private static final class Link {
        final File destination, target;
        Link(File destination, File target) { this.destination = destination; this.target = target; }
    }

    /** Restricted USTAR reader: only files/directories and internal links; links become copies. */
    static void extractTar(InputStream input, File root, String prefix, Cancellation cancellation) throws Exception {
        byte[] header = new byte[512];
        List<Link> links = new ArrayList<Link>(); HashSet<String> files = new HashSet<String>();
        String longName = null, longLink = null; long expanded = 0; int entries = 0;
        while (true) {
            cancellation.check(); readFully(input, header, 512);
            boolean empty = true; for (byte value : header) if (value != 0) { empty = false; break; }
            if (empty) break;
            if (++entries > 20000) throw new IOException("归档条目过多。");
            long checksum = number(header, 148, 8), actual = 0;
            for (int i = 0; i < 512; i++) actual += i >= 148 && i < 156 ? 32 : header[i] & 255;
            if (actual != checksum) throw new IOException("归档头校验失败。");
            String name = text(header, 0, 100), link = text(header, 157, 100), pathPrefix = text(header, 345, 155);
            if (pathPrefix.length() > 0) name = pathPrefix + "/" + name;
            long length = number(header, 124, 12), mode = number(header, 100, 8);
            int type = header[156] & 255;
            expanded += length;
            if (expanded > MAX_EXPANDED) throw new IOException("归档展开大小超过限制。");
            if (type == 'L' || type == 'K') {
                if (length > 4096) throw new IOException("归档路径过长。");
                byte[] value = new byte[(int) length]; readFully(input, value, value.length);
                String path = text(value, 0, value.length);
                if (type == 'L') longName = path; else longLink = path;
                skipFully(input, (512 - length % 512) % 512); continue;
            }
            if (longName != null) { name = longName; longName = null; }
            if (longLink != null) { link = longLink; longLink = null; }
            if (name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0 || name.contains("../") || name.equals("..")) {
                throw new IOException("归档含越界路径。");
            }
            if (!name.startsWith(prefix)) { skipFully(input, length + (512 - length % 512) % 512); continue; }
            File output = child(root, name.substring(prefix.length()));
            if (type == '5') {
                if (!output.isDirectory() && !output.mkdirs()) throw new IOException("无法创建工具子目录。");
                skipFully(input, length);
            } else if (type == 0 || type == '0') {
                if (!files.add(output.getPath()) || output.exists()) throw new IOException("归档重复文件。");
                if (!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs()) throw new IOException("无法创建工具子目录。");
                FileOutputStream stream = new FileOutputStream(output);
                try { copy(input, stream, length, cancellation); } finally { stream.close(); }
                if ((mode & 0111) != 0 && !output.setExecutable(true, true)) throw new IOException("无法设置工具执行权限。");
            } else if (type == '1' || type == '2') {
                if (length != 0 || !files.add(output.getPath())) throw new IOException("归档链接不合法。");
                File target;
                if (type == '1') {
                    if (!link.startsWith(prefix)) throw new IOException("归档硬链接超出安装目录。");
                    target = child(root, link.substring(prefix.length()));
                } else {
                    if (new File(link).isAbsolute()) throw new IOException("归档符号链接必须是内部相对路径。");
                    target = new File(output.getParentFile(), link).getCanonicalFile();
                    if (!ToolchainStore.within(root.getCanonicalFile(), target)) throw new IOException("归档符号链接越界。");
                }
                links.add(new Link(output, target));
            } else throw new IOException("归档含不支持的特殊条目：" + type);
            skipFully(input, (512 - length % 512) % 512);
        }
        for (int attempts = links.size(); !links.isEmpty() && attempts-- >= 0;) {
            boolean progress = false;
            for (int i = links.size() - 1; i >= 0; i--) {
                Link link = links.get(i); cancellation.check();
                if (!link.target.exists()) continue;
                if (link.target.isDirectory()) {
                    if (!link.destination.mkdir()) throw new IOException("无法创建工具链接副本。");
                    copyTree(link.target, link.destination, root, cancellation, new long[]{0});
                } else {
                    FileInputStream source = new FileInputStream(link.target);
                    FileOutputStream target = new FileOutputStream(link.destination);
                    try { copy(source, target, link.target.length(), cancellation); }
                    finally { source.close(); target.close(); }
                    if (link.target.canExecute()) link.destination.setExecutable(true, true);
                }
                links.remove(i); progress = true;
            }
            if (!progress) throw new IOException("归档链接目标缺失或循环。");
        }
    }

    private static void copyTree(File source, File target, File root, Cancellation cancellation, long[] copied) throws Exception {
        File[] children = source.listFiles(); if (children == null) throw new IOException("无法读取工具链接目标。");
        for (File child : children) {
            cancellation.check(); File output = child(root, new File(target, child.getName()).getPath().substring(root.getPath().length()));
            if (child.isDirectory()) { if (!output.mkdir()) throw new IOException("无法创建工具链接副本。"); copyTree(child, output, root, cancellation, copied); }
            else {
                copied[0] += child.length(); if (copied[0] > MAX_EXPANDED) throw new IOException("工具链接副本超过大小限制。");
                FileInputStream input = new FileInputStream(child); FileOutputStream stream = new FileOutputStream(output);
                try { copy(input, stream, child.length(), cancellation); } finally { input.close(); stream.close(); }
                if (child.canExecute()) output.setExecutable(true, true);
            }
        }
    }

    private static File child(File root, String relative) throws Exception {
        while (relative.startsWith("/")) relative = relative.substring(1);
        File file = new File(root, relative).getCanonicalFile();
        if (!ToolchainStore.within(root.getCanonicalFile(), file)) throw new IOException("归档路径或链接越界。");
        return file;
    }

    private static String text(byte[] data, int start, int length) throws Exception {
        int end = start; while (end < start + length && data[end] != 0) end++;
        return new String(data, start, end - start, "UTF-8");
    }
    private static long number(byte[] bytes, int start, int length) throws Exception {
        String value = text(bytes, start, length).trim();
        if (value.length() == 0) return 0;
        if (!value.matches("[0-7]+")) throw new IOException("归档数字字段不合法。");
        long number = Long.parseLong(value, 8);
        if (number < 0 || number > MAX_EXPANDED) throw new IOException("归档数字字段超过限制。");
        return number;
    }
    private static void readFully(InputStream input, byte[] buffer, int length) throws IOException {
        int offset = 0; while (offset < length) { int count = input.read(buffer, offset, length - offset); if (count < 0) throw new IOException("归档被截断。"); offset += count; }
    }
    private static void skipFully(InputStream input, long length) throws IOException {
        byte[] buffer = new byte[8192];
        while (length > 0) { int count = input.read(buffer, 0, (int) Math.min(buffer.length, length)); if (count < 0) throw new IOException("归档被截断。"); length -= count; }
    }
    private static void copy(InputStream input, FileOutputStream output, long length, Cancellation cancellation) throws Exception {
        byte[] buffer = new byte[16384];
        while (length > 0) {
            cancellation.check(); int count = input.read(buffer, 0, (int) Math.min(buffer.length, length));
            if (count < 0) throw new IOException("归档被截断。"); output.write(buffer, 0, count); length -= count;
        }
    }
    static void remove(File file, File root) throws Exception {
        if (!ToolchainStore.within(root, file.getAbsoluteFile()) || !file.getAbsolutePath().equals(file.getCanonicalPath())) {
            throw new IOException("安装暂存路径被替换，拒绝删除。");
        }
        if (!file.exists()) return;
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child, root);
        if (!file.delete()) throw new IOException("无法清理安装暂存文件。");
    }
}
