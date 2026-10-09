package com.mkei.backcast.tool;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable access grants and task focus; relative paths use the first focus root. */
final class WorkspaceRoots {
    private final String primary;
    private final List<File> roots;
    private final List<File> focus;
    private final File restrictedPrimary;
    private final List<File> attached;

    WorkspaceRoots(String primary, List<String> directories) {
        this.primary = primary;
        ArrayList<File> values = new ArrayList<File>();
        if (primary != null && primary.length() > 0) add(values, primary);
        if (directories != null) for (String directory : directories) add(values, directory);
        roots = Collections.unmodifiableList(values);
        focus = roots;
        restrictedPrimary = null;
        attached = roots.size() < 2 ? Collections.<File>emptyList() : roots.subList(1, roots.size());
    }

    private WorkspaceRoots(String primary, List<File> captured, List<File> focused,
            File restrictedPrimary, List<File> attached) {
        this.primary = primary;
        roots = Collections.unmodifiableList(new ArrayList<File>(captured));
        focus = Collections.unmodifiableList(new ArrayList<File>(focused));
        this.restrictedPrimary = restrictedPrimary;
        this.attached = attached;
    }

    String primary() { return primary; }

    /** Narrow the primary project, while retaining explicitly attached access grants. */
    WorkspaceRoots forTask(List<String> paths) {
        ArrayList<File> selected = new ArrayList<File>();
        if (paths.isEmpty()) {
            if (!roots.isEmpty()) selected.add(roots.get(0));
        } else for (String path : paths) {
            try {
                File spelled = new File(alias(path)).toPath().toAbsolutePath().normalize().toFile();
                File requested;
                try { requested = resolve(alias(path)); }
                catch (IOException inaccessible) {
                    // Root-aware file tools will verify the real path themselves.
                    // Lack of Java traversal permission is not an authorization denial.
                    boolean inside = false;
                    for (File root : roots) {
                        String base = root.getPath(), value = spelled.getPath();
                        if (value.equals(base) || value.startsWith(base.endsWith("/") ? base : base + "/")) inside = true;
                    }
                    if (!inside) continue;
                    requested = spelled;
                }
                // Reusing a human task path after its directory was replaced by a
                // symlink must not silently authorize the new target next turn.
                if (!spelled.equals(requested)) continue;
                if (requested.isFile()) requested = requested.getParentFile();
                if (requested != null && !selected.contains(requested)) selected.add(requested);
            } catch (IllegalArgumentException rejected) {
                // An unauthorized task path cannot become a default search root.
            }
        }
        // Attached folders are access grants, not implicit search targets. A task
        // path can narrow the main project without silently revoking those grants.
        ArrayList<File> allowed = new ArrayList<File>(selected);
        for (int i = 1; i < roots.size(); i++) if (!allowed.contains(roots.get(i))) allowed.add(roots.get(i));
        // Failed task resolution keeps the default focus empty; relative commands
        // must not fall back to an attached folder or the broad configured root.
        return new WorkspaceRoots(primary, allowed, selected, roots.isEmpty() ? null : roots.get(0), attached);
    }

    private String alias(String path) {
        if (path != null && (path.equals("/sdcard") || path.startsWith("/sdcard/"))) {
            for (File root : roots) if (root.getPath().equals("/storage/emulated/0")
                    || root.getPath().startsWith("/storage/emulated/0/")) return "/storage/emulated/0" + path.substring(7);
        }
        return path;
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
        File file = lexicalPath(path).getCanonicalFile();
        if (rootFor(file) == null) {
            throw new IllegalArgumentException("路径超出工作目录：" + path + "。允许目录：" + roots
                    + "。任务默认目录：" + focus + "。请在工作文件夹中添加该目录后再访问。");
        }
        return file;
    }

    File lexicalPath(String path) {
        if (path == null || path.length() == 0 || path.indexOf('\0') >= 0
                || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) throw new IllegalArgumentException("路径不合法。");
        File file = new File(alias(path));
        if (!file.isAbsolute()) {
            if (focus.isEmpty()) throw new IllegalArgumentException("本轮指定路径不在已授权工作目录内，不能解析相对路径。");
            file = new File(focus.get(0), path);
        }
        return file.toPath().toAbsolutePath().normalize().toFile();
    }

    File rootFor(File file) throws IOException {
        return matchingRoot(file.getCanonicalFile(), true);
    }

    /** Used only after root has resolved the actual path when Java traversal fails. */
    File lexicalRootFor(File file) throws IOException {
        return matchingRoot(file.toPath().toAbsolutePath().normalize().toFile(), false);
    }

    private File matchingRoot(File file, boolean canonical) throws IOException {
        if (restrictedPrimary != null && inside(restrictedPrimary, file)) {
            boolean primaryAllowed = false;
            for (File root : focus) if (inside(root, file)) primaryAllowed = true;
            // A separately granted child of the main project remains accessible;
            // an attached ancestor cannot undo a narrower main-project task.
            for (File root : attached) if (inside(restrictedPrimary, root) && inside(root, file)) primaryAllowed = true;
            if (!primaryAllowed) return null;
        }
        File best = null;
        for (File root : roots) {
            // A captured root replaced by a link cannot broaden the original authorization.
            if (canonical && !root.getPath().equals(root.getCanonicalPath())) continue;
            if (inside(root, file) && (best == null || root.getPath().length() > best.getPath().length())) best = root;
        }
        return best;
    }

    private static boolean inside(File root, File file) {
        String base = root.getPath(), value = file.getPath();
        return value.equals(base) || value.startsWith(base.endsWith("/") ? base : base + "/");
    }

    boolean isRoot(File file) throws IOException { return roots.contains(file.getCanonicalFile()); }

    List<File> directories() { return roots; }
    List<File> focusDirectories() { return focus; }
}
