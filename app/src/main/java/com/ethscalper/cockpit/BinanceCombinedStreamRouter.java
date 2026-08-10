package com.ethscalper.cockpit;

import org.json.JSONObject;

import java.util.Locale;

/** Pure, fail-closed parser/dispatcher for Binance Futures combined public streams. */
public final class BinanceCombinedStreamRouter {
    public enum Type { BOOK_TICKER, AGG_TRADE, KLINE_1M, DEPTH20 }

    public interface Listener {
        void onEvent(Event event);
        default void onMalformed(String reasonCode) {}
    }

    public static final class Event {
        public final Type type;public final String stream,symbol;public final JSONObject data;
        Event(Type type,String stream,String symbol,JSONObject data){this.type=type;
            this.stream=stream;this.symbol=symbol;this.data=data;}
    }

    public boolean dispatch(String payload,Listener listener){if(listener==null)
        throw new IllegalArgumentException("listener");try{Event event=parse(payload);
            if(event==null){listener.onMalformed("UNSUPPORTED_OR_MALFORMED_STREAM");return false;}
            listener.onEvent(event);return true;}catch(RuntimeException error){
            listener.onMalformed("STREAM_PARSE_ERROR");return false;}}

    public Event parse(String payload){if(payload==null||payload.trim().isEmpty())return null;
        try{JSONObject root=new JSONObject(payload);String stream=root.optString("stream","").trim();
            JSONObject data=root.optJSONObject("data");if(stream.isEmpty()||data==null)return null;
            String normalized=stream.toLowerCase(Locale.ROOT);String symbol=
                    MarketDataRouter.symbolFromStream(stream);if(!CausalMarketRecord.supported(symbol))return null;
            Type type;if(normalized.endsWith("@bookticker"))type=Type.BOOK_TICKER;
            else if(normalized.endsWith("@aggtrade"))type=Type.AGG_TRADE;
            else if(normalized.endsWith("@kline_1m"))type=Type.KLINE_1M;
            else if(normalized.endsWith("@depth20@100ms"))type=Type.DEPTH20;
            else return null;return new Event(type,stream,symbol,data);
        }catch(Exception ignored){return null;}
    }
}
