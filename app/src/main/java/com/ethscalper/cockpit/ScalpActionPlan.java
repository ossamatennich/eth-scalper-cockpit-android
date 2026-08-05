package com.ethscalper.cockpit;

/** Immutable public-manual plan geometry and fee-aware economics. */
public final class ScalpActionPlan {
    public static final String ACTIVE="ACTIVE",TP="TP_TOUCHED",SL="SL_TOUCHED";
    public final ScalpActionPolicy.Route route;public final String episodeId,side,sourceType;
    public final long qualificationAt,entryValidUntil;public final double entry,takeProfit,stopLoss;
    public final double targetDistance,stopDistance,resultCostPerUnit,netRewardPerUnit,netRiskPerUnit;
    public final double plannedNetRewardRisk,theoreticalMaximumLoss,plannedNetRewardUsdt,a;
    public final int quantity;public final String signature;public final ScalpActionObservation observation;

    private ScalpActionPlan(ScalpActionPolicy.Route route,String episodeId,String side,String source,
                            long now,double entry,double tp,double sl,double target,double stop,double cost,
                            double reward,double risk,double rr,double maxLoss,double rewardUsdt,double a,int qty,
                            ScalpActionObservation observation){
        this.route=route;this.episodeId=episodeId;this.side=side;sourceType=source;qualificationAt=now;
        entryValidUntil=now+5_000L;this.entry=entry;takeProfit=tp;stopLoss=sl;targetDistance=target;
        stopDistance=stop;resultCostPerUnit=cost;netRewardPerUnit=reward;netRiskPerUnit=risk;
        plannedNetRewardRisk=rr;theoreticalMaximumLoss=maxLoss;plannedNetRewardUsdt=rewardUsdt;
        this.a=a;quantity=qty;this.observation=observation;signature=ScalpActionPolicy.ENGINE_ID+"|"+route.routeId+"|"+episodeId
                +"|"+side+"|"+entry+"|"+tp+"|"+sl;
    }

    public static BuildResult build(ScalpActionPolicy.Route route,String episodeId,String side,
                                    String source,long now,double bid,double ask,double a){
        return build(route,episodeId,side,source,now,bid,ask,a,null);
    }

    public static BuildResult build(ScalpActionPolicy.Route route,String episodeId,String side,
                                    String source,long now,double bid,double ask,double a,
                                    ScalpActionObservation observation){
        MarketProfile p=MarketProfile.eth();if(route==null||episodeId==null||episodeId.isEmpty()
                ||!("LONG".equals(side)||"SHORT".equals(side))||!validQuote(bid,ask)
                ||!Double.isFinite(a)||a<=0)return BuildResult.reject("SCALP_ACTION_INVALID_QUOTE");
        double entry="LONG".equals(side)?p.ceilToTick(ask):p.floorToTick(bid);
        double tp="LONG".equals(side)?p.floorToTick(entry+route.targetMultiple*a)
                :p.ceilToTick(entry-route.targetMultiple*a);
        double sl="LONG".equals(side)?p.floorToTick(entry-route.stopMultiple*a)
                :p.ceilToTick(entry+route.stopMultiple*a);
        double target=Math.abs(tp-entry),stop=Math.abs(entry-sl),cost=p.resultRoundTripCostReference;
        if(!(target>0&&stop>0)||("LONG".equals(side)&&!(tp>entry&&sl<entry))
                ||("SHORT".equals(side)&&!(tp<entry&&sl>entry)))return BuildResult.reject("SCALP_ACTION_INVALID_QUOTE");
        double reward=target-cost,risk=stop+cost;if(reward<=0)return BuildResult.reject("SCALP_ACTION_TARGET_NOT_NET_POSITIVE");
        double rr=reward/risk;if(!Double.isFinite(rr)||rr<.40)return BuildResult.reject("SCALP_ACTION_NET_RR_TOO_LOW");
        int raw=(int)Math.floor((p.finalRiskBudgetUsdt+1e-12)/risk);int qty=(raw/p.quantityStep)*p.quantityStep;
        qty=Math.min(p.maximumQuantity,Math.max(0,qty));if(qty<p.minimumQuantity)return BuildResult.reject("SCALP_ACTION_QUANTITY_ZERO");
        double loss=qty*risk;if(loss>p.finalRiskBudgetUsdt+1e-9)return BuildResult.reject("SCALP_ACTION_QUANTITY_ZERO");
        return BuildResult.accept(new ScalpActionPlan(route,episodeId,side,source,now,entry,tp,sl,
                target,stop,cost,reward,risk,rr,loss,qty*reward,a,qty,observation));
    }

