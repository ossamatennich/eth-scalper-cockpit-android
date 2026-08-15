package com.ethscalper.cockpit;

import java.util.List;

/** Deterministic manual-plan lifecycle; ambiguous OHLC always resolves stop first. */
public final class V4PlanLifecycle {
    public static final class PricePoint {public final long at;public final double open,high,low,close,bid,ask;
        public PricePoint(long at,double open,double high,double low,double close,double bid,double ask){
            this.at=at;this.open=open;this.high=high;this.low=low;this.close=close;this.bid=bid;this.ask=ask;}}
    private V4PlanLifecycle(){}
    public static V4Plan.Status evaluate(V4Plan p,List<PricePoint> path,long now,V4MarketMetadata metadata){
        if(p.terminal())return p.status;
        boolean followed=p.status==V4Plan.Status.ORDER_PLACED||p.status==V4Plan.Status.OPEN;
        for(PricePoint b:path){if(b.at<p.createdAt)continue;
            boolean stop=touches(b,p.sl),target=touches(b,p.tp),entry=touches(b,p.entry);
            if(p.status==V4Plan.Status.OPEN){if(stop)return close(p,V4Plan.Status.CLOSED_SL,b.at,p.sl,"Stop atteint");
                if(target)return close(p,V4Plan.Status.CLOSED_TP,b.at,p.tp,"Take profit atteint");continue;}
            if(p.status==V4Plan.Status.ORDER_PLACED){if(entry){p.status=V4Plan.Status.OPEN;p.openedAt=b.at;p.statusReason="Ordre limite exécuté";
                    if(stop)return close(p,V4Plan.Status.CLOSED_SL,b.at,p.sl,"Stop atteint après exécution dans la même bougie");
                    if(target)return close(p,V4Plan.Status.CLOSED_TP,b.at,p.tp,"Take profit atteint");continue;}
                if(stop){p.status=V4Plan.Status.INVALIDATED;p.closedAt=b.at;p.statusReason="Setup invalidé avant exécution confirmée";return p.status;}
                if(target){p.status=V4Plan.Status.MISSED_TOO_LATE;p.closedAt=b.at;p.statusReason="Mouvement déjà consommé avant exécution";return p.status;}}
            if(!followed){if(stop){p.status=V4Plan.Status.INVALIDATED;p.statusReason="Setup invalidé avant entrée";p.closedAt=b.at;return p.status;}
                if(target){p.status=V4Plan.Status.MISSED_TOO_LATE;p.statusReason="Mouvement déjà consommé";p.closedAt=b.at;return p.status;}}
        }
        if(now>=p.expiresAt&&p.status!=V4Plan.Status.OPEN){p.status=V4Plan.Status.EXPIRED;p.statusReason="Fenêtre d'entrée terminée";p.closedAt=now;return p.status;}
        if(!path.isEmpty()&&p.status!=V4Plan.Status.OPEN&&p.status!=V4Plan.Status.ORDER_PLACED){PricePoint q=path.get(path.size()-1);
            double allowance=metadata==null?0:metadata.tickSize;double spread=q.ask>q.bid?q.ask-q.bid:0;
            double executable=p.side==V4Plan.Side.LONG?(q.ask>0?q.ask:q.close):(q.bid>0?q.bid:q.close);
            p.status=Math.abs(executable-p.entry)<=Math.max(allowance,spread)+1e-12
                    ?V4Plan.Status.EXECUTABLE:V4Plan.Status.LIMIT_ORDER_POSSIBLE;
            p.statusReason=p.status==V4Plan.Status.EXECUTABLE?"Marché au prix d'entrée":"Entrée figée disponible en limite";}
        p.lastEvaluatedAt=now;return p.status;
    }
    public static void markOrderPlaced(V4Plan p,long now){if(p.status!=V4Plan.Status.LIMIT_ORDER_POSSIBLE)throw new IllegalStateException();
        p.status=V4Plan.Status.ORDER_PLACED;p.entryOrderMarkedAt=now;p.userFollowState="ORDER_PLACED";p.statusReason="Ordre limite déclaré";}
    public static void markTaken(V4Plan p,long now){if(p.status!=V4Plan.Status.EXECUTABLE)throw new IllegalStateException();
        p.status=V4Plan.Status.OPEN;p.openedAt=now;p.userFollowState="TRADE_TAKEN";p.statusReason="Trade déclaré ouvert";}
    public static void manualClose(V4Plan p,long now,double price){if(p.status!=V4Plan.Status.OPEN)throw new IllegalStateException();
        close(p,V4Plan.Status.CLOSED_MANUAL,now,price,"Fermeture manuelle");}
    private static boolean touches(PricePoint p,double level){return p.low<=level&&p.high>=level;}
    private static V4Plan.Status close(V4Plan p,V4Plan.Status s,long at,double price,String reason){p.status=s;p.closedAt=at;
        p.closePrice=price;p.closeReason=reason;p.statusReason=reason;return s;}
}
