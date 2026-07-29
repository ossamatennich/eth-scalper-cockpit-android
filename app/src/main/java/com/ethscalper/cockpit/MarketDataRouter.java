package com.ethscalper.cockpit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Symbol-keyed market-data router used by MarketWatchService.
 *
 * BTC is intentionally not accepted here: it belongs to SharedReferenceContext. A legacy
 * mirror may be registered for ETH so the immutable v2.33.2.1 pipeline receives exactly the
 * same public data without teaching the service about each additional traded symbol.
 */
public final class MarketDataRouter {
    private final MarketRegistry registry;
    private final MultiMarketCoordinator coordinator;
    private final Map<String, LegacyMirror> mirrors;

    public MarketDataRouter(MarketRegistry registry, MultiMarketCoordinator coordinator,
                            Map<String, LegacyMirror> mirrors) {
        if (registry == null || coordinator == null) throw new IllegalArgumentException("router");
        this.registry = registry;
        this.coordinator = coordinator;
        LinkedHashMap<String, LegacyMirror> copy = new LinkedHashMap<>();
        if (mirrors != null) {
            for (Map.Entry<String, LegacyMirror> entry : mirrors.entrySet()) {
                if (!registry.contains(entry.getKey()) || entry.getValue() == null) {
                    throw new IllegalArgumentException("Unknown or null mirror: " + entry.getKey());
                }
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        this.mirrors = Collections.unmodifiableMap(copy);
    }

    public boolean routeBookTicker(String symbol, double bid, double ask, long now) {
        MarketRuntime runtime = find(symbol);
        if (runtime == null || !positive(bid) || !positive(ask)) return false;
        double last = (bid + ask) / 2.0;
        runtime.last = last;
        runtime.bid = bid;
        runtime.ask = ask;
        runtime.lastTickerAt = now;
        runtime.bookTickerMessages++;
        LegacyMirror mirror = mirrors.get(runtime.profile.symbol);
        if (mirror != null) mirror.onBookTicker(last, bid, ask, now);
        return true;
    }

    public boolean routeKline(String symbol, MarketRuntime.MarketBar bar, long receivedAt) {
        MarketRuntime runtime = find(symbol);
        if (runtime == null || !valid(bar)) return false;
        upsert(runtime, bar);
        runtime.last = bar.close;
        runtime.lastKlineAt = receivedAt;
        runtime.klineMessages++;
        LegacyMirror mirror = mirrors.get(runtime.profile.symbol);
        if (mirror != null) mirror.onKline(bar, receivedAt);
        return true;
    }

    public boolean routeAggTrade(String symbol, MarketRuntime.AggTrade trade, long receivedAt) {
        MarketRuntime runtime = find(symbol);
        if (runtime == null || trade == null || trade.id < 0 || trade.at <= 0
                || !positive(trade.price) || !positive(trade.quantity)) return false;
        if (trade.id <= runtime.lastAggTradeId) return false;
        runtime.aggTrades.addLast(trade);
        runtime.lastAggTradeId = trade.id;
        runtime.lastAggTradeAt = trade.at;
        runtime.aggTradeMessages++;
        pruneTrades(runtime, receivedAt);
        LegacyMirror mirror = mirrors.get(runtime.profile.symbol);
        if (mirror != null) mirror.onAggTrade(trade, receivedAt);
        return true;
    }

    public int replacePreloadedCandles(String symbol, List<MarketRuntime.MarketBar> bars,
                                       long receivedAt) {
        MarketRuntime runtime = find(symbol);
        if (runtime == null || bars == null) return 0;
        runtime.candles.clear();
        int accepted = mergeBars(runtime, bars);
        if (accepted > 0) {
            runtime.last = runtime.candles.peekLast().close;
            runtime.lastRestKlineAt = receivedAt;
            runtime.restKlineRefreshes++;
        }
        LegacyMirror mirror = mirrors.get(runtime.profile.symbol);
        if (mirror != null) mirror.onCandleBatch(bars, true, receivedAt);
        return accepted;
    }

    public int mergeFallbackCandles(String symbol, List<MarketRuntime.MarketBar> bars,
                                    long receivedAt) {
        MarketRuntime runtime = find(symbol);
        if (runtime == null || bars == null) return 0;
        int accepted = mergeBars(runtime, bars);
        if (accepted > 0) {
            runtime.last = runtime.candles.peekLast().close;
            runtime.lastRestKlineAt = receivedAt;
            runtime.restKlineRefreshes++;
        }
        LegacyMirror mirror = mirrors.get(runtime.profile.symbol);
        if (mirror != null) mirror.onCandleBatch(bars, false, receivedAt);
        return accepted;
    }

    public int mergeFallbackAggTrades(String symbol, List<MarketRuntime.AggTrade> trades,
                                      long receivedAt) {
        MarketRuntime runtime = find(symbol);
        if (runtime == null || trades == null) return 0;
        int accepted = 0;
        for (MarketRuntime.AggTrade trade : trades) {
            if (routeAggTrade(symbol, trade, receivedAt)) accepted++;
        }
        if (accepted > 0) {
            runtime.lastRestTickerAt = receivedAt;
            runtime.restTradeRefreshes++;
        }
        LegacyMirror mirror = mirrors.get(runtime.profile.symbol);
        if (mirror != null) mirror.onAggTradeBatch(accepted, receivedAt);
        return accepted;
    }

    public MarketRuntime runtimeForStream(String stream) {
        return find(symbolFromStream(stream));
    }

    public static String symbolFromStream(String stream) {
        if (stream == null) return "";
        int marker = stream.indexOf('@');
        String raw = marker < 0 ? stream : stream.substring(0, marker);
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private MarketRuntime find(String symbol) {
        if (symbol == null) return null;
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        return registry.contains(normalized) ? coordinator.runtime(normalized) : null;
    }

    private static int mergeBars(MarketRuntime runtime, List<MarketRuntime.MarketBar> bars) {
        int accepted = 0;
        for (MarketRuntime.MarketBar bar : bars) {
            if (!valid(bar)) continue;
            upsert(runtime, bar);
            accepted++;
        }
        return accepted;
    }

    private static void upsert(MarketRuntime runtime, MarketRuntime.MarketBar bar) {
        if (!runtime.candles.isEmpty()
                && runtime.candles.peekLast().openTime == bar.openTime) {
            runtime.candles.removeLast();
        }
        runtime.candles.addLast(bar);
        while (runtime.candles.size() > 180) runtime.candles.removeFirst();
    }

    private static void pruneTrades(MarketRuntime runtime, long now) {
        while (!runtime.aggTrades.isEmpty()
                && now - runtime.aggTrades.peekFirst().at > 120_000L) {
            runtime.aggTrades.removeFirst();
        }
    }

    private static boolean valid(MarketRuntime.MarketBar bar) {
        return bar != null && bar.openTime >= 0 && positive(bar.open) && positive(bar.high)
                && positive(bar.low) && positive(bar.close) && Double.isFinite(bar.volume)
                && bar.volume >= 0 && bar.high >= Math.max(bar.open, bar.close)
                && bar.low <= Math.min(bar.open, bar.close);
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    public interface LegacyMirror {
        default void onBookTicker(double last, double bid, double ask, long now) {}
        default void onKline(MarketRuntime.MarketBar bar, long receivedAt) {}
        default void onAggTrade(MarketRuntime.AggTrade trade, long receivedAt) {}
        default void onAggTradeBatch(int accepted, long receivedAt) {}
        default void onCandleBatch(List<MarketRuntime.MarketBar> bars, boolean replace,
                                   long receivedAt) {}
    }
}
