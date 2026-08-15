package com.ethscalper.cockpit;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Tiny deterministic evaluator for the frozen scikit-learn ExtraTrees asset. */
public final class V4ExtraTreesModel {
    public static final List<String> FEATURE_ORDER=List.of("ret1","ret3","ret5","ret7","ret14","ret21","of3","of7",
            "buy_ratio","qvz7","qvz30","atr14","loc14","loc21");
    private final Tree[] longTrees,shortTrees;public final String modelSha256;
    private V4ExtraTreesModel(Tree[] l,Tree[] s,String hash){longTrees=l;shortTrees=s;modelSha256=hash;}
    public static V4ExtraTreesModel load(AssetManager assets)throws Exception{
        byte[] bytes;try(var in=assets.open("v4_fallback_model.json")){bytes=readAll(in);}
        return loadBytes(bytes);
    }
    public static V4ExtraTreesModel loadBytes(byte[] bytes)throws Exception{
        JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(!V4Universe.ENGINE_ID.equals(root.getString("engineId"))||!"2025-12-31".equals(root.getString("trainingEnd")))
            throw new IllegalStateException("Unfrozen V4 model");
        JSONArray order=root.getJSONArray("featureOrder");if(order.length()!=FEATURE_ORDER.size())throw new IllegalStateException("features");
        for(int i=0;i<order.length();i++)if(!FEATURE_ORDER.get(i).equals(order.getString(i)))throw new IllegalStateException("features");
        return new V4ExtraTreesModel(parse(root.getJSONArray("long")),parse(root.getJSONArray("short")),hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    public double predictLong(double[] f){return predict(longTrees,f);}
    public double predictShort(double[] f){return predict(shortTrees,f);}
    private static double predict(Tree[] trees,double[] f){if(f.length!=FEATURE_ORDER.size())throw new IllegalArgumentException("features");
        double sum=0;for(Tree t:trees)sum+=t.eval(f);return sum/trees.length;}
    private static Tree[] parse(JSONArray trees)throws Exception{Tree[] out=new Tree[trees.length()];for(int i=0;i<out.length;i++){
        JSONObject o=trees.getJSONObject(i);out[i]=new Tree(ints(o.getJSONArray("left")),ints(o.getJSONArray("right")),
                ints(o.getJSONArray("feature")),doubles(o.getJSONArray("threshold")),doubles(o.getJSONArray("value")));}return out;}
    private static int[] ints(JSONArray a)throws Exception{int[] x=new int[a.length()];for(int i=0;i<x.length;i++)x[i]=a.getInt(i);return x;}
    private static double[] doubles(JSONArray a)throws Exception{double[] x=new double[a.length()];for(int i=0;i<x.length;i++)x[i]=a.getDouble(i);return x;}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v));return s.toString();}
    private static byte[] readAll(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);return out.toByteArray();}
    static final class Tree {final int[] left,right,feature;final double[] threshold,value;Tree(int[] l,int[] r,int[] f,double[] t,double[] v){left=l;right=r;feature=f;threshold=t;value=v;}
        double eval(double[] x){int n=0;while(left[n]>=0)n=x[feature[n]]<=threshold[n]?left[n]:right[n];return value[n];}}
}
