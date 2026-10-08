package android.database.sqlite;
public abstract class SQLiteOpenHelper {
    public static SQLiteDatabase shared;
    public static int requestedVersion;
    private boolean initialized;
    public SQLiteOpenHelper(android.content.Context context,String name,Object factory,int version) {
        requestedVersion=version;
        if (shared==null) shared=new SQLiteDatabase();
    }
    public SQLiteDatabase getWritableDatabase() { if(!initialized){onCreate(shared);initialized=true;}return shared; }
    public SQLiteDatabase getReadableDatabase() { return getWritableDatabase(); }
    public abstract void onCreate(SQLiteDatabase database);
    public abstract void onUpgrade(SQLiteDatabase database,int oldVersion,int newVersion);
}
