package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Emits feed/source transitions only; repeated observations of the same state are silent. */
public final class FeedObservabilityTracker {
    private final LinkedHashMap<String,State> states=new LinkedHashMap<>();

    public synchronized Map<String,Object> observe(String symbol,String asset,String profileVersion,
                                                   long now,boolean fresh,String source,
                                                   Map<String,Object> details) {
        String normalizedSource=source==null?"UNKNOWN":source;State previous=states.get(symbol);
        if(previous!=null&&previous.fresh==fresh&&previous.source.equals(normalizedSource))return Collections.emptyMap();
        states.put(symbol,new State(fresh,normalizedSource));LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        out.put("symbol",symbol);out.put("asset",asset);out.put("profileVersion",profileVersion);
        out.put("eventAt",now);out.put("eventType",previous==null?"FEED_STATE_INITIALIZED":"FEED_SOURCE_TRANSITION");
        out.put("reasonCode",fresh?"V23410_FEED_FRESH":"V23410_FEED_STALE_OBSERVED");
        out.put("reasonText",(previous==null?"État initial":"Transition")+" : "+(fresh?"FRESH":"STALE")+" · "+normalizedSource);
        out.put("classification","STRUCTURAL_SHARED");out.put("historicalDiagnosticCode","");
        out.put("previousFresh",previous==null?null:previous.fresh);out.put("marketFeedFresh",fresh);
        out.put("previousSource",previous==null?"":previous.source);out.put("source",normalizedSource);
        if(details!=null)out.putAll(details);return Collections.unmodifiableMap(out);
    }
    public synchronized void reset(){states.clear();}
    private static final class State{final boolean fresh;final String source;State(boolean fresh,String source){this.fresh=fresh;this.source=source;}}
}
