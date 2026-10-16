package com.kooo.evdashboard;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** UI fixtures only: no SSH, CP access, audio playback or vehicle API changes. */
@RunWith(AndroidJUnit4.class)
public class SoundUiTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private File root;
    private byte[] oldSnapshot, oldRules;
    private String oldKnown, oldLanguage;
    private boolean oldAuto;
    private SoundStore store;
    private String lock;
    private final Set<String> addedPresets = new HashSet<>();
    private final Set<String> addedAssets = new HashSet<>();
    private final Set<String> userNames = new HashSet<>();
    @Before public void setup() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
        SharedPreferences p = context.getSharedPreferences("settings",0); oldKnown = p.getString("sshKnown",null); oldAuto = p.getBoolean("soundAutoApply",false); oldLanguage = p.getString("uiLanguage",null);
        p.edit().remove("sshKnown").putBoolean("soundAutoApply",false).putString("uiLanguage","zh").commit();
        root = new File(context.getFilesDir(),"instrument-sounds"); root.mkdirs();
        oldSnapshot = backup("snapshot.json"); oldRules = backup("rules.json");
        SoundCatalog catalog;
        try(InputStream in=context.getAssets().open("sounds/catalog.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();FilesEx.transfer(in,out);catalog=new SoundCatalog(out.toString("UTF-8"));}
        store = new SoundStore(root,catalog);
        File[] before = new File(root,"assets").listFiles(); Set<String> existing = new HashSet<>(); if (before != null) for (File f : before) existing.add(f.getName());
        short[] pcm = new short[48000]; Arrays.fill(pcm,(short)1000);
        String mono = store.put(SoundWave.encode(pcm,48000,1)), stereo = store.put(SoundWave.encode(pcm,48000,2));
        String tick = store.put(SoundWave.encode(new short[4800*2],48000,2));
        for (String sha : new String[]{mono,stereo,tick}) if (!existing.contains(sha+".wav")) addedAssets.add(sha+".wav");
        JSONArray all; try (InputStream in = context.getAssets().open("sounds/catalog.json")) {ByteArrayOutputStream out = new ByteArrayOutputStream(); FilesEx.transfer(in,out); all = new JSONArray(out.toString("UTF-8"));}
        List<SoundStore.Entry> entries = new ArrayList<>();
        for (int i=0;i<all.length();i++) {String path=all.getJSONObject(i).getString("path"); String sha=path.startsWith("/usr/avas/")?mono:path.contains("Turn_light")&&path.contains("Single")?tick:stereo; entries.add(new SoundStore.Entry(path,sha,sha,"NONE"));}
        store.snapshot("synthetic-ui-fixture",entries); lock=SoundCatalog.CHIMES+"V3_Vehicle_Locked_Single.wav";
        store.rules(Collections.singletonMap(lock,new SoundStore.Rule(stereo,"",70)));
        activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)); instrumentation.waitForIdleSync();
        // Existing image/preset names are user data and intentionally keep their original language.
        java.lang.reflect.Field stateField=MainActivity.class.getDeclaredField("state");stateField.setAccessible(true);Object app=stateField.get(activity);
        java.lang.reflect.Field libraryField=app.getClass().getDeclaredField("library");libraryField.setAccessible(true);Object library=libraryField.get(app);
        if(library!=null)for(Object record:(List<?>)library.getClass().getMethod("list").invoke(library))userNames.add((String)record.getClass().getField("name").get(record));
        for(SoundStore.Preset preset:store.presets())userNames.add(preset.name);
        instrumentation.runOnMainSync(()->{visible("仪表提示音").performClick();clickRow(visible("声音目录"));}); instrumentation.waitForIdleSync();
    }
    private byte[] backup(String name) throws Exception {File f=new File(root,name);return f.isFile()?Files.readAllBytes(f.toPath()):null;}
    @After public void cleanup() throws Exception {
        if(activity!=null) {instrumentation.runOnMainSync(()->activity.finish());instrumentation.waitForIdleSync();}
        if(root!=null) {restore("snapshot.json",oldSnapshot);restore("rules.json",oldRules);for(String name:addedAssets)Files.deleteIfExists(new File(root,"assets/"+name).toPath());for(String id:addedPresets)store.deletePreset(id);}
        if(context!=null) {SharedPreferences.Editor e=context.getSharedPreferences("settings",0).edit().putBoolean("soundAutoApply",oldAuto);if(oldKnown==null)e.remove("sshKnown");else e.putString("sshKnown",oldKnown);if(oldLanguage==null)e.remove("uiLanguage");else e.putString("uiLanguage",oldLanguage);e.commit();}
    }
    private void restore(String name,byte[] data)throws Exception{File f=new File(root,name);if(data==null)Files.deleteIfExists(f.toPath());else Files.write(f.toPath(),data);}
    private TextView find(View view,String label) {if(view instanceof TextView t&&t.isShown()&&t.getText().toString().equals(label))return t;if(view instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){TextView found=find(g.getChildAt(i),label);if(found!=null)return found;}return null;}
    private TextView visible(String label){TextView v=find(activity.getWindow().getDecorView(),label);assertNotNull(label,v);return v;}
    private void clickRow(View view){View v=view;while(v!=null&&!v.isClickable())v=v.getParent() instanceof View?(View)v.getParent():null;assertNotNull(v);v.performClick();}
    private Dialog detail() throws Exception {java.lang.reflect.Field panel=MainActivity.class.getDeclaredField("soundPanel");panel.setAccessible(true);java.lang.reflect.Field detail=Class.forName("com.kooo.evdashboard.SoundPanel",false,activity.getClassLoader()).getDeclaredField("detail");detail.setAccessible(true);java.lang.reflect.Field d=Class.forName("com.kooo.evdashboard.UiDialog",false,activity.getClassLoader()).getDeclaredField("dialog");d.setAccessible(true);return (Dialog)d.get(detail.get(panel.get(activity)));}
    private void capture(String name,View view) throws Exception {
        File folder=new File(context.getFilesDir(),"sound-ui-qa");folder.mkdirs();
        instrumentation.runOnMainSync(()->{float scale=Math.min(1f,1280f/view.getWidth());android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(Math.round(view.getWidth()*scale),Math.round(view.getHeight()*scale),android.graphics.Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(image);canvas.scale(scale,scale);view.draw(canvas);try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new AssertionError(e);}finally{image.recycle();}});
    }
    @Test public void categoriesScrollThroughAllCardsWithoutPagingAndKeepActionsInDialog() throws Exception {
        for(String category:new String[]{"常用","警报","雷达","低速","参考","诊断"}) {
            Set<String> groups=new HashSet<>();SoundCatalog catalog;
            try(InputStream in=context.getAssets().open("sounds/catalog.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();FilesEx.transfer(in,out);catalog=new SoundCatalog(out.toString("UTF-8"));}
            for(SoundStore.Entry e:store.entries())if(catalog.spec(e.path).category.equals(category))groups.add(catalog.spec(e.path).group);
            final int expected=groups.size();
            instrumentation.runOnMainSync(()->{visible(category).performClick();View decor=activity.getWindow().getDecorView();
                assertNull(find(decor,"← 上一屏"));assertNull(find(decor,"下一屏 →"));
                ScrollView scroll=(ScrollView)tag(decor,"sound-scroll");assertNotNull(scroll);assertEquals(expected,countTag(scroll,"sound-card"));
                assertEquals(0,scroll.getScrollY());
            });instrumentation.waitForIdleSync();
            ScrollView scroll=(ScrollView)tag(activity.getWindow().getDecorView(),"sound-scroll");
            instrumentation.runOnMainSync(()->scroll.scrollTo(0,scroll.getChildAt(0).getHeight()));instrumentation.waitForIdleSync();
            if(scroll.getChildAt(0).getHeight()>scroll.getHeight())assertTrue(scroll.getScrollY()>0);
            capture("category-"+category,activity.getWindow().getDecorView());
        }
        instrumentation.runOnMainSync(()->{visible("常用").performClick();clickRow(visible("转向提示"));}); instrumentation.waitForIdleSync();
        Dialog d=detail();assertNotNull(find(d.getWindow().getDecorView(),"试听原声"));assertNotNull(find(d.getWindow().getDecorView(),"独立音量"));
        assertNotNull(find(d.getWindow().getDecorView(),"导出声音"));assertNotNull(find(d.getWindow().getDecorView(),"Tick"));assertNotNull(find(d.getWindow().getDecorView(),"Tock"));
        capture("turn-dialog",d.getWindow().getDecorView());instrumentation.runOnMainSync(d::dismiss);
    }
    @Test public void renderNightCardsAndVolumeDialog() throws Exception {
        instrumentation.runOnMainSync(()->{Configuration c=new Configuration(activity.getResources().getConfiguration());c.uiMode=(c.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_YES;activity.onConfigurationChanged(c);visible("仪表提示音").performClick();clickRow(visible("声音目录"));});
        capture("night-cards",activity.getWindow().getDecorView());
        instrumentation.runOnMainSync(()->{ScrollView scroll=(ScrollView)tag(activity.getWindow().getDecorView(),"sound-scroll");scroll.scrollTo(0,scroll.getChildAt(0).getHeight());});instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()->clickRow(visible("锁车提示"))); instrumentation.waitForIdleSync();
        Dialog d=detail();capture("night-volume-dialog",d.getWindow().getDecorView());instrumentation.runOnMainSync(d::dismiss);
    }
    private View tag(View root,String name) {return root.findViewWithTag(name);}
    private int countTag(View root,String name) {int count=name.equals(root.getTag())?1:0;if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)count+=countTag(g.getChildAt(i),name);return count;}
    private int y(View view) {int[] point=new int[2];view.getLocationOnScreen(point);return point[1];}
    private void englishOnly(View root) {
        if(root instanceof TextView text&&text.isShown()&&!text.getText().toString().equals("中文")) {
            String value=text.getText().toString();List<String> names=new ArrayList<>(userNames);names.sort((a,b)->Integer.compare(b.length(),a.length()));
            for(String name:names)if(!name.isEmpty())value=value.replace(name,"");
            assertFalse(value,value.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN));
        }
        if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)englishOnly(g.getChildAt(i));
    }
    private void switchLanguage() throws Exception {
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(MainActivity.class.getName(),null,false);
        try {
            instrumentation.runOnMainSync(()->tag(activity.getWindow().getDecorView(),"language-switch").performClick());
            Activity next=monitor.waitForActivityWithTimeout(10000); assertNotNull("Language recreation",next);activity=(MainActivity)next;instrumentation.waitForIdleSync();
        }finally{instrumentation.removeMonitor(monitor);}
    }
    @Test public void taskPanelShowsTimingMeasuredProgressAndClearResultStates() throws Exception {
        verifyTaskPanel("zh"); switchLanguage(); verifyTaskPanel("en");
    }
    private void verifyTaskPanel(String language) throws Exception {
        java.lang.reflect.Field stateField=MainActivity.class.getDeclaredField("state");stateField.setAccessible(true);Object state=stateField.get(activity);
        java.lang.reflect.Field title=state.getClass().getDeclaredField("title"),status=state.getClass().getDeclaredField("status"),failed=state.getClass().getDeclaredField("failed"),busyField=state.getClass().getDeclaredField("busy"),progressField=state.getClass().getDeclaredField("progress");
        for(java.lang.reflect.Field f:new java.lang.reflect.Field[]{title,status,failed,busyField,progressField})f.setAccessible(true);
        Object oldTitle=title.get(state),oldStatus=status.get(state);boolean oldFailed=failed.getBoolean(state);
        java.util.concurrent.atomic.AtomicBoolean busy=(java.util.concurrent.atomic.AtomicBoolean)busyField.get(state);Object p=progressField.get(state);
        java.lang.reflect.Method start=p.getClass().getMethod("start",String.class),stage=p.getClass().getMethod("stage",String.class,long.class),advance=p.getClass().getMethod("advance",long.class),finish=p.getClass().getMethod("finish",boolean.class),update=MainActivity.class.getDeclaredMethod("updateStatus");update.setAccessible(true);
        View root=activity.getWindow().getDecorView();boolean en=language.equals("en");
        try {
            instrumentation.runOnMainSync(()->{try {
                title.set(state,"读取 CP 实际提示音");status.set(state,"连接 CP 并检查声音目录");failed.setBoolean(state,false);busy.set(true);start.invoke(p,"连接 CP 并检查声音目录");update.invoke(activity);
                TextView timing=(TextView)tag(root,"task-timing");assertTrue(timing.isShown());assertTrue(timing.getText().toString().matches(".*[0-9]{2}:[0-9]{2}:[0-9]{2}.*"));assertFalse(timing.getText().toString().contains("%"));
                assertFalse(tag(root,"task-percentage").isShown());assertTrue(tag(root,"task-progress").isShown());
            }catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();capture("task-connecting-"+language,tag(root,"task-panel"));
            instrumentation.runOnMainSync(()->{try {
                stage.invoke(p,"读取并校验声音文件",100L);advance.invoke(p,48L);status.set(state,"读取实际提示音 25/51 · 转向提示");update.invoke(activity);
                assertEquals("48%",((TextView)tag(root,"task-percentage")).getText().toString());assertTrue(tag(root,"task-percentage").isShown());
                assertTrue(tag(root,"task-progress").getWidth()>tag(root,"task-panel").getWidth()*.9);
                assertEquals(en?"In progress":"进行中",((TextView)tag(root,"task-state")).getText().toString());
            }catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();capture("task-reading-"+language,root);
            instrumentation.runOnMainSync(()->{try {
                finish.invoke(p,true);busy.set(false);update.invoke(activity);
                assertEquals(en?"Completed":"已完成",((TextView)tag(root,"task-state")).getText().toString());assertFalse(tag(root,"task-progress").isShown());
                start.invoke(p,"读取");finish.invoke(p,false);failed.setBoolean(state,true);status.set(state,"操作未完成：SSH 操作超时");update.invoke(activity);
                assertEquals(en?"Incomplete":"未完成",((TextView)tag(root,"task-state")).getText().toString());
                assertFalse(((TextView)tag(root,"task-message")).getText().toString().contains("写入"));
            }catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();capture("task-failed-"+language,tag(root,"task-panel"));
        }finally {
            instrumentation.runOnMainSync(()->{try{finish.invoke(p,false);busy.set(false);title.set(state,oldTitle);status.set(state,oldStatus);failed.setBoolean(state,oldFailed);update.invoke(activity);}catch(Exception e){throw new AssertionError(e);}});
        }
    }
    @Test public void editorOrdersInformationSliderAndCompactButtonsAndKeepsVariantDrafts() throws Exception {
        byte[] before=backup("rules.json");
        instrumentation.runOnMainSync(()->clickRow(visible("转向提示")));instrumentation.waitForIdleSync();
        Dialog d=detail();View root=d.getWindow().getDecorView();
        instrumentation.runOnMainSync(()->{
            SeekBar slider=(SeekBar)tag(root,"sound-volume");View info=tag(root,"sound-information"),actions=tag(root,"sound-actions");
            assertTrue(y(info)<y(slider));assertTrue(y(slider)<y(actions));
            TextView original=find(root,"试听原声"),current=find(root,"试听当前"),adjusted=find(root,"试听调整后");
            assertTrue(original instanceof Button);assertTrue(current instanceof Button);assertTrue(adjusted instanceof Button);
            assertFalse(original.isSoundEffectsEnabled());assertFalse(current.isSoundEffectsEnabled());assertFalse(adjusted.isSoundEffectsEnabled());
            assertNotNull(find(root,"短音试听含起始缓冲并重复 3 次，实际文件不变"));
            assertEquals(y(original),y(current));assertEquals(y(original),y(adjusted));
            slider.setProgress(0);find(root,"Tock").performClick();
        });instrumentation.waitForIdleSync();
        Dialog tock=detail();instrumentation.runOnMainSync(()->{((SeekBar)tag(tock.getWindow().getDecorView(),"sound-volume")).setProgress(150);find(tock.getWindow().getDecorView(),"Tick").performClick();});instrumentation.waitForIdleSync();
        Dialog tick=detail();assertEquals(0,((SeekBar)tag(tick.getWindow().getDecorView(),"sound-volume")).getProgress());
        instrumentation.runOnMainSync(tick::dismiss);assertArrayEquals(before,backup("rules.json"));
    }
    @Test public void presetsUseCardsAndImageWorkspaceSeparatesSelectionFromActions() throws Exception {
        Set<String> oldIds=new HashSet<>();for(SoundStore.Preset p:store.presets())oldIds.add(p.id);
        if(oldIds.size()<5) {
            store.savePreset("UI QA "+System.currentTimeMillis());
            for(SoundStore.Preset p:store.presets())if(!oldIds.contains(p.id)){addedPresets.add(p.id);userNames.add(p.name);}
        }
        instrumentation.runOnMainSync(()->{try{
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("soundPanel");field.setAccessible(true);Object panel=field.get(activity);
            java.lang.reflect.Method reload=panel.getClass().getDeclaredMethod("reload");reload.setAccessible(true);reload.invoke(panel);
            clickRow(visible("预设与导出"));
        }catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();
        View decor=activity.getWindow().getDecorView();assertEquals(store.presets().size(),countTag(decor,"preset-card"));
        View card=tag(decor,"preset-card");assertTrue(card.isClickable());assertTrue(card.getHeight()>card.getWidth()*.15);
        capture("preset-cards",decor);
        instrumentation.runOnMainSync(()->{try{java.lang.reflect.Field segments=MainActivity.class.getDeclaredField("segButtons");segments.setAccessible(true);((Button[])segments.get(activity))[1].performClick();clickRow(visible("2 · 选择与修改"));}catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();
        View select=tag(decor,"image-selection"),settings=tag(decor,"image-configuration");assertTrue(select.isShown());assertTrue(settings.isShown());
        assertNotNull(find(select,"从本机 / U 盘导入镜像"));assertNull(find(select,"生成新的修改镜像"));assertNotNull(find(settings,"生成新的修改镜像"));
        if(activity.getResources().getConfiguration().screenWidthDp>=1200){int[] a=new int[2],b=new int[2];select.getLocationOnScreen(a);settings.getLocationOnScreen(b);assertTrue(a[0]+select.getWidth()<=b[0]);assertEquals(a[1],b[1]);}
        capture("image-workspace",decor);
    }
    @Test public void languageSwitchPersistsNavigationAndLocalizesDialogsAndRiskNotice() throws Exception {
        switchLanguage();
        assertEquals("en",context.getSharedPreferences("settings",0).getString("uiLanguage",""));
        View root=activity.getWindow().getDecorView();assertNotNull(find(root,"Sound library"));assertNotNull(find(root,"Risk notice"));
        View warning=tag(root,"permanent-risk-warning");assertTrue(warning.isShown());englishOnly(root);
        capture("english-cards",root);
        instrumentation.runOnMainSync(()->clickRow(visible("Turn signal")));instrumentation.waitForIdleSync();
        Dialog d=detail();englishOnly(d.getWindow().getDecorView());assertTrue(find(d.getWindow().getDecorView(),"Play original") instanceof Button);
        capture("english-editor",d.getWindow().getDecorView());instrumentation.runOnMainSync(d::dismiss);
        // Traverse every page and sidebar section without invoking any actions.
        java.lang.reflect.Field segments=MainActivity.class.getDeclaredField("segButtons");segments.setAccessible(true);
        java.lang.reflect.Field items=MainActivity.class.getDeclaredField("navItems");items.setAccessible(true);
        for(int page=0;page<2;page++)for(int section=0;section<(page==0?5:4);section++) {
            final int p=page,s=section;
            instrumentation.runOnMainSync(()->{try{((Button[])segments.get(activity))[p].performClick();((View[][])items.get(activity))[p][s].performClick();}catch(Exception e){throw new AssertionError(e);}});
            instrumentation.waitForIdleSync();englishOnly(activity.getWindow().getDecorView());assertTrue(tag(activity.getWindow().getDecorView(),"permanent-risk-warning").isShown());
        }
        capture("english-workshop",activity.getWindow().getDecorView());
        switchLanguage();assertNotNull(find(activity.getWindow().getDecorView(),"任务与恢复"));assertNotNull(find(activity.getWindow().getDecorView(),"风险提醒"));
    }

}
