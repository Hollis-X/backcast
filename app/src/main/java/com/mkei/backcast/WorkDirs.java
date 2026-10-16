package com.mkei.backcast;

import android.net.Uri;
import android.provider.DocumentsContract;

/** 系统文件夹选择器返回的是 content:// 树地址，工具要的是文件系统路径，这里换算。 */
final class WorkDirs {
    private static final String EXTERNAL = "com.android.externalstorage.documents";

    private WorkDirs() { }

    /** 只认本机存储：primary:Download/x → /storage/emulated/0/Download/x，SD 卡 ABCD-1234:x → /storage/ABCD-1234/x。 */
    static String fromTreeUri(Uri tree) {
        if (tree == null || !EXTERNAL.equals(tree.getAuthority())) return null;
        String id;
        try { id = DocumentsContract.getTreeDocumentId(tree); }
        catch (IllegalArgumentException invalid) { return null; }
        return fromDocumentId(id);
    }

    static String fromDocumentId(String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        if (colon <= 0) return null;
        String volume = id.substring(0, colon), path = id.substring(colon + 1);
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String base = "primary".equalsIgnoreCase(volume) ? Settings.DEFAULT_WORK_DIR
                : volume.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}") ? "/storage/" + volume : null;
        if (base == null) return null;
        return path.length() == 0 ? base : base + "/" + path;
    }
}
