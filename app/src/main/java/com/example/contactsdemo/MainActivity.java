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
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    static final int BT_PERMISSION=10, PICK=11, SAVE=12, ENABLE_BT=13;
    BluetoothAdapter adapter;
    LinearLayout deviceList;
    TextView status, btState, preview, countView;
    Button powerButton, saveButton;
    String exportText="", deviceName="Bluetooth_Device";
    int count=0, blue=Color.rgb(42,94,207), ink=Color.rgb(25,32,42), muted=Color.rgb(102,113,128);
    boolean receiverRegistered=false, scanning=false;

    final BroadcastReceiver btReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c, Intent i){
            String action=i.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)){
                BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if(d!=null) addDevice(d,"قريب منك");
            } else if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)){
                scanning=false; status.setText("انتهى البحث. اضغط على جهاز للاقتران.");
            } else if(BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)){
                BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state=i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.ERROR);
                if(state==BluetoothDevice.BOND_BONDED && d!=null){
                    String n=safeName(d); deviceName=safeFileName(n);
                    status.setText("تم الاقتران مع "+n+". مشاركة جهات الاتصال تحتاج موافقة صاحب الجهاز.");
                } else if(state==BluetoothDevice.BOND_NONE && d!=null){
                    status.setText("لم يكتمل الاقتران مع "+safeName(d)+".");
                }
            } else if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)){
                updatePowerUi();
                if(adapter!=null && adapter.isEnabled()) startScan();
            }
        }
    };

    @Override public void onCreate(Bundle b){super.onCreate(b);adapter=BluetoothAdapter.getDefaultAdapter();buildUi();registerBtReceiver();updatePowerUi();receive(getIntent());}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);receive(i);}
    @Override protected void onDestroy(){stopScan();if(receiverRegistered){try{unregisterReceiver(btReceiver);}catch(Exception ignored){}receiverRegistered=false;}super.onDestroy();}

    void buildUi(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(26),dp(20),dp(26));root.setBackgroundColor(Color.rgb(246,248,251));scroll.addView(root);
        TextView brand=text("BT / CONTACTS",12,blue,true);brand.setLetterSpacing(.14f);root.addView(brand);
        add(root,text("Bluetooth Collector",27,ink,true),8);
        add(root,text("اقترن بالأجهزة القريبة، واستقبل جهات الاتصال التي يشاركها صاحب الجهاز بموافقته.",14,muted,false),8);
        LinearLayout powerCard=card();add(root,powerCard,20);
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setOrientation(0);powerCard.addView(row);
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(1);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        labels.addView(text("Bluetooth",17,ink,true));
        btState=text("جارٍ التحقق من الحالة",12,muted,false);labels.addView(btState);
        powerButton=button("تشغيل البلوتوث");row.addView(powerButton);
        powerButton.setOnClickListener(v->toggleBluetooth());
        add(root,text("الأجهزة القريبة",17,ink,true),22);
        add(root,text("اضغط على اسم الجهاز لبدء الاقتران. سيظهر طلب الاقتران على الجهاز الآخر.",12,muted,false),5);
        deviceList=new LinearLayout(this);deviceList.setOrientation(1);add(root,deviceList,10);
        Button scan=button("إعادة البحث عن الأجهزة");add(root,scan,12);scan.setOnClickListener(v->startScan());
        status=text("فعّل Bluetooth لبدء البحث.",13,blue,true);add(root,status,16);
        LinearLayout received=card();add(root,received,16);
        TextView heading=text("جهات الاتصال المستلمة",16,ink,true);received.addView(heading);
        countView=text("0 جهة اتصال",12,muted,false);add(received,countView,5);
        preview=text("بعد مشاركة ملف VCF من الجهاز المصدر، ستظهر المعاينة هنا.",12,muted,false);preview.setPadding(dp(10),dp(10),dp(10),dp(10));preview.setBackground(round(Color.rgb(246,248,251),12));add(received,preview,10);
        Button import=button("استيراد ملف VCF");add(received,import,12);import.setOnClickListener(v->picker());
        saveButton=button("حفظ كـ TXT");add(root,saveButton,14);saveButton.setEnabled(false);saveButton.setAlpha(.45f);saveButton.setOnClickListener(v->save());
        add(root,text("ملاحظة: الاقتران لا يمنح التطبيق تلقائيًا حق قراءة دفتر الهاتف. على صاحب الجهاز مشاركة جهات الاتصال صراحةً؛ بعدها يمكن استيراد VCF وحفظه كـ TXT.",11,muted,false),12);
        setContentView(scroll);
    }

    void registerBtReceiver(){
        IntentFilter f=new IntentFilter();f.addAction(BluetoothDevice.ACTION_FOUND);f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(btReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(btReceiver,f);
        receiverRegistered=true;
    }
    void toggleBluetooth(){
        if(adapter==null){status.setText("هذا الهاتف لا يدعم Bluetooth.");return;}
        if(!hasPermissions())return;
        try{if(adapter.isEnabled()){stopScan();if(Build.VERSION.SDK_INT<33){adapter.disable();}else{startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));status.setText("أوقف Bluetooth من إعدادات النظام إذا أردت.");}}
            else startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BT);
        }catch(SecurityException e){status.setText("إذن Bluetooth غير متاح.");}
    }
    boolean hasPermissions(){
        if(Build.VERSION.SDK_INT>=31){
            ArrayList<String> p=new ArrayList<>();
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_SCAN);
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);
            if(!p.isEmpty()){requestPermissions(p.toArray(new String[0]),BT_PERMISSION);return false;}
        }else if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},BT_PERMISSION);return false;
        }
        return true;
    }
    void updatePowerUi(){
        if(adapter==null){btState.setText("غير مدعوم");powerButton.setText("غير متاح");powerButton.setEnabled(false);return;}
        try{boolean on=adapter.isEnabled();btState.setText(on?"مفعّل":"متوقف");powerButton.setText(on?"Bluetooth مفعّل":"تشغيل Bluetooth");if(on)showPaired();}catch(SecurityException e){btState.setText("بانتظار الإذن");}
    }
    void startScan(){
        if(adapter==null){status.setText("هذا الهاتف لا يدعم Bluetooth.");return;}
        if(!hasPermissions())return;
        try{
            if(!adapter.isEnabled()){status.setText("شغّل Bluetooth أولًا.");return;}
            stopScan();deviceList.removeAllViews();showPaired();
            boolean started=adapter.startDiscovery();scanning=started;
            status.setText(started?"جاري البحث عن الأجهزة القريبة…":"تعذّر بدء البحث. جرّب مرة أخرى.");
        }catch(SecurityException e){status.setText("تأكد من منح أذونات Bluetooth.");}
    }
    void stopScan(){if(adapter!=null&&scanning){try{adapter.cancelDiscovery();}catch(Exception ignored){}scanning=false;}}
    void showPaired(){
        if(adapter==null||!hasPermissions())return;
        try{Set<BluetoothDevice> bonded=adapter.getBondedDevices();for(BluetoothDevice d:bonded)addDevice(d,"مقترن بالفعل");}catch(SecurityException ignored){}
    }
    void addDevice(BluetoothDevice d,String subtitle){
        try{
            String addr=d.getAddress();for(int i=0;i<deviceList.getChildCount();i++){View v=deviceList.getChildAt(i);if(addr.equals(v.getTag()))return;}
            LinearLayout item=new LinearLayout(this);item.setOrientation(0);item.setGravity(android.view.Gravity.CENTER_VERTICAL);item.setPadding(dp(13),dp(13),dp(13),dp(13));item.setBackground(round(Color.WHITE,14));item.setTag(addr);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);deviceList.addView(item,lp);
            TextView icon=text("ᛒ",22,blue,true);item.addView(icon);
            LinearLayout info=new LinearLayout(this);info.setOrientation(1);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1);ip.leftMargin=dp(12);item.addView(info,ip);
            info.addView(text(safeName(d),14,ink,true));info.addView(text(subtitle,11,muted,false));
            TextView action=text(d.getBondState()==BluetoothDevice.BOND_BONDED?"متصل":"اقتران  ›",12,blue,true);item.addView(action);
            item.setOnClickListener(v->pairDevice(d));
        }catch(SecurityException ignored){}
    }
    void pairDevice(BluetoothDevice d){
        if(!hasPermissions())return;stopScan();
        try{
            if(d.getBondState()==BluetoothDevice.BOND_BONDED){deviceName=safeFileName(safeName(d));status.setText("الجهاز مقترن. اطلب من صاحبه مشاركة جهات الاتصال كملف VCF.");return;}
            status.setText("جارٍ طلب الاقتران مع "+safeName(d)+"…");boolean ok=d.createBond();if(!ok)status.setText("لم يبدأ طلب الاقتران. تأكد أن الجهاز قابل للاكتشاف.");
        }catch(SecurityException e){status.setText("تعذّر الاقتران بسبب أذونات Bluetooth.");}
    }
    String safeName(BluetoothDevice d){try{String n=d.getName();return n==null||n.trim().isEmpty()?"جهاز Bluetooth":n;}catch(Exception e){return "جهاز Bluetooth";}}
    String safeFileName(String s){return s.replaceAll("[^A-Za-z0-9_\\-\\u0600-\\u06FF ]","_").trim().replace(' ','_');}

    TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setAllCaps(false);b.setBackground(round(blue,13));return b;}
    LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(14),dp(14),dp(14),dp(14));l.setBackground(round(Color.WHITE,17));return l;}
    GradientDrawable round(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));d.setStroke(dp(1),Color.rgb(228,233,240));return d;}
    void add(LinearLayout p,View v,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(top);p.addView(v,lp);}
    int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}

    void picker(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/x-vcard","text/vcard","text/directory","application/octet-stream"});startActivityForResult(i,PICK);}
    void receive(Intent i){if(i==null)return;Uri u=i.getData();if(u==null&&Intent.ACTION_SEND.equals(i.getAction())){if(i.getClipData()!=null&&i.getClipData().getItemCount()>0)u=i.getClipData().getItemAt(0).getUri();else{Object x=i.getParcelableExtra(Intent.EXTRA_STREAM);if(x instanceof Uri)u=(Uri)x;}}if(u!=null)importFile(u,deviceName.equals("Bluetooth_Device")?deviceFrom(i,u):deviceName);}
    String deviceFrom(Intent i,Uri u){String n=i.getStringExtra(Intent.EXTRA_TITLE);if(n==null)n=u.getLastPathSegment();if(n==null)n=deviceName;return safeFileName(n.replaceAll("(?i)\\.vcf$",""));}
    void importFile(Uri uri,String source){
        try(InputStream in=getContentResolver().openInputStream(uri);BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            StringBuilder raw=new StringBuilder();String line;while((line=r.readLine())!=null)raw.append(line).append('\n');
            ArrayList<String> lines=new ArrayList<>();for(String x:raw.toString().split("\\r?\\n")){if((x.startsWith(" ")||x.startsWith("\t"))&&!lines.isEmpty())lines.set(lines.size()-1,lines.get(lines.size()-1)+x.substring(1));else lines.add(x);}
            StringBuilder body=new StringBuilder();String name="",phone="";int total=0;
            for(String x:lines){String up=x.toUpperCase(Locale.ROOT);if(up.startsWith("BEGIN:VCARD")){name="";phone="";}else if(up.startsWith("FN:"))name=x.substring(x.indexOf(':')+1).trim();else if(up.startsWith("N:")&&name.isEmpty())name=x.substring(x.indexOf(':')+1).replace(';',' ').trim();else if(up.startsWith("TEL")){String v=x.substring(x.indexOf(':')+1).trim();if(!v.isEmpty())phone+=(phone.isEmpty()?"":" | ")+v;}else if(up.startsWith("END:VCARD")){if(!name.isEmpty()||!phone.isEmpty()){total++;body.append("Contact ").append(total).append('\n').append("Name: ").append(name.isEmpty()?"(No name)":name).append('\n').append("Phone: ").append(phone.isEmpty()?"(No phone)":phone).append("\n\n");}}}
            if(total==0)throw new Exception("الملف لا يحتوي على جهات اتصال VCF قابلة للقراءة.");
            count=total;deviceName=source.isEmpty()?"Bluetooth_Device":source;exportText="Device: "+deviceName+"\nImported: "+new Date()+"\nContacts: "+count+"\n\n"+body;
            status.setText("تم استيراد "+count+" جهة اتصال من "+deviceName);countView.setText(count+" جهة اتصال");preview.setText(exportText.length()>1800?exportText.substring(0,1800)+"\n...":exportText);saveButton.setEnabled(true);saveButton.setAlpha(1f);
        }catch(Exception e){status.setText("تعذّر قراءة الملف: "+(e.getMessage()==null?"ملف غير صالح":e.getMessage()));}
    }
    void save(){if(exportText.isEmpty())return;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");i.putExtra(Intent.EXTRA_TITLE,deviceName+".txt");startActivityForResult(i,SAVE);}
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==ENABLE_BT){updatePowerUi();if(res==RESULT_OK)startScan();return;}if(res!=RESULT_OK||data==null||data.getData()==null)return;if(req==PICK)importFile(data.getData(),deviceName.equals("Bluetooth_Device")?deviceFrom(data,data.getData()):deviceName);else if(req==SAVE){try(OutputStream o=getContentResolver().openOutputStream(data.getData())){o.write(exportText.getBytes(StandardCharsets.UTF_8));status.setText("تم حفظ "+deviceName+".txt — "+count+" جهة اتصال.");}catch(Exception e){status.setText("فشل حفظ الملف.");}}}
    @Override public void onRequestPermissionsResult(int q,String[] p,int[] g){super.onRequestPermissionsResult(q,p,g);if(q==BT_PERMISSION){boolean ok=true;for(int x:g)if(x!=PackageManager.PERMISSION_GRANTED)ok=false;if(ok){updatePowerUi();startScan();}else status.setText("لازم تسمح بأذونات Bluetooth للبحث والاقتران.");}}
}