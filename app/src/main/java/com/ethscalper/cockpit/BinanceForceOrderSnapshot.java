package com.ethscalper.cockpit;

import org.json.JSONObject;

/** Strict, Android-free parser for a Binance USD-M Futures forceOrder payload. */
public final class BinanceForceOrderSnapshot {
    public final long exchangeEventAt,tradeAt;
    public final String symbol,orderSide,orderType,timeInForce,orderStatus;
    public final double originalQuantity,price,averagePrice,lastFilledQuantity,
            accumulatedFilledQuantity;

    private BinanceForceOrderSnapshot(long exchangeEventAt,long tradeAt,String symbol,
            String orderSide,String orderType,String timeInForce,String orderStatus,
            double originalQuantity,double price,double averagePrice,double lastFilledQuantity,
            double accumulatedFilledQuantity){this.exchangeEventAt=exchangeEventAt;
        this.tradeAt=tradeAt;this.symbol=symbol;this.orderSide=orderSide;this.orderType=orderType;
        this.timeInForce=timeInForce;this.orderStatus=orderStatus;
        this.originalQuantity=originalQuantity;this.price=price;this.averagePrice=averagePrice;
        this.lastFilledQuantity=lastFilledQuantity;
        this.accumulatedFilledQuantity=accumulatedFilledQuantity;}

    public static ParseResult parse(String expectedSymbol,JSONObject data){
        try{
            if(data==null||!"forceOrder".equalsIgnoreCase(data.optString("e","")))
                return ParseResult.rejected("LIQUIDATION_EVENT_INVALID");
            JSONObject order=data.optJSONObject("o");
            if(order==null)return ParseResult.rejected("LIQUIDATION_ORDER_MISSING");
            String symbol=text(order,"s",24),side=text(order,"S",16);
            if(!CausalMarketRecord.supported(symbol)||expectedSymbol==null
                    ||!expectedSymbol.equalsIgnoreCase(symbol))
                return ParseResult.rejected("LIQUIDATION_SYMBOL_INVALID");
            if(side.isEmpty())return ParseResult.rejected("LIQUIDATION_SIDE_MISSING");
            long eventAt=strictLong(data,"E"),tradeAt=strictLong(order,"T");
            if(eventAt<0||tradeAt<0||tradeAt>eventAt)
                return ParseResult.rejected("LIQUIDATION_TIMESTAMP_INVALID");
            double quantity=strictNumber(order,"q"),price=strictNumber(order,"p"),
                    averagePrice=strictNumber(order,"ap"),last=strictNumber(order,"l"),
                    accumulated=strictNumber(order,"z");
            if(!nonNegative(quantity)||!nonNegative(price)||!nonNegative(averagePrice)
                    ||!nonNegative(last)||!nonNegative(accumulated))
                return ParseResult.rejected("LIQUIDATION_NUMBER_INVALID");
            return ParseResult.accepted(new BinanceForceOrderSnapshot(eventAt,tradeAt,symbol,side,
                    text(order,"o",40),text(order,"f",32),text(order,"X",32),quantity,price,
                    averagePrice,last,accumulated));
        }catch(RuntimeException error){return ParseResult.rejected("LIQUIDATION_PAYLOAD_MALFORMED");}
    }

    private static String text(JSONObject value,String key,int maximum){String out=value.optString(key,"")
            .trim();return out.length()<=maximum?out:out.substring(0,maximum);}
    private static long strictLong(JSONObject value,String key){Object raw=value.opt(key);
        if(raw==null||raw==JSONObject.NULL)throw new IllegalArgumentException(key);
        if(raw instanceof Number)return ((Number)raw).longValue();return Long.parseLong(String.valueOf(raw));}
    private static double strictNumber(JSONObject value,String key){Object raw=value.opt(key);
        if(raw==null||raw==JSONObject.NULL)throw new IllegalArgumentException(key);
        double parsed=raw instanceof Number?((Number)raw).doubleValue():Double.parseDouble(String.valueOf(raw));
        if(!Double.isFinite(parsed))throw new IllegalArgumentException(key);return parsed;}
    private static boolean nonNegative(double value){return Double.isFinite(value)&&value>=0;}

    public static final class ParseResult {
        public final BinanceForceOrderSnapshot snapshot;public final String reasonCode;
        private ParseResult(BinanceForceOrderSnapshot snapshot,String reasonCode){this.snapshot=snapshot;
            this.reasonCode=reasonCode;}
        static ParseResult accepted(BinanceForceOrderSnapshot snapshot){return new ParseResult(snapshot,"");}
        static ParseResult rejected(String reason){return new ParseResult(null,reason);}
        public boolean accepted(){return snapshot!=null;}
    }
}