    public static ScalpActionPlan fromState(ActivePlanState s){
        if(s==null||!ScalpActionPolicy.ENGINE_ID.equals(s.engineId))return null;
        ScalpActionPolicy.Route r=route(s.routeId);if(r==null)return null;
        double cost=s.resultCostPerUnit>0?s.resultCostPerUnit:MarketProfile.eth().resultRoundTripCostReference;
        double reward=s.targetMove-cost,risk=s.stopDistance+cost;
        return new ScalpActionPlan(r,s.episodeId,s.side,ScalpActionPolicy.RAW,
                s.qualificationAt>0?s.qualificationAt:s.finalConfirmedAt,s.entry,s.takeProfit,s.stopLoss,
                s.targetMove,s.stopDistance,cost,reward,risk,reward/risk,s.theoreticalMaximumLoss,
                s.quantity*reward,Math.max(.35,s.avgRange20),s.quantity,null);
    }

    private static ScalpActionPolicy.Route route(String id){
        for(ScalpActionPolicy.Route r:new ScalpActionPolicy.Route[]{ScalpActionPolicy.RANGE_EXTREME,
                ScalpActionPolicy.CONFIRM_MOVE3,ScalpActionPolicy.P01_SHORT_MICROVOL,
                ScalpActionPolicy.CONT_COVERAGE,ScalpActionPolicy.REVERSAL_8M})if(r.routeId.equals(id))return r;
        return null;
    }

    public Terminal observe(long now,double bid,double ask,boolean fresh){
        if(!fresh||!validQuote(bid,ask))return null;double q="LONG".equals(side)?bid:ask;
        boolean sl="LONG".equals(side)?q<=stopLoss:q>=stopLoss;
        boolean tp="LONG".equals(side)?q>=takeProfit:q<=takeProfit;
        if(!sl&&!tp)return null;String status=sl?SL:TP;double fill=sl?stopLoss:takeProfit;
        double gross=sl?-quantity*stopDistance:quantity*targetDistance;
        double fees=quantity*resultCostPerUnit;double net=gross-fees;
        double r=sl?-1.0:netRewardPerUnit/netRiskPerUnit;
        return new Terminal(status,now,q,fill,gross,fees,net,r);
    }
    private static boolean validQuote(double b,double a){return Double.isFinite(b)&&b>0&&Double.isFinite(a)&&a>0&&a>=b;}
    public static final class BuildResult {public final ScalpActionPlan plan;public final String reasonCode;
        private BuildResult(ScalpActionPlan p,String r){plan=p;reasonCode=r;}static BuildResult accept(ScalpActionPlan p){return new BuildResult(p,"");}
        static BuildResult reject(String r){return new BuildResult(null,r);}public boolean accepted(){return plan!=null;}}
    public static final class Terminal {public final String status;public final long terminalAt;public final double touchQuote,fillPrice,grossResultUsdt,estimatedFeesUsdt,netResultUsdt,resultR;
        Terminal(String s,long at,double q,double f,double g,double fees,double n,double r){status=s;terminalAt=at;touchQuote=q;fillPrice=f;grossResultUsdt=g;estimatedFeesUsdt=fees;netResultUsdt=n;resultR=r;}}
}
