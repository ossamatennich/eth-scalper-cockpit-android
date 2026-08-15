package com.ethscalper.cockpit;

import android.content.Context;
import android.content.SharedPreferences;

public final class V4AccountProfile {
    public enum Mode{EVAL,FUNDED}
    private static final String PREF="v4_account_profile";
    private final SharedPreferences prefs;
    public V4AccountProfile(Context c){prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    public Mode mode(){try{return Mode.valueOf(prefs.getString("mode","EVAL"));}catch(Exception e){return Mode.EVAL;}}
    public double equity(){return positive(prefs.getFloat("equity",5000f),5000);}
    public double target(){return positive(prefs.getFloat("target",5450f),5450);}
    public double mdd(){return positive(prefs.getFloat("mdd",.06f),.06);}
    public void update(Mode mode,double equity,double target,double mdd){if(!(equity>0&&target>0&&mdd>0&&mdd<1))throw new IllegalArgumentException();
        prefs.edit().putString("mode",mode.name()).putFloat("equity",(float)equity).putFloat("target",(float)target).putFloat("mdd",(float)mdd).apply();}
    public void reset(){update(Mode.EVAL,5000,5450,.06);}
    public void applyClosedPlan(V4Plan p){if(p.openedAt<=0||p.closedAt<=0||p.closePrice<=0)return;double signed=p.side==V4Plan.Side.LONG?1:-1;
        double gross=signed*(p.closePrice-p.entry)*p.quantity,fees=(p.entry+p.closePrice)*p.quantity*.0005;
        double days=Math.max(0,(p.closedAt-p.openedAt)/86_400_000d),funding=p.entry*p.quantity*.00033*days;
        prefs.edit().putFloat("equity",(float)Math.max(0,equity()+gross-fees-funding)).apply();}
    private static double positive(double v,double fallback){return Double.isFinite(v)&&v>0?v:fallback;}
}
