package com.mkei.backcast.tool;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An immutable snapshot of project roots; relative paths use the first root. */
final class WorkspaceRoots {
    private final String primary;
    private final List<File> roots;

    WorkspaceRoots(String primary, List<String> directories) {
        this.primary = primary;
        ArrayList<File> values = new ArrayList<File>();
        if (primary != null && primary.length() > 0) add(values, primary);
        if (directories != null) for (String directory : directories) add(values, directory);
        roots = Collections.unmodifiableList(values);
    }

    private static void add(List<File> values, String path) {
        if (path == null || path.length() == 0 || path.indexOf('\0') >= 0
                || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0 || !new File(path).isAbsolute()) {
            throw new IllegalArgumentException("工作目录必须是不含换行的绝对路径。");
        }
        try {
            File root = new File(path).getCanonicalFile();
            if (!values.contains(root)) values.add(root);
        } catch (IOException failure) { throw new IllegalArgumentException("无法确认工作目录：" + path, failure); }
    }

    boolean matches(String directory) {
        return primary == null ? directory == null || directory.length() == 0 : primary.equals(directory);
    }

    File resolve(String path) throws IOException {
        if (path == null || path.length() == 0 || path.indexOf('\0') >= 0
                || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) throw new IllegalArgumentException("路径不合法。");
        File file = new File(path);
        if (!file.isAbsolute() && !roots.isEmpty()) file = new File(roots.get(0), path);
        file = file.getCanonicalFile();
        if (!roots.isEmpty() && rootFor(file) == null) {
            throw new IllegalArgumentException("路径超出工作目录：" + path + "。允许目录：" + roots
                    + "。请在工作文件夹中添加该目录后再访问。");
        }
        return file;
    }

    File rootFor(File file) throws IOException {
        File best = null;
        for (File root : roots) {
            // A captured root replaced by a link cannot broaden the original authorization.
            if (!root.getPath().equals(root.getCanonicalPath())) continue;
            String base = root.getPath(), path = file.getCanonicalPath();
            if ((path.equals(base) || path.startsWith(base.endsWith("/") ? base : base + "/"))
                    && (best == null || base.length() > best.getPath().length())) best = root;
        }
        return best;
    }

    boolean isRoot(File file) throws IOException { return roots.contains(file.getCanonicalFile()); }

    List<File> directories() { return roots; }
}
