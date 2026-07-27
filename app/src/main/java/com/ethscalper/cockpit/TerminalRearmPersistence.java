package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;

/** Atomic persistence and pure timing policy for the three-minute terminal rearm. */
public final class TerminalRearmPersistence {
    public static final long REARM_MS = 180_000L;
    public static final String KEY_LAST_TERMINAL_AT = "lastTerminalAt";
    private final Backend backend;

    public TerminalRearmPersistence(Backend backend) { this.backend = backend; }

    public boolean save(long lastTerminalAt) {
        return lastTerminalAt > 0L && backend != null
                && backend.write(Collections.singletonMap(KEY_LAST_TERMINAL_AT,
                Long.toString(lastTerminalAt)));
    }

    public boolean save(String symbol,long lastTerminalAt) {
        if(lastTerminalAt<=0||backend==null)return false;
        Map<String,String> values=new LinkedHashMap<>();
        Map<String,String> current=backend.read();if(current!=null)values.putAll(current);
        values.put(KEY_LAST_TERMINAL_AT+"."+symbol,Long.toString(lastTerminalAt));
        if(MarketProfile.ETH_SYMBOL.equals(symbol))values.put(KEY_LAST_TERMINAL_AT,Long.toString(lastTerminalAt));
        return backend.write(values);
    }

    public long restore() {
        try {
            if (backend == null) return 0L;
            Map<String, String> values = backend.read();
            if (values == null) return 0L;
            long restored = Long.parseLong(values.get(KEY_LAST_TERMINAL_AT));
            return restored > 0L ? restored : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public long restore(String symbol) {
        try {
            Map<String,String> values=backend.read();if(values==null)return 0;
            String value=values.get(KEY_LAST_TERMINAL_AT+"."+symbol);
            if(value==null&&MarketProfile.ETH_SYMBOL.equals(symbol))value=values.get(KEY_LAST_TERMINAL_AT);
            long restored=Long.parseLong(value);return restored>0?restored:0;
        }catch(Exception ignored){return 0;}
    }

    public static long remainingMs(long now, long lastTerminalAt) {
        if (lastTerminalAt <= 0L || now < lastTerminalAt) return lastTerminalAt <= 0L ? 0L : REARM_MS;
        return Math.max(0L, REARM_MS - (now - lastTerminalAt));
    }

    public static boolean allowsNewCandidate(long now, long lastTerminalAt) {
        return remainingMs(now, lastTerminalAt) == 0L;
    }

    public interface Backend {
        Map<String, String> read();
        boolean write(Map<String, String> values);
    }
}
