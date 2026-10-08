package android.database.sqlite;
import android.content.ContentValues;
import android.database.Cursor;
import java.io.*;
import java.util.*;
import org.json.*;

/** Android API fixture backed by a live Python sqlite3 connection and real transactions. */
public final class SQLiteDatabase {
    public static final int CONFLICT_REPLACE=5,CONFLICT_IGNORE=4;
    public static int failRecordWrite;
    public static String refuseInsertTable;
    private final Process bridge;
    private final BufferedWriter input;
    private final BufferedReader output;
    private boolean successful;
    public SQLiteDatabase() {
        try {
            bridge=new ProcessBuilder("python3","-u",System.getProperty("backcast.sqlite.bridge")).start();
            input=new BufferedWriter(new OutputStreamWriter(bridge.getOutputStream(),"UTF-8"));
            output=new BufferedReader(new InputStreamReader(bridge.getInputStream(),"UTF-8"));
        } catch(Exception failure){throw new IllegalStateException(failure);}
    }
    private synchronized JSONObject execute(String sql,Object[] args) {
        try {
            input.write(new JSONObject().put("sql",sql).put("args",new JSONArray(args==null?new Object[0]:args)).toString());
            input.newLine();input.flush();
            String line=output.readLine();if(line==null)throw new IllegalStateException("SQLite bridge ended");
            JSONObject result=new JSONObject(line);
            if(result.has("error"))throw new IllegalStateException(result.getString("error"));
            return result;
        }catch(IOException failure){throw new IllegalStateException(failure);}
    }
    public void execSQL(String sql){execute(sql,null);}
    public void execSQL(String sql,Object[] args){execute(sql,args);}
    public void beginTransaction(){execute("BEGIN IMMEDIATE",null);successful=false;}
    public void setTransactionSuccessful(){successful=true;}
    public void endTransaction(){execute(successful?"COMMIT":"ROLLBACK",null);}
    public long insert(String table,String nullable,ContentValues values){return insertWithOnConflict(table,nullable,values,0);}
    public long insertWithOnConflict(String table,String nullable,ContentValues values,int conflict){
        if(table.equals(refuseInsertTable)){refuseInsertTable=null;return -1;}
        if(table.equals("sub_agent_records")&&failRecordWrite>0&&--failRecordWrite==0)throw new IllegalStateException("disk full fixture");
        StringBuilder names=new StringBuilder(),placeholders=new StringBuilder();List<Object> arguments=new ArrayList<Object>();
        for(Map.Entry<String,Object> value:values.entrySet()){
            if(names.length()>0){names.append(',');placeholders.append(',');}
            names.append(value.getKey());placeholders.append('?');arguments.add(value.getValue());
        }
        return execute("INSERT "+(conflict==CONFLICT_REPLACE?"OR REPLACE ":conflict==CONFLICT_IGNORE?"OR IGNORE ":"")
                +"INTO "+table+" ("+names+") VALUES ("+placeholders+")",arguments.toArray()).getLong("id");
    }
    public int update(String table,ContentValues values,String where,String[] args){
        StringBuilder setters=new StringBuilder();List<Object> arguments=new ArrayList<Object>();
        for(Map.Entry<String,Object> value:values.entrySet()){
            if(setters.length()>0)setters.append(',');setters.append(value.getKey()).append("=?");arguments.add(value.getValue());
        }
        if(args!=null)Collections.addAll(arguments,args);
        return execute("UPDATE "+table+" SET "+setters+(where==null?"":" WHERE "+where),arguments.toArray()).getInt("changed");
    }
    public int delete(String table,String where,String[] args){return execute("DELETE FROM "+table+(where==null?"":" WHERE "+where),args).getInt("changed");}
    public Cursor query(String table,String[] columns,String where,String[] args,String group,String having,String order){return query(table,columns,where,args,group,having,order,null);}
    public Cursor query(String table,String[] columns,String where,String[] args,String group,String having,String order,String limit){
        return rawQuery("SELECT "+String.join(",",columns)+" FROM "+table+(where==null?"":" WHERE "+where)
                +(group==null?"":" GROUP BY "+group)+(having==null?"":" HAVING "+having)
                +(order==null?"":" ORDER BY "+order)+(limit==null?"":" LIMIT "+limit),args);
    }
    public Cursor rawQuery(String sql,String[] args){return new Cursor(execute(sql,args).getJSONArray("rows"));}
    public void close(){try{input.close();output.close();}catch(Exception ignored){}bridge.destroy();}
}
