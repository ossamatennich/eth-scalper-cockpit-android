package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure market-card catalogue shared by MainActivity and extensibility tests. */
public final class MarketUiCatalog {
    private MarketUiCatalog() {}

    public static List<CardDescriptor> cards(MarketRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry");
        List<CardDescriptor> cards=new ArrayList<>();
        for(MarketProfile profile:registry.tradedMarkets())
            cards.add(new CardDescriptor(profile.symbol,profile.asset,profile.profileVersion));
        return Collections.unmodifiableList(cards);
    }

    public static final class CardDescriptor {
        public final String symbol,asset,profileVersion;
        CardDescriptor(String symbol,String asset,String profileVersion) {
            this.symbol=symbol;this.asset=asset;this.profileVersion=profileVersion;
        }
    }
}
