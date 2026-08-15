package com.ethscalper.cockpit;

import org.json.JSONArray;
import org.json.JSONObject;

/** Strict Android-free parser for one Binance USD-M diff-depth event. */
public final class BinanceDepthDiff {
    public final String symbol;public final long exchangeEventAt,transactionAt,firstUpdateId,
            finalUpdateId,previousFinalUpdateId;public final double[][] bids,asks;
    private BinanceDepthDiff(String symbol,long eventAt,long transactionAt,long first,long last,
            long previous,double[][] bids,double[][] asks){this.symbol=symbol;exchangeEventAt=eventAt;
        this.transactionAt=transactionAt;firstUpdateId=first;finalUpdateId=last;
        previousFinalUpdateId=previous;this.bids=bids;this.asks=asks;}

    public static ParseResult parse(String expectedSymbol,JSONObject data){try{
        if(data==null||!"depthUpdate".equalsIgnoreCase(data.optString("e","")))
            return ParseResult.reject("DEPTH_DIFF_EVENT_INVALID");String symbol=data.optString("s","").trim();
        if(!CausalMarketRecord.supported(symbol)||expectedSymbol==null
                ||!expectedSymbol.equalsIgnoreCase(symbol))return ParseResult.reject("DEPTH_DIFF_SYMBOL_INVALID");
        long eventAt=strictLong(data,"E"),transactionAt=optionalLong(data,"T",-1),
                first=strictLong(data,"U"),last=strictLong(data,"u"),previous=optionalLong(data,"pu",-1);
        if(eventAt<0||transactionAt< -1||first<0||last<first||previous< -1)
            return ParseResult.reject("DEPTH_DIFF_UPDATE_ID_INVALID");double[][] bids=levels(data.optJSONArray("b")),
                asks=levels(data.optJSONArray("a"));if(bids==null||asks==null)
            return ParseResult.reject("DEPTH_DIFF_LEVELS_INVALID");
        return ParseResult.accept(new BinanceDepthDiff(symbol,eventAt,transactionAt,first,last,previous,bids,asks));
    }catch(RuntimeException error){return ParseResult.reject("DEPTH_DIFF_MALFORMED");}}

    static double[][] levels(JSONArray rows){if(rows==null)return null;double[][] out=new double[rows.length()][2];
        for(int i=0;i<rows.length();i++){JSONArray level=rows.optJSONArray(i);if(level==null||level.length()<2)
            return null;double price=number(level.opt(0)),quantity=number(level.opt(1));
            if(!Double.isFinite(price)||price<0||!Double.isFinite(quantity)||quantity<0)return null;
            out[i][0]=price;out[i][1]=quantity;}return out;}
    private static double number(Object raw){if(raw==null||raw==JSONObject.NULL)return Double.NaN;
        double value=raw instanceof Number?((Number)raw).doubleValue():Double.parseDouble(String.valueOf(raw));
        return Double.isFinite(value)?value:Double.NaN;}
    private static long strictLong(JSONObject value,String key){Object raw=value.opt(key);
        if(raw==null||raw==JSONObject.NULL)throw new IllegalArgumentException(key);
        if(raw instanceof Number){double number=((Number)raw).doubleValue();long integer=((Number)raw).longValue();
            if(!Double.isFinite(number)||number!=integer)throw new IllegalArgumentException(key);return integer;}
        return Long.parseLong(String.valueOf(raw));}
    private static long optionalLong(JSONObject value,String key,long fallback){return value.has(key)
            &&value.opt(key)!=JSONObject.NULL?strictLong(value,key):fallback;}
    public static final class ParseResult {public final BinanceDepthDiff value;public final String reasonCode;
        private ParseResult(BinanceDepthDiff value,String reason){this.value=value;reasonCode=reason;}
        static ParseResult accept(BinanceDepthDiff value){return new ParseResult(value,"");}
        static ParseResult reject(String reason){return new ParseResult(null,reason);}
        public boolean accepted(){return value!=null;}}
}
