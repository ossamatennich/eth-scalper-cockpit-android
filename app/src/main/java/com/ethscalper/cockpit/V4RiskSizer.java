package com.ethscalper.cockpit;

public final class V4RiskSizer {
    public static final double CORE_RISK=.0225,FALLBACK_RISK=.0075,COMBINED_CAP=.024,COST_BUFFER=.00133;
    private V4RiskSizer(){}
    public static double[] allocate(double... desired){double sum=0;for(double v:desired)sum+=Math.max(0,v);
        double scale=sum>COMBINED_CAP?COMBINED_CAP/sum:1;double[] out=new double[desired.length];
        for(int i=0;i<out.length;i++)out[i]=Math.max(0,desired[i])*scale;return out;}
    public static Result size(String asset,double equity,double entry,double stop,double allocatedRisk,V4MarketMetadata meta){
        if(!V4Universe.supports(asset)||!(equity>0&&entry>0&&allocatedRisk>0)||meta==null)return Result.unavailable();
        double stopPct=Math.abs(entry-stop)/entry;
        double multiple=Math.min(.80*V4Universe.leverage(asset),allocatedRisk/(stopPct+COST_BUFFER));
        double notional=equity*Math.max(0,multiple),quantity=meta.floorQuantity(notional/entry,entry);
        return quantity<=0?Result.unavailable():new Result(quantity,notional,multiple,true);
    }
    public static final class Result {public final double quantity,notionalUsd,notionalMultiple;public final boolean available;
        Result(double q,double n,double m,boolean a){quantity=q;notionalUsd=n;notionalMultiple=m;available=a;}
        static Result unavailable(){return new Result(0,0,0,false);}}
}
