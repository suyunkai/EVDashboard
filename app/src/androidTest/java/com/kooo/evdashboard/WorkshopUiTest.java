package com.kooo.evdashboard;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** VM-only UI/navigation checks. No partition access, CP network connection or audio changes. */
@RunWith(AndroidJUnit4.class)
public class WorkshopUiTest {
    private Instrumentation instrumentation;
    private MainActivity activity;
    @Before public void launch() {
        Assume.assumeTrue(android.os.Build.HARDWARE.contains("ranchu"));
        instrumentation=InstrumentationRegistry.getInstrumentation();
        activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
    }
    @After public void finish(){if(activity!=null)instrumentation.runOnMainSync(()->activity.finish());}
    private TextView find(View root,String label){
        if(root instanceof TextView text&&text.getText().toString().equals(label)&&text.isShown())return text;
        if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){TextView found=find(group.getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private TextView visible(String label){TextView v=find(activity.getWindow().getDecorView(),label);assertNotNull(label,v);return v;}
    private Dialog pickerDialog() throws Exception {
        java.lang.reflect.Field pickerField=MainActivity.class.getDeclaredField("filePicker");pickerField.setAccessible(true);Object picker=pickerField.get(activity);
        java.lang.reflect.Field active=picker.getClass().getDeclaredField("active");active.setAccessible(true);Object uiDialog=active.get(picker);
        java.lang.reflect.Field dialog=uiDialog.getClass().getDeclaredField("dialog");dialog.setAccessible(true);return (Dialog)dialog.get(uiDialog);
    }
    private void clickRow(TextView text){View current=text;while(current!=null&&!current.isClickable())current=current.getParent() instanceof View?(View)current.getParent():null;assertNotNull(current);assertTrue(current.performClick());}
    @Test public void workshopFirstAndNavigationExplainsNextStep(){
        instrumentation.runOnMainSync(()->{
            TextView first=visible("仪表盘工坊"),second=visible("AVAS 与提示音");
            int[] a=new int[2],b=new int[2];first.getLocationOnScreen(a);second.getLocationOnScreen(b);assertTrue(a[0]<b[0]);
            visible("识别并导出完整备份");
            clickRow(visible("2 · 选择与修改"));visible("从本机 / U 盘导入镜像");visible("生成新的修改镜像");
            clickRow(visible("3 · 校验与写入"));visible("将所选镜像写入当前 CP 槽位");visible("← 返回第 2 步选镜像");
            second.performClick();visible("修改 CP IP 地址");
        });
    }
    @Test public void permissionEntryTargetsExactAppAndEvcc(){
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
                assertEquals("com.kooo.evcc",intent.getPackage());
                assertEquals("evcc://permissions/app?package=com.kooo.evdashboard",intent.getDataString());
                calls.incrementAndGet();
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);
            }
        };
        instrumentation.addMonitor(monitor);
        try{instrumentation.runOnMainSync(()->clickRow(visible("前往 EVCC 授权本应用权限")));assertEquals(1,calls.get());}
        finally{instrumentation.removeMonitor(monitor);}
    }
    @Test public void fingerprintIsBoundToSavedAddress() throws Exception {
        java.lang.reflect.Method trusted=activity.getClassLoader().loadClass("com.kooo.evdashboard.SshAccess").getDeclaredMethod("trusted",Context.class);
        trusted.setAccessible(true);
        SharedPreferences preferences=activity.getSharedPreferences("settings",0);
        String host=preferences.getString("cpHost",null),key=preferences.getString("sshKnown",null);
        try{
            preferences.edit().putString("cpHost","192.168.10.20").putString("sshKnown","198.18.44.2 ssh-rsa fixture").commit();
            assertEquals(false,trusted.invoke(null,activity));
            preferences.edit().putString("sshKnown","192.168.10.20 ssh-rsa fixture").commit();assertEquals(true,trusted.invoke(null,activity));
        }finally{
            SharedPreferences.Editor edit=preferences.edit();if(host==null)edit.remove("cpHost");else edit.putString("cpHost",host);
            if(key==null)edit.remove("sshKnown");else edit.putString("sshKnown",key);edit.commit();
        }
    }
    private void capture(String name) {
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()->{
            View root=activity.getWindow().getDecorView();
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(bitmap));
            java.io.File folder=new java.io.File(activity.getFilesDir(),"ui-qa");assertTrue(folder.isDirectory()||folder.mkdir());
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
            catch(Exception e){throw new AssertionError(e);}finally{bitmap.recycle();}
        });
    }
    @Test public void renderDayAndNightWorkflow() {
        for(int mode:new int[]{android.content.res.Configuration.UI_MODE_NIGHT_NO,android.content.res.Configuration.UI_MODE_NIGHT_YES}){
            instrumentation.runOnMainSync(()->{
                android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration());
                config.uiMode=(config.uiMode&~android.content.res.Configuration.UI_MODE_NIGHT_MASK)|mode;
                activity.onConfigurationChanged(config);visible("仪表盘工坊").performClick();clickRow(visible("1 · 识别与备份"));
            });
            String theme=mode==android.content.res.Configuration.UI_MODE_NIGHT_YES?"night":"day";
            capture(theme+"-backup");
            instrumentation.runOnMainSync(()->clickRow(visible("2 · 选择与修改")));capture(theme+"-library");
            instrumentation.runOnMainSync(()->clickRow(visible("3 · 校验与写入")));capture(theme+"-write");
            instrumentation.runOnMainSync(()->visible("AVAS 与提示音").performClick());capture(theme+"-connection");
        }
    }
    @Test public void builtInPickerDoesNotLaunchExternalFileManager() throws Exception {
        Assume.assumeTrue(android.os.Environment.isExternalStorageManager());
        java.util.concurrent.atomic.AtomicInteger external=new java.util.concurrent.atomic.AtomicInteger();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){external.incrementAndGet();return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}};
        instrumentation.addMonitor(monitor);
        try{
            instrumentation.runOnMainSync(()->{clickRow(visible("2 · 选择与修改"));visible("从本机 / U 盘导入镜像").performClick();});
            Dialog volumes=pickerDialog();assertTrue(volumes.isShowing());
            instrumentation.runOnMainSync(()->{TextView local=find(volumes.getWindow().getDecorView(),"本机存储（当前 Android 用户）");assertNotNull(local);clickRow(local);});
            Dialog folders=pickerDialog();assertTrue(folders.isShowing());
            assertNotNull(find(folders.getWindow().getDecorView(),"存储设备"));assertEquals(0,external.get());
            instrumentation.runOnMainSync(folders::dismiss);
        }finally{instrumentation.removeMonitor(monitor);}
    }
}
