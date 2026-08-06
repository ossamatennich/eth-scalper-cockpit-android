package com.ethscalper.cockpit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recursively converts arbitrary diagnostic values into a validated, finite JSON tree. */
public final class SafeJsonNormalizer {
    private static final int MAX_ISSUES=20;
    private SafeJsonNormalizer() {}

    public static Result normalizeAndSerialize(JSONObject source) throws Exception {
        return normalizeObjectAndSerialize(source);
    }

    public static Result normalizeObjectAndSerialize(Object source) throws Exception {
        List<Issue> issues=new ArrayList<>();
        Object normalized=normalize(source,"$",issues);
        if(!(normalized instanceof JSONObject))throw new IllegalArgumentException("root is not an object");
        JSONObject object=(JSONObject)normalized;
        String serialized=object.toString();
        if(serialized==null)throw new IllegalStateException("JSONObject.toString returned null after normalization");
        new JSONObject(serialized);
        return new Result(object,serialized,issues);
    }

    public static Object normalize(Object value,String path,List<Issue> issues) {
        if(value==null||value==JSONObject.NULL)return JSONObject.NULL;
        if(value instanceof Boolean)return value;
        if(value instanceof CharSequence||value instanceof Character)
            return String.class.isInstance(value)?value:String.valueOf(value);
        if(value instanceof Number){
            double number=((Number)value).doubleValue();
            if(Double.isFinite(number))return value;
            issue(issues,path,"NON_FINITE_NUMBER",String.valueOf(value));
            return JSONObject.NULL;
        }
        if(value instanceof JSONObject){
            JSONObject out=new JSONObject();JSONObject in=(JSONObject)value;
            java.util.Iterator<String> keys=in.keys();while(keys.hasNext()){String key=keys.next();
                try{out.put(key,normalize(in.opt(key),path+"."+key,issues));}
                catch(Exception error){issue(issues,path+"."+key,"SECTION_NORMALIZATION_FAILED",error.getClass().getSimpleName());}}
            return out;
        }
        if(value instanceof JSONArray){
            JSONArray out=new JSONArray(),in=(JSONArray)value;
            for(int i=0;i<in.length();i++)out.put(normalize(in.opt(i),path+"["+i+"]",issues));
            return out;
        }
        if(value instanceof Map){
            JSONObject out=new JSONObject();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){
                String key=safeString(entry.getKey());
                try{out.put(key,normalize(entry.getValue(),path+"."+key,issues));}
                catch(Exception error){issue(issues,path+"."+key,"MAP_VALUE_FAILED",error.getClass().getSimpleName());}
            }
            return out;
        }
        if(value instanceof Collection){
            JSONArray out=new JSONArray();int index=0;
            for(Object item:(Collection<?>)value)out.put(normalize(item,path+"["+(index++)+"]",issues));
            return out;
        }
        if(value.getClass().isArray()){
            JSONArray out=new JSONArray();int length=Array.getLength(value);
            for(int i=0;i<length;i++)out.put(normalize(Array.get(value,i),path+"["+i+"]",issues));
            return out;
        }
        String text=safeString(value);
        issue(issues,path,"UNKNOWN_JAVA_VALUE_STRINGIFIED",value.getClass().getName());
        return text.isEmpty()?JSONObject.NULL:text;
    }

    public static boolean isValidObject(String serialized){
        if(serialized==null||serialized.trim().isEmpty())return false;
        try{new JSONObject(serialized);return true;}catch(Exception ignored){return false;}
    }

    public static int sizeBytes(JSONObject state) throws Exception {
        return normalizeAndSerialize(state).serialized.getBytes(StandardCharsets.UTF_8).length;
    }
    public static int sizeBytes(Map<String,Object> state) throws Exception {
        return normalizeObjectAndSerialize(state).serialized.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String safeString(Object value){
        if(value==null)return "";
        try{String text=String.valueOf(value);return text==null?"":text;}
        catch(RuntimeException ignored){return "";}
    }
    private static void issue(List<Issue> issues,String path,String code,String detail){
        if(issues!=null&&issues.size()<MAX_ISSUES)issues.add(new Issue(path,code,detail));
    }

    public static final class Issue {
        public final String path,code,detail;
        Issue(String path,String code,String detail){this.path=path;this.code=code;this.detail=detail;}
        public Map<String,Object> asMap(){Map<String,Object> out=new LinkedHashMap<>();out.put("path",path);
            out.put("code",code);out.put("detail",detail);return out;}
    }
    public static final class Result {
        public final JSONObject value;public final String serialized;public final List<Issue> issues;
        Result(JSONObject value,String serialized,List<Issue> issues){this.value=value;this.serialized=serialized;
            this.issues=List.copyOf(issues);}
        public String firstProblemSection(){return issues.isEmpty()?"":issues.get(0).path;}
    }
}
