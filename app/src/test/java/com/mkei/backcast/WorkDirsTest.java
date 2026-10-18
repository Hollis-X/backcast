package com.mkei.backcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.net.Uri;
import android.provider.DocumentsContract;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = android.app.Application.class)
public class WorkDirsTest {
    @Test
    public void treeUrisBecomeFileSystemPaths() {
        String authority = "com.android.externalstorage.documents";
        assertEquals("/storage/emulated/0/Download/项目",
                WorkDirs.fromTreeUri(DocumentsContract.buildTreeDocumentUri(authority, "primary:Download/项目")));
        assertEquals("/storage/emulated/0", WorkDirs.fromTreeUri(DocumentsContract.buildTreeDocumentUri(authority, "primary:")));
        assertEquals("/storage/1A2B-3C4D/DCIM", WorkDirs.fromDocumentId("1A2B-3C4D:DCIM/"));
        assertNull(WorkDirs.fromTreeUri(DocumentsContract.buildTreeDocumentUri("com.google.android.apps.docs.storage", "abc")));
        assertNull(WorkDirs.fromDocumentId("home:Documents"));
        assertNull(WorkDirs.fromTreeUri(Uri.parse("file:///sdcard")));
    }
}
