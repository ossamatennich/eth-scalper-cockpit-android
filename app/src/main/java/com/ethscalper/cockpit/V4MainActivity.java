package com.ethscalper.cockpit;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Minimal operational UI for manually executed V4 plans. */
public final class V4MainActivity extends AppCompatActivity {
    private static final int BG=Color.rgb(8,12,19),CARD=Color.rgb(18,25,36),MUTED=Color.rgb(146,158,178),TEXT=Color.rgb(241,245,249),ACCENT=Color.rgb(67,135,255);
    private FrameLayout content;private TextView modeChip,scanner,lastAnalysis;private int tab;private boolean registered;private String historyFilter="TOUS";
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){render();}};
    @Override protected void onCreate(Bundle state){setTheme(R.style.AppTheme);super.onCreate(state);buildShell();
        ContextCompat.startForegroundService(this,new Intent(this,MarketWatchService.class).setAction(MarketWatchService.ACTION_START));V4RuntimeCoordinator.start(this);render();}
    @Override protected void onStart(){super.onStart();if(!registered){ContextCompat.registerReceiver(this,receiver,new IntentFilter(V4RuntimeCoordinator.ACTION_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;}}
    @Override protected void onStop(){if(registered){unregisterReceiver(receiver);registered=false;}super.onStop();}
    private void buildShell(){LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(18),dp(16),dp(18),dp(10));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);TextView brand=text("NMC",26,TEXT,true);head.addView(brand);
        modeChip=text("EVAL",12,TEXT,true);modeChip.setBackground(round(Color.rgb(35,48,67),99));modeChip.setPadding(dp(10),dp(5),dp(10),dp(5));LinearLayout.LayoutParams chipLp=new LinearLayout.LayoutParams(-2,-2);chipLp.setMargins(dp(10),0,0,0);head.addView(modeChip,chipLp);
        Space space=new Space(this);head.addView(space,new LinearLayout.LayoutParams(0,1,1));Button settings=button("⚙",false);settings.setContentDescription("Réglages");settings.setOnClickListener(v->settings());head.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(head);
        LinearLayout sub=new LinearLayout(this);sub.setGravity(Gravity.CENTER_VERTICAL);scanner=text("SYNCHRO",13,ACCENT,true);lastAnalysis=text("",12,MUTED,false);sub.addView(scanner);sub.addView(lastAnalysis,new LinearLayout.LayoutParams(0,-2,1));lastAnalysis.setGravity(Gravity.END);root.addView(sub);
        content=new FrameLayout(this);LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(-1,0,1);clp.setMargins(0,dp(14),0,dp(8));root.addView(content,clp);
        LinearLayout nav=new LinearLayout(this);String[] names={"ACCUEIL","PLANS","HISTORIQUE"};for(int i=0;i<names.length;i++){final int at=i;Button b=button(names[i],true);b.setOnClickListener(v->{tab=at;render();});nav.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));}root.addView(nav);setContentView(root);}
    private void render(){V4RuntimeCoordinator runtime=V4RuntimeCoordinator.get();if(runtime==null)return;modeChip.setText(runtime.account().mode().name());
        org.json.JSONObject s=runtime.status();scanner.setText(s.optString("scannerState","SYNCHRO"));long at=s.optLong("lastAnalysisAt");lastAnalysis.setText(at>0?"Analyse · "+time(at):"Première synchronisation");
        content.removeAllViews();content.addView(tab==0?home(runtime):tab==1?plans(runtime):history(runtime));}
    private View home(V4RuntimeCoordinator runtime){LinearLayout body=column();List<V4Plan> active=runtime.store().active();V4Plan primary=null;for(V4Plan p:active)if(p.status==V4Plan.Status.EXECUTABLE||p.status==V4Plan.Status.LIMIT_ORDER_POSSIBLE||p.status==V4Plan.Status.ORDER_PLACED||p.status==V4Plan.Status.OPEN){primary=p;break;}
        if(primary==null){LinearLayout empty=card();empty.setGravity(Gravity.CENTER_HORIZONTAL);empty.setPadding(dp(24),dp(44),dp(24),dp(44));empty.addView(text("Scanner actif",24,TEXT,true));empty.addView(text("53 marchés surveillés",15,MUTED,false));TextView wait=text("En attente d'un plan valide",14,MUTED,false);LinearLayout.LayoutParams w=new LinearLayout.LayoutParams(-2,-2);w.setMargins(0,dp(16),0,0);empty.addView(wait,w);body.addView(empty);}else{
            body.addView(text("PLAN ACTIF",13,ACCENT,true));body.addView(planCard(primary,true));int shown=0;for(V4Plan p:active)if(p!=primary&&shown++<1)body.addView(planCard(p,false));if(active.size()>2){Button all=button("Voir tous les plans",true);all.setOnClickListener(v->{tab=1;render();});body.addView(all);}}
        return scroll(body);}
    private View plans(V4RuntimeCoordinator runtime){LinearLayout body=column();List<V4Plan>a=runtime.store().active();section(body,"EXÉCUTABLES MAINTENANT",a,V4Plan.Status.EXECUTABLE);section(body,"ORDRES LIMITES POSSIBLES",a,V4Plan.Status.LIMIT_ORDER_POSSIBLE);
        section(body,"EN ATTENTE",a,V4Plan.Status.WAITING);section(body,"ORDRES POSÉS",a,V4Plan.Status.ORDER_PLACED);section(body,"EN COURS",a,V4Plan.Status.OPEN);if(a.isEmpty())body.addView(text("Aucun plan opérationnel pour le moment.",15,MUTED,false));return scroll(body);}
    private void section(LinearLayout body,String title,List<V4Plan> plans,V4Plan.Status status){boolean any=false;for(V4Plan p:plans)if(p.status==status){if(!any)body.addView(text(title,13,MUTED,true));body.addView(planCard(p,false));any=true;}}
    private View history(V4RuntimeCoordinator runtime){LinearLayout body=column();HorizontalScrollView hs=new HorizontalScrollView(this);LinearLayout filters=new LinearLayout(this);for(String x:new String[]{"TOUS","GAGNÉS","PERDUS","EXPIRÉS / NON PRIS"}){Button f=button(x,true);f.setTextColor(x.equals(historyFilter)?ACCENT:MUTED);f.setOnClickListener(v->{historyFilter=x;render();});filters.addView(f);}hs.addView(filters);body.addView(hs);
        boolean any=false;for(V4Plan p:runtime.store().all())if(p.terminal()&&matchesHistory(p)){any=true;LinearLayout row=card();row.setOnClickListener(v->detail(p));TextView first=text(p.symbol+"  "+p.side.name(),18,p.side==V4Plan.Side.LONG?Color.rgb(63,211,151):Color.rgb(255,102,112),true);row.addView(first);row.addView(text(day(p.createdAt)+"  ·  "+V4Plan.french(p.status),13,MUTED,false));row.addView(text("Entry "+fmt(p.entry)+"   Exit "+(p.closePrice>0?fmt(p.closePrice):"—"),14,TEXT,false));body.addView(row);}if(!any)body.addView(text("L'historique V4 apparaîtra ici.",15,MUTED,false));return scroll(body);}
    private boolean matchesHistory(V4Plan p){return historyFilter.equals("TOUS")||historyFilter.equals("GAGNÉS")&&p.status==V4Plan.Status.CLOSED_TP||historyFilter.equals("PERDUS")&&p.status==V4Plan.Status.CLOSED_SL||historyFilter.startsWith("EXPIRÉS")&&(p.status==V4Plan.Status.EXPIRED||p.status==V4Plan.Status.MISSED_TOO_LATE||p.status==V4Plan.Status.INVALIDATED);}
    private View planCard(V4Plan p,boolean large){LinearLayout c=card();c.setOnClickListener(v->detail(p));LinearLayout top=new LinearLayout(this);TextView side=text(p.side.name(),large?28:20,p.side==V4Plan.Side.LONG?Color.rgb(63,211,151):Color.rgb(255,102,112),true);top.addView(side);TextView sym=text(p.symbol,large?28:20,TEXT,true);LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(0,-2,1);slp.setMargins(dp(10),0,0,0);top.addView(sym,slp);TextView badge=text(V4Plan.french(p.status),11,TEXT,true);badge.setPadding(dp(9),dp(5),dp(9),dp(5));badge.setBackground(round(statusColor(p.status),99));top.addView(badge);c.addView(top);
        TextView qty=text("QTÉ  "+fmt(p.quantity()),large?26:19,TEXT,true);LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(-1,-2);qlp.setMargins(0,dp(18),0,dp(12));c.addView(qty,qlp);
        LinearLayout levels=new LinearLayout(this);levels.addView(level("ENTRY",p.entry),new LinearLayout.LayoutParams(0,-2,1));levels.addView(level("TP",p.tp),new LinearLayout.LayoutParams(0,-2,1));levels.addView(level("SL",p.sl),new LinearLayout.LayoutParams(0,-2,1));c.addView(levels);
        c.addView(text("Créé "+time(p.createdAt)+"  ·  Expire "+time(p.expiresAt),12,MUTED,false));if(!p.statusReason.isEmpty())c.addView(text(p.statusReason,13,MUTED,false));
        if(p.status==V4Plan.Status.LIMIT_ORDER_POSSIBLE){Button b=button("ORDRE POSÉ",false);b.setOnClickListener(v->{V4RuntimeCoordinator.get().markOrder(p.planId);render();});c.addView(b);}
        if(p.status==V4Plan.Status.EXECUTABLE){Button b=button("TRADE PRIS",false);b.setOnClickListener(v->{V4RuntimeCoordinator.get().markTaken(p.planId);render();});c.addView(b);}return c;}
    private View level(String name,double value){LinearLayout x=column();x.addView(text(name,11,MUTED,true));x.addView(text(fmt(value),17,TEXT,true));return x;}
    private void detail(V4Plan p){LinearLayout v=column();v.setPadding(dp(20),0,dp(20),0);v.addView(text(p.symbol+" "+p.side.name(),24,TEXT,true));v.addView(text("Quantité  "+fmt(p.quantity())+"\nEntry  "+fmt(p.entry)+"\nTP  "+fmt(p.tp)+"\nSL  "+fmt(p.sl)+"\n\n"+V4Plan.french(p.status)+"\n"+p.statusReason,16,TEXT,false));
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("Détail du plan").setView(v).setNegativeButton("FERMER",null);if(p.status==V4Plan.Status.OPEN)b.setPositiveButton("FERMÉ MANUELLEMENT",(d,w)->manualClose(p));b.show();}
    private void manualClose(V4Plan p){EditText e=input(fmt(p.entry));new AlertDialog.Builder(this).setTitle("Prix de clôture").setView(e).setNegativeButton("ANNULER",null).setPositiveButton("CONFIRMER",(d,w)->{try{V4RuntimeCoordinator.get().manualClose(p.planId,Double.parseDouble(e.getText().toString()));render();}catch(Exception ignored){}}).show();}
    private void settings(){V4RuntimeCoordinator r=V4RuntimeCoordinator.get();LinearLayout v=column();v.setPadding(dp(22),0,dp(22),0);EditText equity=input(fmt(r.account().equity())),target=input(fmt(r.account().target())),mdd=input(fmt(r.account().mdd()*100));v.addView(label("Équité suivie",equity));v.addView(label("Objectif d'évaluation",target));v.addView(label("MDD cumulée (%)",mdd));
        Button reset=button("Réinitialiser à 5 000",true);reset.setOnClickListener(x->new AlertDialog.Builder(this).setTitle("Réinitialiser l'équité ?").setMessage("Le profil local reviendra à 5 000 USD.").setNegativeButton("ANNULER",null).setPositiveButton("CONFIRMER",(d,w)->{r.account().reset();render();}).show());v.addView(reset);
        new AlertDialog.Builder(this).setTitle("Profil du compte · "+r.account().mode()).setView(v).setNeutralButton("EVAL / FUNDED",(d,w)->{V4AccountProfile.Mode next=r.account().mode()==V4AccountProfile.Mode.EVAL?V4AccountProfile.Mode.FUNDED:V4AccountProfile.Mode.EVAL;r.account().update(next,r.account().equity(),r.account().target(),r.account().mdd());render();})
                .setNegativeButton("ANNULER",null).setPositiveButton("ENREGISTRER",(d,w)->{try{r.account().update(r.account().mode(),Double.parseDouble(equity.getText().toString()),Double.parseDouble(target.getText().toString()),Double.parseDouble(mdd.getText().toString())/100);render();}catch(Exception ignored){}}).show();}
    private View label(String label,View input){LinearLayout x=column();x.addView(text(label,12,MUTED,true));x.addView(input);return x;}
    private EditText input(String value){EditText e=new EditText(this);e.setText(value);e.setTextColor(TEXT);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);return e;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(17),dp(18),dp(17));c.setBackground(round(CARD,18));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(10));c.setLayoutParams(lp);return c;}
    private ScrollView scroll(View child){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.addView(child);return s;}
    private LinearLayout column(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);return x;}
    private TextView text(String value,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLineSpacing(0,1.12f);t.setPadding(0,dp(4),0,dp(4));return t;}
    private Button button(String value,boolean flat){Button b=new Button(this);b.setText(value);b.setTextSize(12);b.setTextColor(flat?MUTED:TEXT);b.setAllCaps(false);b.setBackground(flat?null:round(ACCENT,14));b.setMinHeight(dp(48));return b;}
    private android.graphics.drawable.GradientDrawable round(int color,float radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp((int)radius));return d;}
    private int statusColor(V4Plan.Status s){return switch(s){case EXECUTABLE,OPEN,CLOSED_TP->Color.rgb(25,130,91);case INVALIDATED,CLOSED_SL,MISSED_TOO_LATE->Color.rgb(145,52,62);default->Color.rgb(45,67,99);};}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static String fmt(double v){return new DecimalFormat("0.########").format(v);}private static String time(long v){return date(v,"dd/MM HH:mm 'UTC'");}private static String day(long v){return date(v,"dd/MM/yyyy");}
    private static String date(long v,String p){SimpleDateFormat f=new SimpleDateFormat(p,Locale.FRANCE);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(v));}
}
