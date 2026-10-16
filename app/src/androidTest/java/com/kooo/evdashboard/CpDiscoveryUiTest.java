package com.kooo.evdashboard;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Local UI fixtures; real discovery is separately opted in and performs no SSH login or writes. */
@RunWith(AndroidJUnit4.class)
public class CpDiscoveryUiTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private SharedPreferences prefs;
    private Map<String, ?> saved;
    private Object app, oldLibrary;
    private String oldSelection;
    private ImageLibrary fixture;

    private Field field(Class<?> type, String name) throws Exception {Field f=type.getDeclaredField(name); f.setAccessible(true); return f;}
    private Object call(String name, Class<?>[] types, Object...args) throws Exception {Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    @Before public void setup() throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();prefs=context.getSharedPreferences("settings",0);
        saved=new HashMap<>(prefs.getAll());prefs.edit().remove("sshKnown").putBoolean("soundAutoApply",false).putString("uiLanguage","zh").commit();
        activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrumentation.waitForIdleSync();
        app=context.getApplicationContext();oldLibrary=field(app.getClass(),"library").get(app);oldSelection=(String)field(app.getClass(),"selected").get(app);
    }
    @After public void cleanup() throws Exception {
        if(activity!=null) {instrumentation.runOnMainSync(()->activity.finish());instrumentation.waitForIdleSync();}
        if(app!=null) {field(app.getClass(),"library").set(app,oldLibrary);field(app.getClass(),"selected").set(app,oldSelection);}
        if(fixture!=null)try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(fixture.root.toPath())) {paths.sorted(Comparator.reverseOrder()).forEach(p->{try{java.nio.file.Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}
        if(prefs!=null&&saved!=null) {SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){Object v=entry.getValue();if(v instanceof String s)e.putString(entry.getKey(),s);else if(v instanceof Boolean b)e.putBoolean(entry.getKey(),b);else if(v instanceof Integer n)e.putInt(entry.getKey(),n);else if(v instanceof Long n)e.putLong(entry.getKey(),n);else if(v instanceof Float n)e.putFloat(entry.getKey(),n);}e.commit();}
    }
    private View root(){return activity.getWindow().getDecorView();}
    private void navigate(int page,int section)throws Exception {instrumentation.runOnMainSync(()->{try{((Button[])field(MainActivity.class,"segButtons").get(activity))[page].performClick();((View[][])field(MainActivity.class,"navItems").get(activity))[page][section].performClick();}catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();}
    private Dialog dialog()throws Exception {Object wrapper=field(MainActivity.class,"discoveryDialog").get(activity);return wrapper==null?null:(Dialog)field(wrapper.getClass(),"dialog").get(wrapper);}
    private int y(View v){int[] p=new int[2];v.getLocationOnScreen(p);return p[1];}
    private TextView text(View v,String contains) {if(v instanceof TextView t&&t.getText().toString().contains(contains))return t;if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){TextView t=text(g.getChildAt(i),contains);if(t!=null)return t;}return null;}
    private void capture(String name,View v)throws Exception {File folder=new File(context.getFilesDir(),"cp-discovery-qa");assertTrue(folder.isDirectory()||folder.mkdirs());instrumentation.runOnMainSync(()->{android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(v.getWidth(),v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);v.draw(new android.graphics.Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new AssertionError(e);}finally{b.recycle();}});}
    private void assertButtons()throws Exception {navigate(0,0);View edit=root().findViewWithTag("cp-edit-address"),scan=root().findViewWithTag("cp-discover-address");assertTrue(edit instanceof Button);assertTrue(scan instanceof Button);assertTrue(edit.isShown());assertEquals(y(edit),y(scan));assertEquals(edit.getWidth(),scan.getWidth());assertTrue(edit.getHeight()>=48*context.getResources().getDisplayMetrics().density);}

    @Test public void buttonsShareRowAndImageReviewShowsDetectedConfigurationInBothLanguages()throws Exception {
        assertButtons();capture("connection-zh",root());
        fixture=new ImageLibrary(new File(context.getCacheDir(),"image-status-ui-"+UUID.randomUUID()));
        field(app.getClass(),"library").set(app,fixture);
        ImageRecord image=fixture.create("IMPORT","SSH status fixture");image.validated=true;image.socket="ENABLED";image.password="EMPTY";fixture.save(image);field(app.getClass(),"selected").set(app,image.id);
        for(String language:List.of("zh","en")) {
            prefs.edit().putString("uiLanguage",language).commit();
            instrumentation.runOnMainSync(()->{Configuration c=new Configuration(activity.getResources().getConfiguration());if(language.equals("en"))c.uiMode=(c.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_YES;activity.onConfigurationChanged(c);});
            assertButtons();capture("connection-"+language,root());navigate(1,2);
            TextView info=root().findViewWithTag("selected-image-write-info");String value=info.getText().toString();assertTrue(value,value.contains(language.equals("zh")?"已配置开机监听":"Enabled on boot"));assertTrue(value,value.contains(language.equals("zh")?"空密码":"Empty password"));
            capture("image-review-"+language,root());
        }
        image.validated=false;fixture.save(image);instrumentation.runOnMainSync(()->{try{call("refreshLibrary",new Class<?>[]{});}catch(Exception e){throw new AssertionError(e);}});
        String stale=((TextView)root().findViewWithTag("selected-image-write-info")).getText().toString();assertFalse(stale,stale.contains("Enabled on boot"));assertTrue(stale,stale.contains("Unknown"));
    }
    @Test public void discoveryRequiresSelectionAndChangedAddressClearsTrust()throws Exception {
        prefs.edit().putString("cpHost","198.18.44.2").putString("sshKnown","198.18.44.2 ssh-test fixture").putBoolean("sshKeyChanged",false).putBoolean("reapply",true).commit();
        CpDiscovery scan=new CpDiscovery();field(MainActivity.class,"cpDiscovery").set(activity,scan);
        CpDiscovery.Result result=new CpDiscovery.Result(List.of(new CpDiscovery.Endpoint("198.18.37.2","SSH-2.0-fixture")),256,false);
        instrumentation.runOnMainSync(()->{try{call("finishDiscovery",new Class<?>[]{CpDiscovery.class,CpDiscovery.Result.class},scan,result);}catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();
        assertEquals("198.18.44.2",prefs.getString("cpHost",""));assertTrue(prefs.contains("sshKnown"));
        Dialog d=dialog();assertNotNull(d);capture("candidate-selection",d.getWindow().getDecorView());
        instrumentation.runOnMainSync(()->{View v=text(d.getWindow().getDecorView(),"198.18.37.2");while(v!=null&&!v.isClickable())v=v.getParent() instanceof View?(View)v.getParent():null;assertNotNull(v);v.performClick();});instrumentation.waitForIdleSync();
        assertEquals("198.18.37.2",prefs.getString("cpHost",""));assertFalse(prefs.contains("sshKnown"));assertFalse(prefs.getBoolean("soundAutoApply",true));assertFalse(prefs.getBoolean("reapply",true));
    }
    @Test public void readOnlyRealDiscoveryLeavesAddressAndTrustUntouched()throws Exception {
        Assume.assumeTrue("Explicit read-only vehicle discovery opt-in",InstrumentationRegistry.getArguments().getString("allowCpDiscovery","false").equals("true"));
        Map<String,?> before=new HashMap<>(prefs.getAll());navigate(0,0);
        long start=System.nanoTime();instrumentation.runOnMainSync(()->root().findViewWithTag("cp-discover-address").performClick());
        AtomicBoolean busy=(AtomicBoolean)field(app.getClass(),"busy").get(app);
        while(busy.get()&&System.nanoTime()-start<25_000_000_000L)Thread.sleep(100);
        assertFalse("Discovery exceeded its time budget",busy.get());instrumentation.waitForIdleSync();Thread.sleep(250);instrumentation.waitForIdleSync();
        Dialog d=dialog();assertNotNull(d);assertTrue(d.isShowing());
        assertNotNull("Expected real CP SSH endpoint",text(d.getWindow().getDecorView(),"198.18.44.2"));
        assertEquals(before,prefs.getAll());capture("real-discovery",d.getWindow().getDecorView());
        System.out.println("READ_ONLY_DISCOVERY_PASS durationMs="+(System.nanoTime()-start)/1_000_000);
    }
}
