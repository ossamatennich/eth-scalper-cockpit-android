package com.ethscalper.cockpit;

import org.json.JSONObject;

import java.util.UUID;

/** Persistent single source of truth for a manual Kraken Prop plan. */
public final class V4Plan {
    public enum Source{CORE,FALLBACK}
    public enum Side{LONG,SHORT}
    public enum Status{WAITING,LIMIT_ORDER_POSSIBLE,EXECUTABLE,ORDER_PLACED,OPEN,MISSED_TOO_LATE,
        INVALIDATED,EXPIRED,CLOSED_TP,CLOSED_SL,CLOSED_MANUAL,CLOSED_OTHER,DATA_UNAVAILABLE}
    public static final String ENGINE_VERSION="4.0.0";
    public final String planId,parentPlanId,symbol,modelManifestHash;
    public final Source source;public final Side side;public final int fixedLeverage;
    private double quantity;public final double allocatedRiskFraction;public final double entry,tp,sl,atr14,trackedEquityAtCreation;
    public final long createdAt,activatedAt,expiresAt,dataCutoffUtc;
    public Status status;public String statusReason,userFollowState,closeReason;
    public long lastEvaluatedAt,entryOrderMarkedAt,openedAt,closedAt;public double closePrice;
    public V4Plan(String planId,String parentPlanId,Source source,String symbol,Side side,double quantity,double entry,
                  double tp,double sl,double atr14,long createdAt,long activatedAt,long expiresAt,Status status,
                  String reason,double equity,double risk,long cutoff,String modelHash){
        if(!V4Universe.supports(symbol)||quantity<0||!(entry>0&&tp>0&&sl>0&&atr14>=0)||createdAt<0||expiresAt<=createdAt)
            throw new IllegalArgumentException("plan");
        if(source==Source.FALLBACK&&(modelHash==null||modelHash.isEmpty()))throw new IllegalArgumentException("fallback hash");
        this.planId=planId==null?UUID.randomUUID().toString():planId;this.parentPlanId=parentPlanId;
        this.source=source;this.symbol=symbol;this.side=side;this.fixedLeverage=V4Universe.leverage(symbol);
        this.quantity=quantity;this.entry=entry;this.tp=tp;this.sl=sl;this.atr14=atr14;this.createdAt=createdAt;
        this.activatedAt=activatedAt;this.expiresAt=expiresAt;this.status=status;this.statusReason=reason==null?"":reason;
        this.trackedEquityAtCreation=equity;this.allocatedRiskFraction=risk;this.dataCutoffUtc=cutoff;
        this.modelManifestHash=modelHash;this.userFollowState="NONE";this.closeReason="";
    }
    public boolean terminal(){return status==Status.MISSED_TOO_LATE||status==Status.INVALIDATED||status==Status.EXPIRED
            ||status==Status.CLOSED_TP||status==Status.CLOSED_SL||status==Status.CLOSED_MANUAL||status==Status.CLOSED_OTHER;}
    public double quantity(){return quantity;}
    public void restoreUncommittedQuantity(double restored){if(status!=Status.DATA_UNAVAILABLE||!(restored>0)||!Double.isFinite(restored))throw new IllegalStateException("quantity frozen");quantity=restored;}
    public JSONObject toJson(){try{JSONObject o=new JSONObject();o.put("plan_id",planId);o.put("parent_plan_id",parentPlanId);
        o.put("engine_id",V4Universe.ENGINE_ID);o.put("engine_version",ENGINE_VERSION);o.put("source",source.name());
        o.put("symbol",symbol);o.put("side",side.name());o.put("fixed_leverage",fixedLeverage);o.put("quantity",quantity);
        o.put("entry",entry);o.put("tp",tp);o.put("sl",sl);o.put("atr14",atr14);o.put("created_at",createdAt);
        o.put("activated_at",activatedAt);o.put("expires_at",expiresAt);o.put("status",status.name());
        o.put("status_reason",statusReason);o.put("tracked_equity_at_creation",trackedEquityAtCreation);
        o.put("allocated_risk_fraction",allocatedRiskFraction);o.put("data_cutoff_utc",dataCutoffUtc);
        o.put("model_manifest_hash",modelManifestHash);o.put("last_evaluated_at",lastEvaluatedAt);
        o.put("user_follow_state",userFollowState);o.put("entry_order_marked_at",nullable(entryOrderMarkedAt));
        o.put("opened_at",nullable(openedAt));o.put("closed_at",nullable(closedAt));
        o.put("close_price",closePrice>0?closePrice:JSONObject.NULL);o.put("close_reason",closeReason);return o;}catch(Exception e){throw new IllegalStateException("plan serialization",e);}}
    private static Object nullable(long v){return v>0?v:JSONObject.NULL;}
    public static V4Plan fromJson(JSONObject o){try{V4Plan p=new V4Plan(o.getString("plan_id"),o.optString("parent_plan_id",null),
            Source.valueOf(o.getString("source")),o.getString("symbol"),Side.valueOf(o.getString("side")),o.getDouble("quantity"),
            o.getDouble("entry"),o.getDouble("tp"),o.getDouble("sl"),o.getDouble("atr14"),o.getLong("created_at"),
            o.optLong("activated_at"),o.getLong("expires_at"),Status.valueOf(o.getString("status")),o.optString("status_reason"),
            o.getDouble("tracked_equity_at_creation"),o.getDouble("allocated_risk_fraction"),o.getLong("data_cutoff_utc"),
            o.optString("model_manifest_hash",null));p.lastEvaluatedAt=o.optLong("last_evaluated_at");
        p.userFollowState=o.optString("user_follow_state","NONE");p.entryOrderMarkedAt=o.optLong("entry_order_marked_at");
        p.openedAt=o.optLong("opened_at");p.closedAt=o.optLong("closed_at");p.closePrice=o.optDouble("close_price",0);
        p.closeReason=o.optString("close_reason","");return p;}catch(Exception e){throw new IllegalArgumentException("plan json",e);}}
    public static String french(Status s){return switch(s){case WAITING->"EN ATTENTE";case LIMIT_ORDER_POSSIBLE->"ORDRE LIMITE POSSIBLE";
        case EXECUTABLE->"EXÉCUTABLE";case ORDER_PLACED->"ORDRE POSÉ";case OPEN->"EN COURS";case MISSED_TOO_LATE->"TROP TARD";
        case INVALIDATED->"INVALIDÉ";case EXPIRED->"EXPIRÉ";case CLOSED_TP->"GAGNÉ / TP";case CLOSED_SL->"STOP / SL";
        case CLOSED_MANUAL->"FERMÉ";case CLOSED_OTHER->"TERMINÉ";case DATA_UNAVAILABLE->"DONNÉES INDISPONIBLES";};}
}
