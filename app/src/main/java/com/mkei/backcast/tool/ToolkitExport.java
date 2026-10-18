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
            String sourcePath, String targetPath, String purpose, boolean overwrite, boolean useRoot,
            ToolchainInstaller.Cancellation cancellation) throws Exception {
        if (temporary == null) throw new IllegalArgumentException("当前没有临时材料管理器。");
        boolean root = useRoot && RootShell.available(cancellation);
        File source = temporary.resolveManaged(sourcePath);
        if (source == null || !source.exists() || temporary.isOwnershipMarker(source)) {
            throw new IllegalArgumentException("导出来源必须是本轮已登记的临时结果。");
        }
        File literal = new File(sourcePath).getAbsoluteFile();
        if (!literal.getPath().equals(literal.getCanonicalPath())) throw new IllegalArgumentException("导出不能跟随来源符号链接。");
        File target = ToolPaths.resolve(workDir, targetPath, temporary, root, cancellation);
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
            transfer(source, target, overwrite, root, cancellation, created, size);
            return new JSONObject().put("source", source.getPath()).put("target", target.getPath())
                    .put("purpose", purpose).put("files", size[1]).put("bytes", size[0]);
        } catch (Exception failure) {
            for (int i = created.size() - 1; i >= 0; i--) {
                File file = created.get(i);
                try {
                    if (file.getAbsolutePath().equals(file.getCanonicalPath())) {
                        if (root) rootDelete(file); else file.delete();
                    }
                } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private static void transfer(File source, File target, boolean overwrite, boolean root,
            ToolchainInstaller.Cancellation cancellation, List<File> created, long[] size) throws Exception {
        cancellation.check();
        if (!source.getAbsolutePath().equals(source.getCanonicalPath()) || !target.getAbsolutePath().equals(target.getCanonicalPath())) {
            throw new IOException("导出不能跟随符号链接。");
        }
        if (".backcast-owner".equals(source.getName())) throw new IOException("不能导出临时所有权标记。");
        ToolPaths.Probe destination = ToolPaths.probe(target, root, cancellation);
        if (destination.denied) throw new IOException("无法确认导出目标状态。");
        if (source.isDirectory()) {
            if (destination.exists && (!destination.directory || !overwrite)) throw new IOException("导出目标已存在；覆盖必须明确 overwrite=true。");
            makeParents(target, root, cancellation, created);
            File[] children = source.listFiles(); if (children == null) throw new IOException("无法读取导出目录。");
            for (File child : children) transfer(child, new File(target, child.getName()), overwrite, root, cancellation, created, size);
        } else {
            if (!source.isFile()) throw new IOException("导出来源不是普通文件。");
            if (destination.exists && (destination.directory || !root && !target.isFile() || !overwrite)) throw new IOException("导出目标已存在；覆盖必须明确 overwrite=true。");
            makeParents(target.getParentFile(), root, cancellation, created);
            boolean existed = destination.exists;
            if (!existed) created.add(target);
            size[0] += source.length(); size[1]++;
            if (size[0] > 512L * 1024 * 1024 || size[1] > 20000) throw new IOException("导出超过大小或文件数量限制。");
            File draft = new File(target.getParentFile(), ".backcast-export-" + java.util.UUID.randomUUID() + ".tmp");
            boolean published = false;
            try {
                if (root) {
                    rootRun(ToolPaths.rootPathGuard(source) + ToolPaths.rootPathGuard(draft)
                            + "(set -C; cat " + RootShell.quote(source.getPath()) + " > " + RootShell.quote(draft.getPath()) + ")", cancellation);
                    cancellation.check();
                    rootRun(ToolPaths.rootPathGuard(target) + ToolPaths.rootPathGuard(draft)
                            + (overwrite ? "[ ! -e " + RootShell.quote(target.getPath()) + " ] || [ -f " + RootShell.quote(target.getPath()) + " ] || exit 1; "
                                         : "[ ! -e " + RootShell.quote(target.getPath()) + " ] || exit 1; ")
                            + "mv -f " + RootShell.quote(draft.getPath()) + " " + RootShell.quote(target.getPath()), cancellation);
                    published = true;
                    return;
                }
                FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(draft);
                try {
                    byte[] bytes = new byte[16384]; int count;
                    while ((count = input.read(bytes)) >= 0) { cancellation.check(); if (count > 0) output.write(bytes, 0, count); }
                    output.getFD().sync();
                } finally { input.close(); output.close(); }
                cancellation.check();
                if (!target.getAbsolutePath().equals(target.getCanonicalPath())) throw new IOException("导出目标被链接替换。");
                if (!draft.renameTo(target)) throw new IOException("无法发布导出结果。");
            } finally { if (root) { if (!published) rootDelete(draft); } else draft.delete(); }
        }
    }

    private static void makeParents(File directory, boolean root, ToolchainInstaller.Cancellation cancellation, List<File> created) throws Exception {
        if (directory == null || ToolPaths.probe(directory, root, cancellation).directory) return;
        if (!directory.getAbsolutePath().equals(directory.getCanonicalPath())) throw new IOException("导出目标父目录是符号链接。");
        makeParents(directory.getParentFile(), root, cancellation, created);
        if (root) rootRun(ToolPaths.rootPathGuard(directory) + "mkdir " + RootShell.quote(directory.getPath()), cancellation);
        else if (!directory.mkdir()) throw new IOException("无法创建导出目录。");
        created.add(directory);
    }

    private static void rootRun(String command, ToolchainInstaller.Cancellation cancellation) throws Exception {
        RootShell.Out out = RootShell.exec(command, null, 2048, 60000, cancellation);
        if (out.exit != 0) throw new IOException("root 导出失败，exit=" + out.exit + "：" + out.stderr);
    }

    private static void rootDelete(File file) throws Exception {
        try (CleanupBudget budget = new CleanupBudget(System.nanoTime() + CleanupBudget.DEFAULT_NANOS)) {
            rootRun(ToolPaths.rootPathGuard(file) + "if [ -d " + RootShell.quote(file.getPath()) + " ]; then rmdir "
                    + RootShell.quote(file.getPath()) + "; else rm -f " + RootShell.quote(file.getPath()) + "; fi", budget);
        }
    }
}
