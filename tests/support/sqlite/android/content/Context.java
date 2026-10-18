package android.content;
public final class Context {
    public Context getApplicationContext() { return this; }
    /** 测试用 backcast.files.dir 指定 App 私有目录。 */
    public java.io.File getFilesDir() { return new java.io.File(System.getProperty("backcast.files.dir")); }
}
