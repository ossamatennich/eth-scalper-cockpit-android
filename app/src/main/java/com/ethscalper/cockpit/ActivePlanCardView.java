package com.ethscalper.cockpit;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Reusable active-plan card. It renders any registered market without symbol branches. */
public final class ActivePlanCardView extends LinearLayout {
    private static final int TEXT=Color.rgb(237,244,251),MUTED=Color.rgb(137,155,177);
    private static final int CYAN=Color.rgb(67,224,193),AMBER=Color.rgb(255,169,64);
    private static final int RED=Color.rgb(255,78,112),CARD=Color.rgb(14,23,36);
    private final TextView title,state,levels,market,progress,results,stopWhy,sizingWhy,risk,execution,warning;
    private PlanUiModel model;

    public ActivePlanCardView(Context context){super(context);setOrientation(VERTICAL);
        setPadding(dp(16),dp(15),dp(16),dp(16));setBackground(background());
        title=text("PLAN ACTIF",20,TEXT,true,false);addView(title);
        state=text("—",13,CYAN,true,false);state.setPadding(0,dp(4),0,dp(8));addView(state);
        levels=text("DONNÉE INDISPONIBLE",17,TEXT,true,true);addView(levels);
        market=text("",13,MUTED,false,true);market.setPadding(0,dp(8),0,0);addView(market);
        progress=text("",13,TEXT,false,true);progress.setPadding(0,dp(8),0,0);addView(progress);
        results=text("",13,TEXT,false,true);results.setPadding(0,dp(8),0,0);addView(results);
        stopWhy=text("",13,TEXT,false,true);stopWhy.setPadding(0,dp(10),0,0);addView(stopWhy);
        sizingWhy=text("",13,AMBER,false,true);sizingWhy.setPadding(0,dp(8),0,0);addView(sizingWhy);
        risk=text("",13,AMBER,false,true);risk.setPadding(0,dp(8),0,0);addView(risk);
        execution=text("",13,CYAN,false,true);execution.setPadding(0,dp(8),0,0);addView(execution);
        warning=text("",12,RED,true,false);warning.setPadding(0,dp(8),0,0);addView(warning);
        LinearLayout actions=new LinearLayout(context);actions.setOrientation(HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);actions.setPadding(0,dp(9),0,0);addView(actions);
        addCopy(actions,"LIMIT",0);addCopy(actions,"TP",1);addCopy(actions,"SL",2);addCopy(actions,"TOUT",3);
    }

