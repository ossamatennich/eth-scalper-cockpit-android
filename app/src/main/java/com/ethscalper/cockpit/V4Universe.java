package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Frozen canonical Kraken Prop universe and imposed leverage metadata. */
public final class V4Universe {
    public static final String ENGINE_ID = "NMC_PROP_DAILY_HYBRID_V4";
    public static final List<String> ASSETS = List.of(
            "AAVE","ADA","AIXBT","ALGO","APT","ARB","ASTER","ATOM","AVAX","BCH","BNB","BTC","CRV",
            "DOGE","DOT","ETC","ETH","FARTCOIN","FIL","GRASS","HBAR","HYPE","INJ","JTO","JUP","KAITO",
            "LDO","LINK","LTC","MOODENG","NEAR","ONDO","OP","PENGU","PNUT","POL","POPCAT","PUMP",
            "RENDER","S","SOL","STX","SUI","TAO","TIA","TRUMP","TRX","UNI","VIRTUAL","WIF","WLD","XPL","XRP");
    public static final List<String> CORE_ASSETS = List.of("BTC","ETH","SOL","BNB","XRP");
    private static final Map<String,Integer> LEVERAGE;
    static {
        LinkedHashMap<String,Integer> m=new LinkedHashMap<>();
        put(m,10,"BTC");
        put(m,5,"AAVE ADA APT ARB ASTER AVAX BCH BNB CRV DOGE DOT ETC ETH FIL HBAR INJ LDO LINK LTC SOL SUI TAO TRUMP TRX UNI XRP");
        put(m,3,"HYPE JTO NEAR ONDO PENGU PUMP WLD XPL");
        put(m,2,"AIXBT ALGO ATOM FARTCOIN GRASS JUP KAITO MOODENG OP PNUT POL POPCAT RENDER S STX TIA VIRTUAL WIF");
        if(m.size()!=ASSETS.size()||!m.keySet().containsAll(ASSETS))throw new IllegalStateException("Invalid V4 leverage registry");
        LEVERAGE=Collections.unmodifiableMap(m);
    }
    private static void put(Map<String,Integer> map,int leverage,String symbols){for(String s:symbols.split(" ")){
        if(map.put(s,leverage)!=null)throw new IllegalStateException("Duplicate leverage: "+s);}}
    private V4Universe(){}
    public static boolean supports(String asset){return LEVERAGE.containsKey(asset);}
    public static int leverage(String asset){Integer v=LEVERAGE.get(asset);if(v==null)throw new IllegalArgumentException(asset);return v;}
    public static Map<String,Integer> leverageTable(){return LEVERAGE;}
    public static String binanceSymbol(String asset){if(!supports(asset))throw new IllegalArgumentException(asset);return asset+"USDT";}
}
