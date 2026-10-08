package android.database;
import org.json.JSONArray;
public final class Cursor {
    private final JSONArray rows;
    private int index = -1;
    public Cursor(JSONArray rows) { this.rows=rows; }
    public boolean moveToFirst() { index=0; return rows.length()>0; }
    public boolean moveToNext() { return ++index<rows.length(); }
    private Object value(int i) { return rows.getJSONArray(index).get(i); }
    public String getString(int i) { Object v=value(i); return v==org.json.JSONObject.NULL?null:v.toString(); }
    public long getLong(int i) { Object v=value(i); return v==org.json.JSONObject.NULL?0:((Number)v).longValue(); }
    public int getInt(int i) { return (int)getLong(i); }
    public boolean isNull(int i) { return value(i)==org.json.JSONObject.NULL; }
    public void close() { }
}
