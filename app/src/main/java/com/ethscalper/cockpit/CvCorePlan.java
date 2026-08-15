package com.ethscalper.cockpit;

/** Immutable fee-aware CV Core plan, with planned-price terminal economics. */
public final class CvCorePlan {
    public static final String TP="TP_TOUCHED",SL="SL_TOUCHED";
    public final CvCorePolicy.Route route;public final String episodeId,side,sourceType,signature;
    public final long qualificationAt,entryValidUntil;public final double entry,takeProfit,stopLoss,targetDistance,stopDistance;
    public final double resultCostPerUnit,netRewardPerUnit,netRiskPerUnit,plannedNetRewardRisk,theoreticalMaximumLoss,plannedNetRewardUsdt,a;
    public final int quantity;public final CvCoreObservation observation;
    private CvCorePlan(CvCorePolicy.Route r,String episode,String side,String source,long now,double entry,double tp,double sl,
                       double target,double stop,double reward,double risk,double rr,double loss,double rewardUsdt,double a,int qty,CvCoreObservation o){
        route=r;episodeId=episode;this.side=side;sourceType=source;qualificationAt=now;entryValidUntil=now+5_000L;
        this.entry=entry;takeProfit=tp;stopLoss=sl;targetDistance=target;stopDistance=stop;
        resultCostPerUnit=CvCorePolicy.RESULT_COST_PER_UNIT;netRewardPerUnit=reward;netRiskPerUnit=risk;
        plannedNetRewardRisk=rr;theoreticalMaximumLoss=loss;plannedNetRewardUsdt=rewardUsdt;this.a=a;quantity=qty;observation=o;
        signature=CvCorePolicy.ENGINE_ID+"|"+r.routeId+"|"+episode+"|"+side+"|"+entry+"|"+tp+"|"+sl;}
    public static BuildResult build(CvCorePolicy.Route route,String episode,String side,String source,long now,double bid,double ask,double a){
        return build(route,episode,side,source,now,bid,ask,a,null);}
    public static BuildResult build(CvCorePolicy.Route route,String episode,String side,String source,long now,double bid,double ask,double a,CvCoreObservation observation){
        MarketProfile p=MarketProfile.eth();if(!CvCorePolicy.known(route)||episode==null||episode.isEmpty()||now<=0
                ||!("LONG".equals(side)||"SHORT".equals(side))||!validQuote(bid,ask)||!Double.isFinite(a)||a<=0)
            return BuildResult.reject("CV_CORE_INVALID_QUOTE");
        double entry="LONG".equals(side)?p.ceilToTick(ask):p.floorToTick(bid);
        double tp="LONG".equals(side)?p.floorToTick(entry+route.targetMultiple*a):p.ceilToTick(entry-route.targetMultiple*a);
        double sl="LONG".equals(side)?p.floorToTick(entry-route.stopMultiple*a):p.ceilToTick(entry+route.stopMultiple*a);
        double target=Math.abs(tp-entry),stop=Math.abs(entry-sl);
        if(!(target>0&&stop>0)||("LONG".equals(side)&&!(tp>entry&&sl<entry))||("SHORT".equals(side)&&!(tp<entry&&sl>entry)))
            return BuildResult.reject("CV_CORE_INVALID_QUOTE");
        double reward=target-CvCorePolicy.RESULT_COST_PER_UNIT,risk=stop+CvCorePolicy.RESULT_COST_PER_UNIT;
        if(!(reward>0))return BuildResult.reject("CV_CORE_TARGET_NOT_NET_POSITIVE");double rr=reward/risk;
        if(!Double.isFinite(rr)||rr<.40)return BuildResult.reject("CV_CORE_NET_RR_TOO_LOW");
        int raw=(int)Math.floor((route.riskBudgetUsdt+1e-12)/risk);int qty=(raw/p.quantityStep)*p.quantityStep;
        qty=Math.min(p.maximumQuantity,Math.max(0,qty));if(qty<p.minimumQuantity)return BuildResult.reject("CV_CORE_QUANTITY_ZERO");
        double loss=qty*risk;if(loss>route.riskBudgetUsdt+1e-9)return BuildResult.reject("CV_CORE_RISK_BUDGET_EXCEEDED");
        return BuildResult.accept(new CvCorePlan(route,episode,side,source,now,entry,tp,sl,target,stop,reward,risk,rr,loss,qty*reward,a,qty,observation));
    }
    public static CvCorePlan fromState(ActivePlanState s){if(s==null||!CvCorePolicy.ENGINE_ID.equals(s.engineId))return null;
        CvCorePolicy.Route r=CvCorePolicy.route(s.routeId);if(r==null)return null;double reward=s.targetMove-CvCorePolicy.RESULT_COST_PER_UNIT;
        double risk=s.stopDistance+CvCorePolicy.RESULT_COST_PER_UNIT;return new CvCorePlan(r,s.episodeId,s.side,"RESTORED",
                s.qualificationAt>0?s.qualificationAt:s.finalConfirmedAt,s.entry,s.takeProfit,s.stopLoss,s.targetMove,s.stopDistance,
                reward,risk,reward/risk,s.quantity*risk,s.quantity*reward,Math.max(.35,s.avgRange20),s.quantity,null);}
    public Terminal observe(long now,double bid,double ask,boolean fresh){if(!fresh||!validQuote(bid,ask))return null;
        double q="LONG".equals(side)?bid:ask;boolean stop="LONG".equals(side)?q<=stopLoss:q>=stopLoss;
        boolean target="LONG".equals(side)?q>=takeProfit:q<=takeProfit;if(!stop&&!target)return null;
        String status=stop?SL:TP;double fill=stop?stopLoss:takeProfit;double gross=stop?-quantity*stopDistance:quantity*targetDistance;
        double fees=quantity*resultCostPerUnit,net=gross-fees;return new Terminal(status,now,q,fill,gross,fees,net,stop?-1d:netRewardPerUnit/netRiskPerUnit);}
    private static boolean validQuote(double b,double a){return Double.isFinite(b)&&b>0&&Double.isFinite(a)&&a>0&&a>=b;}
    public static final class BuildResult{public final CvCorePlan plan;public final String reasonCode;private BuildResult(CvCorePlan p,String r){plan=p;reasonCode=r;}
        static BuildResult accept(CvCorePlan p){return new BuildResult(p,"");}static BuildResult reject(String r){return new BuildResult(null,r);}public boolean accepted(){return plan!=null;}}
    public static final class Terminal{public final String status;public final long terminalAt;public final double touchQuote,fillPrice,grossResultUsdt,estimatedFeesUsdt,netResultUsdt,resultR;
        Terminal(String s,long at,double touch,double fill,double gross,double fees,double net,double r){status=s;terminalAt=at;touchQuote=touch;fillPrice=fill;grossResultUsdt=gross;estimatedFeesUsdt=fees;netResultUsdt=net;resultR=r;}}
}
