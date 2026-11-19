package com.ashrof.bluetoothcollector;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    static final int BT=10, PICK=11, SAVE=12;
    TextView status, devices, preview;
    Button saveButton;
    String exportText="", deviceName="Bluetooth_Device";
    int count=0;
    int ink=Color.rgb(25,32,42), blue=Color.rgb(42,94,207), muted=Color.rgb(102,113,128);

    @Override public void onCreate(Bundle b) { super.onCreate(b); ui(); receive(getIntent()); refresh(); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); receive(i); }

    void ui() {
        ScrollView sc=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(dp(22),dp(28),dp(22),dp(28)); root.setBackgroundColor(Color.rgb(246,248,251)); sc.addView(root);
        TextView brand=txt("BT / CONTACTS",12,blue,true); brand.setLetterSpacing(.13f); root.addView(brand);
        add(root,txt("Bluetooth Contacts",27,ink,true),0,8);
        add(root,txt("استقبل ملف جهات الاتصال بموافقة صاحبه، وحوّله إلى TXT باسم الجهاز.",14,muted,false),0,8);
        Button pair=btn("فتح إعدادات البلوتوث والاقتران"); add(root,pair,dp(54),22); pair.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        Button refresh=btn("تحديث الأجهزة المقترنة"); add(root,refresh,dp(48),10); refresh.setOnClickListener(v->refresh());
        devices=txt("الأجهزة المقترنة هتظهر هنا.",13,ink,false); panel(devices); add(root,devices,-2,12);
        add(root,txt("طريقة النقل",17,ink,true),0,24);
        add(root,txt("1. اقترن بالجهاز من إعدادات البلوتوث.\n2. على الجهاز المصدر اختَر جهات الاتصال ثم مشاركة / تصدير كملف VCF.\n3. أرسل الملف إلى هذا الهاتف عبر Bluetooth.\n4. افتح الملف بهذا التطبيق لحفظ نسخة TXT.",13,muted,false),0,8);
        Button pick=btn("اختيار ملف VCF من الهاتف"); add(root,pick,dp(54),18); pick.setOnClickListener(v->picker());
        status=txt("جاهز لاستقبال ملف جهات الاتصال.",13,blue,true); add(root,status,0,18);
        preview=txt("معاينة البيانات هتظهر هنا بعد الاستيراد.",12,muted,false); panel(preview); add(root,preview,-2,10);
        saveButton=btn("حفظ كملف TXT"); add(root,saveButton,dp(54),14); saveButton.setEnabled(false); saveButton.setAlpha(.45f); saveButton.setOnClickListener(v->save());
        setContentView(sc);
    }
    void add(LinearLayout p, android.view.View v,int h,int top){ LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,h==0?-2:h); lp.topMargin=dp(top); p.addView(v,lp); }
    TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    Button btn(String s){Button b=new Button(this);b.setText(s);b.setTextColor(-1);b.setTextSize(14);b.setAllCaps(false);b.setBackground(round(blue,14));return b;}
    void panel(TextView t){t.setPadding(dp(14),dp(14),dp(14),dp(14));GradientDrawable d=round(-1,16);d.setStroke(dp(1),Color.rgb(228,233,240));t.setBackground(d);}
    GradientDrawable round(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
    int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}

    void picker(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/x-vcard","text/vcard","text/directory","application/octet-stream"});startActivityForResult(i,PICK);}
    void receive(Intent i){if(i==null)return;Uri u=i.getData();if(u==null&&Intent.ACTION_SEND.equals(i.getAction())){if(Build.VERSION.SDK_INT>=33&&i.getClipData()!=null&&i.getClipData().getItemCount()>0)u=i.getClipData().getItemAt(0).getUri();else {Object x=i.getParcelableExtra(Intent.EXTRA_STREAM);if(x instanceof Uri)u=(Uri)x;}}if(u!=null)importFile(u,deviceFrom(i,u));}
    String deviceFrom(Intent i,Uri u){String n=i.getStringExtra(Intent.EXTRA_TITLE);if(n==null)n=u.getLastPathSegment();if(n==null)n="Bluetooth_Device";n=n.replaceAll("(?i)\\.vcf$","").replaceAll("[^A-Za-z0-9_\\-\\u0600-\\u06FF ]","_").trim().replace(' ','_');return n.isEmpty()?"Bluetooth_Device":n;}

    void importFile(Uri uri,String source){
        try(InputStream in=getContentResolver().openInputStream(uri);BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            StringBuilder raw=new StringBuilder();String line;while((line=r.readLine())!=null)raw.append(line).append('\n');
            ArrayList<String> lines=new ArrayList<>();for(String x:raw.toString().split("\\r?\\n")){if((x.startsWith(" ")||x.startsWith("\t"))&&!lines.isEmpty())lines.set(lines.size()-1,lines.get(lines.size()-1)+x.substring(1));else lines.add(x);}
            StringBuilder body=new StringBuilder();String name="",phone="";int total=0;
            for(String x:lines){String up=x.toUpperCase(Locale.ROOT);
                if(up.startsWith("BEGIN:VCARD")){name="";phone="";}
                else if(up.startsWith("FN:"))name=x.substring(x.indexOf(':')+1).trim();
                else if(up.startsWith("N:")&&name.isEmpty())name=x.substring(x.indexOf(':')+1).replace(';',' ').trim();
                else if(up.startsWith("TEL")){String v=x.substring(x.indexOf(':')+1).trim();if(!v.isEmpty())phone+=(phone.isEmpty()?"":" | ")+v;}
                else if(up.startsWith("END:VCARD")){if(!name.isEmpty()||!phone.isEmpty()){total++;body.append("Contact ").append(total).append('\n').append("Name: ").append(name.isEmpty()?"(No name)":name).append('\n').append("Phone: ").append(phone.isEmpty()?"(No phone)":phone).append("\n\n");}}
            }
            if(total==0)throw new Exception("الملف لا يحتوي على جهات اتصال VCF قابلة للقراءة.");
            count=total;deviceName=source;exportText="Device: "+deviceName+"\nImported: "+new Date()+"\nContacts: "+count+"\n\n"+body;
            status.setText("تم استيراد "+count+" جهة اتصال من "+deviceName);preview.setText(exportText.length()>1800?exportText.substring(0,1800)+"\n...":exportText);saveButton.setEnabled(true);saveButton.setAlpha(1f);
        }catch(Exception e){status.setText("تعذّر قراءة الملف: "+(e.getMessage()==null?"ملف غير صالح":e.getMessage()));}
    }
    void save(){if(exportText.isEmpty())return;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");i.putExtra(Intent.EXTRA_TITLE,deviceName+".txt");startActivityForResult(i,SAVE);}
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(res!=RESULT_OK||data==null||data.getData()==null)return;if(req==PICK)importFile(data.getData(),deviceFrom(data,data.getData()));else if(req==SAVE){try(OutputStream o=getContentResolver().openOutputStream(data.getData())){o.write(exportText.getBytes(StandardCharsets.UTF_8));status.setText("تم حفظ "+deviceName+".txt — "+count+" جهة اتصال.");}catch(Exception e){status.setText("فشل حفظ الملف.");}}}

    void refresh(){BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();if(a==null){devices.setText("هذا الهاتف لا يدعم Bluetooth.");return;}if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},BT);devices.setText("مطلوب إذن Bluetooth لعرض الأجهزة المقترنة.");return;}try{Set<BluetoothDevice>s=a.getBondedDevices();if(s==null||s.isEmpty()){devices.setText("لا توجد أجهزة مقترنة حاليًا.");return;}StringBuilder b=new StringBuilder("الأجهزة المقترنة ("+s.size()+")\n\n");for(BluetoothDevice d:s)b.append("• ").append(d.getName()==null?"جهاز غير معروف":d.getName()).append('\n');devices.setText(b.toString());}catch(SecurityException e){devices.setText("لم يتم منح إذن Bluetooth.");}}
    @Override public void onRequestPermissionsResult(int q,String[] p,int[] g){super.onRequestPermissionsResult(q,p,g);if(q==BT)refresh();}
}