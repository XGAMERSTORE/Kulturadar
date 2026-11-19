package cz.nikinkawow;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public class MainActivity extends Activity {
    private final int BLACK=Color.BLACK, INK=Color.rgb(125,125,125), DIM=Color.rgb(85,85,85);
    private LinearLayout root, contentArea;
    private TextView status;
    private final ArrayList<Story> stories=new ArrayList<>();
    private final String[] topics={"Nightwish Tarja","M W Craven Washington Poe","muzikál Rebecca","Tolkien Lord of the Rings","Scotland northern lights","fantasy books","orchids botany","archaeology discoveries","horses science","fantasy cinema"};
    private final String[] questions={
        "Kdybys mohla slyšet jednu skladbu Nightwish naživo v původním obsazení, kterou by sis vybrala?",
        "Co by podle tebe Poe s Tilly vyřešili rychleji než běžná policie?",
        "Kdybys mohla vidět Rebeccu v libovolném divadle na světě, které by to bylo?",
        "Které místo ze Středozemě bys chtěla vidět doopravdy?",
        "Chtěla bys radši pozorovat polární záři ve Skotsku, nebo strávit noc na odlehlé chatě?",
        "Jaká fantasy kniha si podle tebe zaslouží opravdu věrné filmové zpracování?",
        "Která neobvyklá orchidej je podle tebe nejkrásnější?",
        "Jaký historický objev by tě přiměl okamžitě vyrazit do muzea?",
        "Kdybys mohla strávit den u koní, co bys chtěla vyzkoušet?",
        "Který fantasy film má podle tebe nejpůsobivější hudbu?"
    };
    private final String[] prompts={"Hudba","Detektivky","Muzikály","Fantasy","Cestování","Knihy","Příroda","Historie","Koně","Filmy"};
    private final HashSet<String> liked=new HashSet<>();
    private int current=0;
    static class Story {
        final String title,url,category;
        Story(String t,String u,String c){title=t;url=u;category=c;}
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        getWindow().getDecorView().setSystemUiVisibility(5894|1024|512);
        liked.addAll(getPreferences(0).getStringSet("liked",new HashSet<>()));
        setup();
        loadCache();
        refresh();
    }
    private TextView text(String s,int size,int color){
        TextView v=new TextView(this);v.setText(s);v.setTextColor(color);v.setTextSize(size);
        v.setPadding(2,12,2,12);return v;
    }
    private GradientDrawable background(){
        GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(10,10,10));
        d.setCornerRadius(14);d.setStroke(1,Color.rgb(27,27,27));return d;
    }
    private Button button(String title){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);
        b.setTextColor(Color.rgb(195,195,195));b.setTextSize(14);b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(25,25,25)));b.setBackground(background());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,54);lp.topMargin=10;b.setLayoutParams(lp);return b;
    }
    private void setup(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BLACK);
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(22,34,22,30);
        scroll.addView(root);setContentView(scroll);
        TextView header=text("NIKINKA  /  WOW",13,INK);header.setTypeface(null,Typeface.BOLD);root.addView(header);
        root.addView(text("Téma na dnešek",25,Color.rgb(190,190,190)));
        status=text("Načítám internetové novinky…",12,DIM);root.addView(status);
        Button update=button("Obnovit novinky");update.setOnClickListener(v->refresh());root.addView(update);
        Button next=button("Další téma");next.setOnClickListener(v->{if(!stories.isEmpty()){current=(current+1)%stories.size();render();}});root.addView(next);
        contentArea=new LinearLayout(this);contentArea.setOrientation(1);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=18;
        root.addView(contentArea,lp);
    }
    private void loadCache(){
        String cached=getPreferences(0).getString("stories","");
        if(!cached.isEmpty())for(String line:cached.split("\\n")){
            String[] p=line.split("\\t",3);if(p.length==3)stories.add(new Story(p[0],p[1],p[2]));
        }
        if(stories.isEmpty())for(int i=0;i<questions.length;i++)
            stories.add(new Story(questions[i],"",prompts[i]));
        render();
    }
    private void refresh(){
        status.setText("Hledám čerstvé zprávy…");
        new Thread(()->{
            ArrayList<Story> found=new ArrayList<>();String lastError="";
            for(int i=0;i<topics.length;i++){
                try {
                    String q=URLEncoder.encode(topics[i],"UTF-8");
                    String[] feeds={
                        "https://www.bing.com/news/search?q="+q+"&format=rss",
                        "https://news.google.com/rss/search?q="+q+"&hl=en&gl=US&ceid=US:en"
                    };
                    for(String feed:feeds){
                        try{
                            HttpURLConnection c=(HttpURLConnection)new URL(feed).openConnection();
                            c.setConnectTimeout(6000);c.setReadTimeout(6000);
                            c.setRequestProperty("User-Agent","Mozilla/5.0 (compatible; NikinkaWOW/1.1)");
                            try(InputStream stream=c.getInputStream()){
                                DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
                                factory.setExpandEntityReferences(false);
                                try { factory.setFeature("http://xml.org/sax/features/external-general-entities",false); } catch(Exception ignored) {}
                                try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false); } catch(Exception ignored) {}
                                Document doc=factory.newDocumentBuilder().parse(stream);
                                NodeList items=doc.getElementsByTagName("item");
                                for(int j=0;j<Math.min(items.getLength(),4);j++){
                                    Element item=(Element)items.item(j);
                                    String title=value(item,"title"),link=value(item,"link");
                                    if(!title.isEmpty()&&link.startsWith("https://"))
                                        found.add(new Story(title,link,prompts[i]));
                                }
                            }finally{c.disconnect();}
                            if(items.getLength()>0)break;
                        }catch(Exception e){lastError=e.getClass().getSimpleName()+": "+e.getMessage();}
                    }
                }catch(Exception e){lastError=e.getClass().getSimpleName();}
            }
            final String error=lastError;
            runOnUiThread(()->{
                if(found.isEmpty()){
                    status.setText("Online zprávy momentálně nedostupné. Zobrazena připravená témata.");
                }else{
                    HashSet<String> unique=new HashSet<>();ArrayList<Story> clean=new ArrayList<>();
                    for(Story s:found)if(unique.add(s.title))clean.add(s);
                    Collections.sort(clean,(a,b)->Boolean.compare(liked.contains(b.category),liked.contains(a.category)));
                    stories.clear();stories.addAll(clean);current=0;
                    StringBuilder cache=new StringBuilder();
                    for(Story s:clean)cache.append(s.title.replace("\t"," ").replace("\n"," ")).append("\t").append(s.url).append("\t").append(s.category).append("\n");
                    getPreferences(0).edit().putString("stories",cache.toString()).apply();
                    status.setText("Online • "+clean.size()+" nalezených témat");
                }
                render();
            });
        }).start();
    }
    private static String value(Element e,String tag){
        NodeList nodes=e.getElementsByTagName(tag);
        return nodes.getLength()>0?nodes.item(0).getTextContent().trim():"";
    }
    private void render(){
        if(contentArea==null||stories.isEmpty())return;
        contentArea.removeAllViews();Story s=stories.get(current);
        contentArea.addView(text(s.category.toUpperCase(Locale.ROOT),11,DIM));
        contentArea.addView(text(s.title,20,Color.rgb(200,200,200)));
        String question=s.url.isEmpty()?"Tohle je připravené konverzační téma.":"Co tě na téhle novince nejvíc překvapilo? A co si o tom myslíš?";
        contentArea.addView(text(question,14,INK));
        if(!s.url.isEmpty()){
            Button open=button("Otevřít zdroj");
            open.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(s.url)));}catch(Exception ignored){}});
            contentArea.addView(open);
        }
        Button favorite=button(liked.contains(s.category)?"♥ Zajímavé":"♡ Tohle Niki zajímá");
        favorite.setOnClickListener(v->{liked.add(s.category);getPreferences(0).edit().putStringSet("liked",new HashSet<>(liked)).apply();render();});
        contentArea.addView(favorite);
    }
}