    public void bind(PlanUiModel value){model=value;if(value==null)return;
        changed(title,value.symbol+" · "+value.side+" · "+value.quantity+" "+value.asset);
        changed(state,"PLAN IMMUTABLE · TP/SL UNIQUEMENT · "+value.feedState);
        changed(levels,"LIMIT "+money(value.entry)+"   TP "+money(value.takeProfit)+"   SL "+money(value.stopLoss));
        changed(market,"Cours "+money(value.currentPrice)+"   BID "+money(value.bid)+"   ASK "+money(value.ask)
                +"\nScore "+integer(value.score)+" · Sleeve "+available(value.sleeve)
                +" · Âge "+duration(value.ageMs)+" · Feed "+duration(value.feedAgeMs));
        if(!value.complete()){
            changed(progress,"Progression : DONNÉE INDISPONIBLE");
            changed(results,"Résultats : DONNÉE INDISPONIBLE");
            changed(stopWhy,"POURQUOI CE STOP ?\nDONNÉE INDISPONIBLE");
            changed(sizingWhy,"SIZING\nDONNÉE INDISPONIBLE");
            changed(risk,"Risque : DONNÉE INDISPONIBLE");
            changed(execution,"Exécution LIMIT : DONNÉE INDISPONIBLE");
            changed(warning,PlanUiModel.DATA_INCOMPLETE+" · DONNÉE INDISPONIBLE");return;
        }
        PlanMetricsCalculator.Result m=value.metrics;
        changed(progress,String.format(Locale.FRANCE,"Progression %.1f %% · distance TP %.2f · distance SL %.2f",
                m.progressPercent,m.distanceToTarget,m.distanceToStop));
        changed(results,String.format(Locale.FRANCE,
                "Gain brut %.2f · net estimé %.2f\nPerte brute %.2f · nette estimée %.2f · frais %.2f USDT",
                m.grossProfit,m.netProfit,m.grossLoss,m.netLoss,m.estimatedFees));
        double finalStopDistance=Math.abs(value.entry-value.stopLoss);
        boolean stopData=positive(value.volatilityA)&&positive(value.baseStop)
                &&positive(finalStopDistance)
                &&!value.stopCalculationType.isEmpty();
        changed(stopWhy,stopData?String.format(Locale.FRANCE,
                "POURQUOI CE STOP ?\nA %.2f · E %s · base %.2f · anchor %s · fenêtre %s · buffer %s\nSL final %.2f · %s",
                value.volatilityA,number(value.adverseExcursion),value.baseStop,
                number(value.structuralAnchor),value.structuralWindowMinutes>0
                        ?value.structuralWindowMinutes+" min":"DONNÉE INDISPONIBLE",
                number(value.structuralBuffer),finalStopDistance,value.stopCalculationType)
                :"POURQUOI CE STOP ?\nDONNÉE INDISPONIBLE");
        boolean sizingData=positive(value.riskBudgetUsdt)&&positive(value.riskPerUnit)
                &&value.riskQuantity>0&&value.qualityCap>0&&!value.selectedBudgetReason.isEmpty();
        changed(sizingWhy,sizingData?String.format(Locale.FRANCE,
                "SIZING\nBudget brut hors frais %.2f · %s\nRisque brut/unité %.2f · quantité risque %d · plafond qualité %d · finale %d\nPerte brute SL %.2f · frais estimés %.2f · perte totale estimée %.2f USDT",
                value.riskBudgetUsdt,value.selectedBudgetReason,value.riskPerUnit,
                value.riskQuantity,value.qualityCap,value.quantity,m.grossLoss,m.estimatedFees,
                m.netLoss)
                :"SIZING\nDONNÉE INDISPONIBLE");
        changed(risk,String.format(Locale.FRANCE,
                "Perte brute maximale %.2f / %.2f USDT hors frais · R/R brut %.2f",
                m.theoreticalMaximumLoss,value.riskBudgetUsdt,m.rewardRisk));
        changed(execution,String.format(Locale.FRANCE,
                "Levier visuel x%d · notionnel %.2f · marge estimée %.2f USDT\nLIMIT %s maintenant",
                value.leverage,m.notional,m.estimatedMargin,m.currentlyExecutable?"exécutable":"non exécutable"));
        changed(warning,"");
    }

    private void addCopy(LinearLayout row,String label,int kind){Button b=new Button(getContext());b.setText(label);
        b.setAllCaps(false);b.setTextSize(11);b.setTextColor(TEXT);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
        b.setOnClickListener(v->copy(kind));LayoutParams p=new LayoutParams(0,dp(48),1);p.setMargins(dp(2),0,dp(2),0);row.addView(b,p);}
    private void copy(int kind){if(model==null)return;String value=kind==0?money(model.entry):kind==1?money(model.takeProfit):kind==2?money(model.stopLoss):
            model.symbol+" "+model.side+" · "+model.quantity+" "+model.asset+" · LIMIT "+money(model.entry)+" · TP "+money(model.takeProfit)+" · SL "+money(model.stopLoss);
        ClipboardManager clipboard=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("NMC plan",value));
        Toast.makeText(getContext(),"Copié",Toast.LENGTH_SHORT).show();}
    private static String money(double v){return Double.isFinite(v)&&v>0?String.format(Locale.FRANCE,"%.2f",v):"DONNÉE INDISPONIBLE";}
    private static String integer(int v){return v>=0?String.valueOf(v):"DONNÉE INDISPONIBLE";}
    private static String available(String v){return v==null||v.isEmpty()?"DONNÉE INDISPONIBLE":v;}
    private static String duration(long ms){if(ms<0)return "DONNÉE INDISPONIBLE";long s=ms/1000;return s<60?s+" s":(s/60)+" min "+(s%60)+" s";}
    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}
    private static String number(double value){return positive(value)?String.format(Locale.FRANCE,"%.2f",value):"DONNÉE INDISPONIBLE";}
    private TextView text(String value,int size,int color,boolean bold,boolean mono){TextView out=new TextView(getContext());out.setText(value);out.setTextSize(size);out.setTextColor(color);out.setTypeface(mono?Typeface.MONOSPACE:Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);out.setLineSpacing(dp(2),1);return out;}
    private GradientDrawable background(){GradientDrawable d=new GradientDrawable();d.setColor(CARD);d.setCornerRadius(dp(16));d.setStroke(dp(1),Color.rgb(38,57,78));return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static void changed(TextView view,String value){if(!String.valueOf(view.getText()).equals(value))view.setText(value);}
}
