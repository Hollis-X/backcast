package com.mkei.backcast.tool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Copies explicitly classified results; turn cleanup never owns the delivered copies. */
final class ToolkitExport {
    private ToolkitExport() { }

    static JSONObject copy(String workDir, TemporaryWorkspace temporary, ToolchainStore store,
            String sourcePath, String targetPath, String purpose, boolean overwrite,
            ToolchainInstaller.Cancellation cancellation) throws Exception {
        if (temporary == null) throw new IllegalArgumentException("当前没有临时材料管理器。");
        File source = temporary.resolveManaged(sourcePath);
        if (source == null || !source.exists() || temporary.isOwnershipMarker(source)) {
            throw new IllegalArgumentException("导出来源必须是本轮已登记的临时结果。");
        }
        File literal = new File(sourcePath).getAbsoluteFile();
        if (!literal.getPath().equals(literal.getCanonicalPath())) throw new IllegalArgumentException("导出不能跟随来源符号链接。");
        File target = ToolPaths.resolve(workDir, targetPath, temporary);
        File base = workDir == null ? null : new File(workDir).getCanonicalFile();
        File absoluteTarget = new File(targetPath);
        if (!absoluteTarget.isAbsolute() && base != null) absoluteTarget = new File(base, targetPath);
        if (!absoluteTarget.getAbsolutePath().equals(absoluteTarget.getCanonicalPath())) throw new IllegalArgumentException("导出不能跟随目标符号链接。");
        if (temporary.contains(target) || temporary.isPrivateStorage(target) || ToolchainStore.within(store.root(), target)
                || ToolPaths.projectRoot(workDir, target, temporary)) throw new IllegalArgumentException("导出目标不能是临时、私有工具目录或整个项目根目录。");
        if (!"deliverable".equals(purpose) && !"test".equals(purpose)) throw new IllegalArgumentException("导出必须明确 purpose=deliverable 或 test。");
        if ("test".equals(purpose) && !ToolPaths.organizedTest(workDir, target, temporary)) {
            throw new IllegalArgumentException("正式测试请导出到项目已有测试目录或 tests/。");
        }
        List<File> created = new ArrayList<File>(); long[] size = new long[]{0, 0};
        try {
            transfer(source, target, overwrite, cancellation, created, size);
            return new JSONObject().put("source", source.getPath()).put("target", target.getPath())
                    .put("purpose", purpose).put("files", size[1]).put("bytes", size[0]);
        } catch (Exception failure) {
            for (int i = created.size() - 1; i >= 0; i--) {
                File file = created.get(i);
                if (file.getAbsolutePath().equals(file.getCanonicalPath())) file.delete();
            }
            throw failure;
        }
    }

    private static void transfer(File source, File target, boolean overwrite,
            ToolchainInstaller.Cancellation cancellation, List<File> created, long[] size) throws Exception {
        cancellation.check();
        if (!source.getAbsolutePath().equals(source.getCanonicalPath()) || !target.getAbsolutePath().equals(target.getCanonicalPath())) {
            throw new IOException("导出不能跟随符号链接。");
        }
        if (".backcast-owner".equals(source.getName())) throw new IOException("不能导出临时所有权标记。");
        if (source.isDirectory()) {
            if (target.exists() && (!target.isDirectory() || !overwrite)) throw new IOException("导出目标已存在；覆盖必须明确 overwrite=true。");
            makeParents(target, created);
            File[] children = source.listFiles(); if (children == null) throw new IOException("无法读取导出目录。");
            for (File child : children) transfer(child, new File(target, child.getName()), overwrite, cancellation, created, size);
        } else {
            if (!source.isFile()) throw new IOException("导出来源不是普通文件。");
            if (target.exists() && (!target.isFile() || !overwrite)) throw new IOException("导出目标已存在；覆盖必须明确 overwrite=true。");
            makeParents(target.getParentFile(), created);
            boolean existed = target.exists();
            if (!existed) created.add(target);
            size[0] += source.length(); size[1]++;
            if (size[0] > 512L * 1024 * 1024 || size[1] > 20000) throw new IOException("导出超过大小或文件数量限制。");
            File draft = new File(target.getParentFile(), ".backcast-export-" + java.util.UUID.randomUUID() + ".tmp");
            try {
                FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(draft);
                try {
                    byte[] bytes = new byte[16384]; int count;
                    while ((count = input.read(bytes)) >= 0) { cancellation.check(); if (count > 0) output.write(bytes, 0, count); }
                    output.getFD().sync();
                } finally { input.close(); output.close(); }
                cancellation.check();
                if (!target.getAbsolutePath().equals(target.getCanonicalPath())) throw new IOException("导出目标被链接替换。");
                if (!draft.renameTo(target)) throw new IOException("无法发布导出结果。");
            } finally { draft.delete(); }
        }
    }

    private static void makeParents(File directory, List<File> created) throws Exception {
        if (directory == null || directory.isDirectory()) return;
        if (!directory.getAbsolutePath().equals(directory.getCanonicalPath())) throw new IOException("导出目标父目录是符号链接。");
        makeParents(directory.getParentFile(), created);
        if (!directory.mkdir()) throw new IOException("无法创建导出目录。");
        created.add(directory);
    }
}
