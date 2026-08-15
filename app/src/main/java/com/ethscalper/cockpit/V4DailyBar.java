package com.ethscalper.cockpit;

import org.json.JSONArray;

/** Validated, fully completed Binance USD-M UTC daily bar. */
public final class V4DailyBar {
    public final long openTime;
    public final double open,high,low,close,volume,quoteVolume,takerBuyQuote;
    public V4DailyBar(long openTime,double open,double high,double low,double close,double volume,
                      double quoteVolume,double takerBuyQuote){
        if(openTime<0||!finitePositive(open)||!finitePositive(high)||!finitePositive(low)||!finitePositive(close)
                ||low>Math.min(open,close)||high<Math.max(open,close)||high<low||!finiteNonNegative(volume)
                ||!finiteNonNegative(quoteVolume)||!finiteNonNegative(takerBuyQuote)
                ||takerBuyQuote>quoteVolume+Math.max(1e-8,quoteVolume*1e-10))throw new IllegalArgumentException("Invalid daily bar");
        this.openTime=openTime;this.open=open;this.high=high;this.low=low;this.close=close;this.volume=volume;
        this.quoteVolume=quoteVolume;this.takerBuyQuote=Math.min(takerBuyQuote,quoteVolume);
    }
    public static V4DailyBar fromBinance(JSONArray a){try{return new V4DailyBar(a.getLong(0),a.getDouble(1),a.getDouble(2),
            a.getDouble(3),a.getDouble(4),a.getDouble(5),a.getDouble(7),a.getDouble(10));}catch(Exception e){throw new IllegalArgumentException("Binance daily bar",e);}}
    private static boolean finitePositive(double v){return Double.isFinite(v)&&v>0;}
    private static boolean finiteNonNegative(double v){return Double.isFinite(v)&&v>=0;}
}
