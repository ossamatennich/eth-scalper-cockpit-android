package com.ethscalper.cockpit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Premium operational UI for manually executed V4 plans. */
public final class V4MainActivity extends Activity {
    private static final int BG=Color.rgb(5,10,19),CARD=Color.rgb(15,24,38),CARD_ALT=Color.rgb(18,29,45);
    private static final int BORDER=Color.rgb(39,58,82),MUTED=Color.rgb(148,161,184),TEXT=Color.rgb(245,248,252);
    private static final int ACCENT=Color.rgb(53,125,255),GREEN=Color.rgb(45,204,132),RED=Color.rgb(255,81,91),AMBER=Color.rgb(245,174,63);

    private FrameLayout content;
    private LinearLayout header,bottomNav;
    private TextView modeChip,scanner,lastAnalysis;
    private View scannerDot;
    private final Button[] navButtons=new Button[3];
    private int tab;
    private boolean registered;
    private String historyFilter="TOUS";

    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){render();}};

    @Override protected void onCreate(Bundle state){
        setTheme(R.style.AppTheme);
        super.onCreate(state);
        configureSystemBars();
        buildShell();
        ContextCompat.startForegroundService(this,new Intent(this,MarketWatchService.class).setAction(MarketWatchService.ACTION_START));
        V4RuntimeCoordinator.start(this);
        render();
    }

    @Override protected void onStart(){
        super.onStart();
        if(!registered){
            ContextCompat.registerReceiver(this,receiver,new IntentFilter(V4RuntimeCoordinator.ACTION_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);
            registered=true;
        }
        render();
    }

    @Override protected void onStop(){
        if(registered){unregisterReceiver(receiver);registered=false;}
        super.onStop();
    }

    private void configureSystemBars(){
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(BG);
        WindowInsetsControllerCompat controller=new WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }

    private void buildShell(){
        LinearLayout root=column();
        root.setBackgroundColor(BG);

        header=column();
        LinearLayout brandRow=new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("NMC",34,TEXT,true);
        brandRow.addView(brand);
        modeChip=text("EVAL",13,TEXT,true);
        modeChip.setGravity(Gravity.CENTER);
        modeChip.setBackground(round(Color.rgb(31,48,73),99));
        modeChip.setPadding(dp(13),dp(7),dp(13),dp(7));
        LinearLayout.LayoutParams chipParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.setMargins(dp(12),0,0,0);
        brandRow.addView(modeChip,chipParams);
        brandRow.addView(new Space(this),new LinearLayout.LayoutParams(0,1,1));
        Button settings=button("⚙",true);
        settings.setTextSize(23);
        settings.setContentDescription("Réglages");
        settings.setBackground(roundStroke(Color.rgb(13,22,35),14,BORDER,1));
        settings.setOnClickListener(view->settings());
        brandRow.addView(settings,new LinearLayout.LayoutParams(dp(50),dp(50)));
        header.addView(brandRow);

        LinearLayout statusRow=new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        scannerDot=new View(this);
        scannerDot.setBackground(round(ACCENT,99));
        LinearLayout.LayoutParams dotParams=new LinearLayout.LayoutParams(dp(9),dp(9));
        dotParams.setMargins(dp(2),0,dp(10),0);
        statusRow.addView(scannerDot,dotParams);
        scanner=text("SYNCHRO",14,ACCENT,true);
        statusRow.addView(scanner);
        lastAnalysis=text("Première synchronisation",12,MUTED,false);
        lastAnalysis.setGravity(Gravity.END);
        statusRow.addView(lastAnalysis,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0,dp(15),0,dp(13));
        header.addView(statusRow,statusParams);
        View divider=new View(this);
        divider.setBackgroundColor(Color.rgb(29,42,60));
        header.addView(divider,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(1)));
        root.addView(header);

        content=new FrameLayout(this);
        LinearLayout.LayoutParams contentParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1);
        root.addView(content,contentParams);

        bottomNav=new LinearLayout(this);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setBackground(roundStroke(Color.rgb(11,20,32),18,BORDER,1));
        String[] names={"ACCUEIL","PLANS","HISTORIQUE"};
        for(int i=0;i<names.length;i++){
            final int selected=i;
            Button nav=button(names[i],true);
            nav.setTextSize(12);
            nav.setMinHeight(dp(54));
            nav.setOnClickListener(view->{tab=selected;render();});
            navButtons[i]=nav;
            bottomNav.addView(nav,new LinearLayout.LayoutParams(0,dp(54),1));
        }
        LinearLayout.LayoutParams navParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        navParams.setMargins(dp(18),dp(6),dp(18),0);
        root.addView(bottomNav,navParams);

        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            Insets ime=insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left,0,bars.right,0);
            header.setPadding(dp(20),bars.top+dp(12),dp(20),0);
            bottomNav.setPadding(dp(6),dp(5),dp(6),Math.max(bars.bottom,ime.bottom)+dp(8));
            return insets;
        });
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private void render(){
        V4RuntimeCoordinator runtime=V4RuntimeCoordinator.get();
        if(runtime==null)return;
        modeChip.setText(runtime.account().mode().name());
        org.json.JSONObject status=runtime.status();
        String state=status.optString("scannerState","SYNCHRO");
        scanner.setText(state);
        int stateColor="ACTIF".equals(state)?GREEN:"HORS LIGNE".equals(state)?RED:ACCENT;
        scanner.setTextColor(stateColor);
        scannerDot.setBackground(round(stateColor,99));
        long analysedAt=status.optLong("lastAnalysisAt");
        lastAnalysis.setText(analysedAt>0?"Analyse · "+time(analysedAt):"Première synchronisation");
        updateNavigation();
        content.removeAllViews();
        content.addView(tab==0?home(runtime):tab==1?plans(runtime):history(runtime),new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void updateNavigation(){
        for(int i=0;i<navButtons.length;i++){
            boolean selected=i==tab;
            navButtons[i].setTextColor(selected?ACCENT:MUTED);
            navButtons[i].setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);
            navButtons[i].setBackground(selected?round(Color.rgb(18,40,78),14):null);
        }
    }

    private View home(V4RuntimeCoordinator runtime){
        LinearLayout body=screenBody();
        List<V4Plan> ordered=V4PlanDisplayPolicy.homeOrder(runtime.store().active());
        if(ordered.isEmpty()){
            LinearLayout empty=card(false);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(24),dp(56),dp(24),dp(56));
            empty.addView(text("Scanner actif",26,TEXT,true));
            empty.addView(text("53 marchés surveillés",16,MUTED,false));
            TextView waiting=text("En attente d'un plan valide",15,MUTED,false);
            LinearLayout.LayoutParams waitingParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            waitingParams.setMargins(0,dp(22),0,0);
            empty.addView(waiting,waitingParams);
            body.addView(empty);
        }else{
            body.addView(sectionTitle("PLAN ACTIF",ACCENT));
            body.addView(planCard(ordered.get(0),true));
            if(ordered.size()>1){
                body.addView(sectionTitle("AUTRES PLANS · "+(ordered.size()-1),MUTED));
                for(int i=1;i<ordered.size();i++)body.addView(planCard(ordered.get(i),false));
                Button all=button("VOIR TOUS LES PLANS",true);
                all.setTextColor(ACCENT);
                all.setOnClickListener(view->{tab=1;render();});
                body.addView(all);
            }
        }
        return scroll(body);
    }

    private View plans(V4RuntimeCoordinator runtime){
        LinearLayout body=screenBody();
        List<V4Plan> active=runtime.store().active();
        List<V4Plan> all=runtime.store().all();
        section(body,"EXÉCUTABLES",active,V4Plan.Status.EXECUTABLE);
        section(body,"ORDRES LIMITES POSSIBLES",active,V4Plan.Status.LIMIT_ORDER_POSSIBLE);
        section(body,"EN ATTENTE",active,V4Plan.Status.WAITING,V4Plan.Status.DATA_UNAVAILABLE);
        section(body,"ORDRES POSÉS",active,V4Plan.Status.ORDER_PLACED);
        section(body,"EN COURS",active,V4Plan.Status.OPEN);
        ArrayList<V4Plan> terminal=new ArrayList<>();
        for(V4Plan plan:all)if(plan.terminal())terminal.add(plan);
        section(body,"TERMINÉS / EXPIRÉS",terminal,V4Plan.Status.MISSED_TOO_LATE,V4Plan.Status.INVALIDATED,V4Plan.Status.EXPIRED,
                V4Plan.Status.CLOSED_TP,V4Plan.Status.CLOSED_SL,V4Plan.Status.CLOSED_MANUAL,V4Plan.Status.CLOSED_OTHER);
        return scroll(body);
    }

    private void section(LinearLayout body,String title,List<V4Plan> plans,V4Plan.Status... statuses){
        List<V4Plan.Status> accepted=Arrays.asList(statuses);
        ArrayList<V4Plan> matches=new ArrayList<>();
        for(V4Plan plan:plans)if(accepted.contains(plan.status))matches.add(plan);
        body.addView(sectionTitle(title+" · "+matches.size(),matches.isEmpty()?MUTED:ACCENT));
        if(matches.isEmpty()){
            TextView none=text("Aucun plan",13,Color.rgb(102,116,138),false);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(12),0,0,dp(12));
            body.addView(none,params);
        }else for(V4Plan plan:matches)body.addView(planCard(plan,false));
    }

    private View history(V4RuntimeCoordinator runtime){
        LinearLayout body=screenBody();
        HorizontalScrollView filterScroll=new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters=new LinearLayout(this);
        for(String value:new String[]{"TOUS","GAGNÉS","PERDUS","EXPIRÉS / NON PRIS"}){
            Button filter=button(value,true);
            filter.setTextColor(value.equals(historyFilter)?ACCENT:MUTED);
            filter.setBackground(value.equals(historyFilter)?round(Color.rgb(18,40,78),14):null);
            filter.setOnClickListener(view->{historyFilter=value;render();});
            filters.addView(filter);
        }
        filterScroll.addView(filters);
        body.addView(filterScroll);
        boolean any=false;
        for(V4Plan plan:runtime.store().all())if(plan.terminal()&&matchesHistory(plan)){
            any=true;
            LinearLayout row=card(false);
            row.setOnClickListener(view->detail(plan));
            TextView title=text(plan.side.name()+" "+plan.symbol,19,plan.side==V4Plan.Side.LONG?GREEN:RED,true);
            title.setSingleLine(true);
            row.addView(title);
            row.addView(text(day(plan.createdAt)+"  ·  "+V4Plan.french(plan.status),13,MUTED,false));
            row.addView(text("ENTRY  "+fmt(plan.entry)+"     SORTIE  "+(plan.closePrice>0?fmt(plan.closePrice):"—"),14,TEXT,false));
            body.addView(row);
        }
        if(!any)body.addView(text("L'historique V4 apparaîtra ici.",15,MUTED,false));
        return scroll(body);
    }

    private boolean matchesHistory(V4Plan plan){
        return historyFilter.equals("TOUS")
                ||historyFilter.equals("GAGNÉS")&&plan.status==V4Plan.Status.CLOSED_TP
                ||historyFilter.equals("PERDUS")&&plan.status==V4Plan.Status.CLOSED_SL
                ||historyFilter.startsWith("EXPIRÉS")&&(plan.status==V4Plan.Status.EXPIRED||plan.status==V4Plan.Status.MISSED_TOO_LATE||plan.status==V4Plan.Status.INVALIDATED);
    }

    private View planCard(V4Plan plan,boolean hero){
        LinearLayout card=card(hero);
        card.setOnClickListener(view->detail(plan));

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(plan.side.name()+" "+plan.symbol,hero?30:21,plan.side==V4Plan.Side.LONG?GREEN:RED,true);
        title.setSingleLine(true);
        title.setAutoSizeTextTypeUniformWithConfiguration(hero?18:15,hero?30:21,1,TypedValue.COMPLEX_UNIT_SP);
        top.addView(title,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        TextView badge=text(V4Plan.french(plan.status),11,TEXT,true);
        badge.setGravity(Gravity.CENTER);
        badge.setMaxLines(2);
        badge.setPadding(dp(10),dp(7),dp(10),dp(7));
        badge.setBackground(roundStroke(statusColor(plan.status),99,statusBorder(plan.status),1));
        LinearLayout.LayoutParams badgeParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.setMargins(dp(10),0,0,0);
        top.addView(badge,badgeParams);
        card.addView(top);

        TextView quantityLabel=text("QTÉ",12,MUTED,true);
        LinearLayout.LayoutParams quantityLabelParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        quantityLabelParams.setMargins(0,hero?dp(28):dp(18),0,0);
        card.addView(quantityLabel,quantityLabelParams);
        TextView quantity=text(fmt(plan.quantity()),hero?36:27,TEXT,true);
        quantity.setSingleLine(true);
        card.addView(quantity);

        LinearLayout levels=new LinearLayout(this);
        LinearLayout.LayoutParams levelsParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        levelsParams.setMargins(0,hero?dp(20):dp(14),0,dp(14));
        addLevel(levels,"ENTRY",plan.entry,ACCENT,0);
        addLevel(levels,"TP",plan.tp,GREEN,1);
        addLevel(levels,"SL",plan.sl,RED,2);
        card.addView(levels,levelsParams);

        LinearLayout timing=new LinearLayout(this);
        timing.setPadding(dp(12),dp(9),dp(12),dp(9));
        timing.setBackground(roundStroke(Color.rgb(17,29,44),13,BORDER,1));
        timing.addView(metaText("Créé",time(plan.createdAt)),new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        View separator=new View(this);
        separator.setBackgroundColor(BORDER);
        timing.addView(separator,new LinearLayout.LayoutParams(dp(1),dp(32)));
        TextView expires=metaText("Expire",time(plan.expiresAt));
        expires.setGravity(Gravity.END);
        timing.addView(expires,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        card.addView(timing);

        if(!plan.statusReason.isEmpty()){
            TextView reason=text("ⓘ  "+plan.statusReason,13,MUTED,false);
            reason.setBackground(roundStroke(Color.rgb(17,29,44),13,BORDER,1));
            reason.setPadding(dp(13),dp(11),dp(13),dp(11));
            LinearLayout.LayoutParams reasonParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            reasonParams.setMargins(0,dp(12),0,0);
            card.addView(reason,reasonParams);
        }

        if(plan.status==V4Plan.Status.LIMIT_ORDER_POSSIBLE){
            Button action=primaryAction("ORDRE POSÉ");
            action.setOnClickListener(view->{V4RuntimeCoordinator.get().markOrder(plan.planId);render();});
            card.addView(action,actionParams());
        }
        if(plan.status==V4Plan.Status.EXECUTABLE){
            Button action=primaryAction("TRADE PRIS");
            action.setOnClickListener(view->{V4RuntimeCoordinator.get().markTaken(plan.planId);render();});
            card.addView(action,actionParams());
        }
        return card;
    }

    private void addLevel(LinearLayout parent,String name,double value,int accent,int index){
        LinearLayout level=column();
        level.setGravity(Gravity.CENTER_HORIZONTAL);
        level.setPadding(dp(9),dp(11),dp(9),dp(9));
        level.setBackground(roundStroke(Color.rgb(14,24,38),13,accent,1));
        TextView label=text(name,11,MUTED,true);
        label.setGravity(Gravity.START);
        level.addView(label,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        String exact=fmt(value);
        TextView price=text(exact,17,TEXT,true);
        price.setGravity(Gravity.START);
        price.setSingleLine(true);
        price.setAutoSizeTextTypeUniformWithConfiguration(10,17,1,TypedValue.COMPLEX_UNIT_SP);
        level.addView(price,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(33)));
        Button copy=button("▣",true);
        copy.setTextSize(19);
        copy.setContentDescription("Copier "+name);
        copy.setBackground(roundStroke(Color.rgb(21,44,83),10,Color.rgb(51,91,151),1));
        copy.setOnClickListener(view->copyValue(name,exact));
        LinearLayout.LayoutParams copyParams=new LinearLayout.LayoutParams(dp(38),dp(38));
        copyParams.setMargins(0,dp(5),0,0);
        level.addView(copy,copyParams);
        LinearLayout.LayoutParams levelParams=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);
        if(index>0)levelParams.setMargins(dp(7),0,0,0);
        parent.addView(level,levelParams);
    }

    private void copyValue(String label,String value){
        ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText(label,value));
        Toast.makeText(this,"Copié",Toast.LENGTH_SHORT).show();
    }

    private TextView metaText(String label,String value){
        TextView text=text(label+"  "+value,12,MUTED,false);
        text.setSingleLine(true);
        text.setAutoSizeTextTypeUniformWithConfiguration(9,12,1,TypedValue.COMPLEX_UNIT_SP);
        return text;
    }

    private Button primaryAction(String label){
        Button action=button(label,false);
        action.setTextSize(15);
        action.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        action.setBackground(round(ACCENT,14));
        return action;
    }

    private LinearLayout.LayoutParams actionParams(){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54));
        params.setMargins(0,dp(14),0,0);
        return params;
    }

    private View sectionTitle(String label,int color){
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View marker=new View(this);
        marker.setBackground(round(color,99));
        row.addView(marker,new LinearLayout.LayoutParams(dp(4),dp(25)));
        TextView title=text(label,13,color,true);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(dp(11),0,0,0);
        row.addView(title,titleParams);
        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0,dp(17),0,dp(6));
        row.setLayoutParams(rowParams);
        return row;
    }

    private void detail(V4Plan plan){
        LinearLayout view=column();
        view.setPadding(dp(20),0,dp(20),0);
        TextView title=text(plan.side.name()+" "+plan.symbol,24,plan.side==V4Plan.Side.LONG?GREEN:RED,true);
        title.setSingleLine(true);
        view.addView(title);
        view.addView(text("Quantité  "+fmt(plan.quantity())+"\nENTRY  "+fmt(plan.entry)+"\nTP  "+fmt(plan.tp)+"\nSL  "+fmt(plan.sl)+"\n\n"+V4Plan.french(plan.status)+"\n"+plan.statusReason,16,TEXT,false));
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Détail du plan").setView(view).setNegativeButton("FERMER",null);
        if(plan.status==V4Plan.Status.OPEN)dialog.setPositiveButton("FERMÉ MANUELLEMENT",(ignored,which)->manualClose(plan));
        dialog.show();
    }

    private void manualClose(V4Plan plan){
        EditText price=input(fmt(plan.entry));
        new AlertDialog.Builder(this).setTitle("Prix de clôture").setView(price).setNegativeButton("ANNULER",null)
                .setPositiveButton("CONFIRMER",(ignored,which)->{try{V4RuntimeCoordinator.get().manualClose(plan.planId,Double.parseDouble(price.getText().toString()));render();}catch(Exception ignoredError){}}).show();
    }

    private void settings(){
        V4RuntimeCoordinator runtime=V4RuntimeCoordinator.get();
        LinearLayout view=column();
        view.setPadding(dp(22),0,dp(22),0);
        EditText equity=input(fmt(runtime.account().equity())),target=input(fmt(runtime.account().target())),mdd=input(fmt(runtime.account().mdd()*100));
        view.addView(label("Équité suivie",equity));
        view.addView(label("Objectif d'évaluation",target));
        view.addView(label("MDD cumulée (%)",mdd));
        Button reset=button("Réinitialiser à 5 000",true);
        reset.setOnClickListener(button->new AlertDialog.Builder(this).setTitle("Réinitialiser l'équité ?").setMessage("Le profil local reviendra à 5 000 USD.")
                .setNegativeButton("ANNULER",null).setPositiveButton("CONFIRMER",(ignored,which)->{runtime.account().reset();render();}).show());
        view.addView(reset);
        new AlertDialog.Builder(this).setTitle("Profil du compte · "+runtime.account().mode()).setView(view)
                .setNeutralButton("EVAL / FUNDED",(ignored,which)->{V4AccountProfile.Mode next=runtime.account().mode()==V4AccountProfile.Mode.EVAL?V4AccountProfile.Mode.FUNDED:V4AccountProfile.Mode.EVAL;runtime.account().update(next,runtime.account().equity(),runtime.account().target(),runtime.account().mdd());render();})
                .setNegativeButton("ANNULER",null).setPositiveButton("ENREGISTRER",(ignored,which)->{try{runtime.account().update(runtime.account().mode(),Double.parseDouble(equity.getText().toString()),Double.parseDouble(target.getText().toString()),Double.parseDouble(mdd.getText().toString())/100);render();}catch(Exception ignoredError){}}).show();
    }

    private View label(String label,View input){LinearLayout value=column();value.addView(text(label,12,MUTED,true));value.addView(input);return value;}
    private EditText input(String value){EditText input=new EditText(this);input.setText(value);input.setTextColor(TEXT);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);return input;}

    private LinearLayout screenBody(){LinearLayout body=column();body.setPadding(dp(18),0,dp(18),dp(18));return body;}
    private LinearLayout card(boolean hero){
        LinearLayout card=column();
        card.setPadding(hero?dp(20):dp(17),hero?dp(20):dp(17),hero?dp(20):dp(17),hero?dp(20):dp(17));
        card.setBackground(roundStroke(hero?CARD_ALT:CARD,hero?20:17,hero?Color.rgb(55,104,171):BORDER,1));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0,dp(7),0,dp(11));
        card.setLayoutParams(params);
        card.setElevation(dp(hero?4:2));
        return card;
    }

    private ScrollView scroll(View child){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(child,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private TextView text(String value,float size,int color,boolean bold){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(color);if(bold)text.setTypeface(Typeface.DEFAULT,Typeface.BOLD);text.setLineSpacing(0,1.12f);text.setPadding(0,dp(3),0,dp(3));return text;}
    private Button button(String value,boolean flat){Button button=new Button(this);button.setText(value);button.setTextSize(12);button.setTextColor(flat?MUTED:TEXT);button.setAllCaps(false);button.setBackground(flat?null:round(ACCENT,14));button.setMinHeight(dp(48));button.setPadding(dp(8),0,dp(8),0);return button;}
    private android.graphics.drawable.GradientDrawable round(int color,float radius){return roundStroke(color,radius,color,0);}
    private android.graphics.drawable.GradientDrawable roundStroke(int color,float radius,int stroke,int width){android.graphics.drawable.GradientDrawable drawable=new android.graphics.drawable.GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp((int)radius));if(width>0)drawable.setStroke(dp(width),stroke);return drawable;}
    private int statusColor(V4Plan.Status status){return switch(status){case EXECUTABLE,OPEN,CLOSED_TP->Color.rgb(20,105,75);case INVALIDATED,CLOSED_SL,MISSED_TOO_LATE->Color.rgb(121,39,51);case EXPIRED,DATA_UNAVAILABLE->Color.rgb(79,65,39);default->Color.rgb(25,54,98);};}
    private int statusBorder(V4Plan.Status status){return switch(status){case EXECUTABLE,OPEN,CLOSED_TP->GREEN;case INVALIDATED,CLOSED_SL,MISSED_TOO_LATE->RED;case EXPIRED,DATA_UNAVAILABLE->AMBER;default->ACCENT;};}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static String fmt(double value){return new DecimalFormat("0.########").format(value);}
    private static String time(long value){return date(value,"dd/MM HH:mm 'UTC'");}
    private static String day(long value){return date(value,"dd/MM/yyyy");}
    private static String date(long value,String pattern){SimpleDateFormat format=new SimpleDateFormat(pattern,Locale.FRANCE);format.setTimeZone(TimeZone.getTimeZone("UTC"));return format.format(new Date(value));}
}
