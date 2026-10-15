package com.kooo.evdashboard;

import android.app.Activity;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.InputType;
import android.widget.*;
import com.kooo.evdashboard.core.LocalFiles;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** In-app browser for head units without DocumentsUI. Never runs root commands. */
final class LocalFilePicker implements AutoCloseable {
    private final Activity activity;
    private final Ui ui;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private boolean saving, audio;
    private String suggested;
    private File protectedLibrary;
    private Consumer<File> selected;
    private UiDialog active;

    LocalFilePicker(Activity activity,Ui ui){this.activity=activity;this.ui=ui;}
    void open(boolean audio,Consumer<File> selected){this.audio=audio;this.selected=selected;volumes();}
    void save(String name,File protectedLibrary,Consumer<File> selected){saving=true;suggested=name;this.protectedLibrary=protectedLibrary;this.selected=selected;volumes();}
    private void volumes(){
        LinkedHashMap<String,File> volumes=new LinkedHashMap<>();
        File internal=Environment.getExternalStorageDirectory();volumes.put("本机存储（当前 Android 用户）",internal);
        StorageManager manager=activity.getSystemService(StorageManager.class);
        for(StorageVolume volume:manager.getStorageVolumes()){
            File dir=volume.getDirectory();
            if(dir!=null&&!dir.equals(internal)&&Environment.MEDIA_MOUNTED.equals(volume.getState()))
                volumes.put(volume.getDescription(activity)+" · "+dir.getName(),dir);
            else if(!saving&&dir!=null&&!dir.equals(internal)&&Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState()))
                volumes.put(volume.getDescription(activity)+"（只读）",dir);
        }
        List<File> roots=new ArrayList<>(volumes.values());
        active=new UiDialog(activity,ui,saving?"选择另存位置":"选择文件位置")
            .message("内置文件选择器，无需安装文件浏览工具。U 盘须先被车机识别并挂载。")
            .items(volumes.keySet().toArray(new String[0]),i->browse(roots.get(i),roots.get(i)))
            .negative("取消",null).show();
    }
    private void browse(File root,File folder){browse(root,folder,0);}
    private void browse(File root,File folder,int offset){
        LinearLayout body=ui.vertical();TextView path=ui.text(folder.getAbsolutePath(),24,ui.muted,false);body.addView(path);
        LinearLayout list=ui.group();body.addView(list,ui.lp(Ui.MATCH,Ui.WRAP));
        TextView loading=ui.text("正在读取文件列表…",28,ui.muted,false);ui.addRow(list,ui.infoRow(loading));
        UiDialog dialog=new UiDialog(activity,ui,saving?"选择文件夹":"选择"+(audio?"音频":"镜像"))
            .view(body).negative("取消",null).neutral("存储设备",this::volumes);
        EditText filename;
        if(saving){
            filename=ui.field(body,"文件名（不覆盖同名文件）",suggested,InputType.TYPE_CLASS_TEXT,"文件名","",0);
            dialog.positive("保存到此文件夹",Ui.Look.PRIMARY,()->{
                try{File file=LocalFiles.destination(root,folder,filename.getText().toString().trim(),protectedLibrary);selected.accept(file);}
                catch(Exception e){new UiDialog(activity,ui,"不能保存到此位置").message(e.getMessage()).positive("返回选择",Ui.Look.PRIMARY,()->browse(root,folder)).show();}
            });
        }
        active=dialog;dialog.show();
        io.execute(()->{
            try{
                List<File> entries=LocalFiles.list(root,folder,audio,saving);
                activity.runOnUiThread(()->{
                    if(closed||activity.isFinishing()||!dialog.isShowing())return;
                    list.removeAllViews();
                    if(!folder.equals(root))ui.addRow(list,ui.actionRow("↑ 上一级",Ui.Tone.ACCENT,v->{dialog.dismiss();browse(root,folder.getParentFile());}));
                    if(entries.isEmpty())ui.addRow(list,ui.infoRow(ui.text(saving?"可以保存到此文件夹":audio?"此目录没有支持的音频文件":"此目录没有可选镜像（.img / .bin）",26,ui.muted,false)));
                    if(offset>0)ui.addRow(list,ui.actionRow("↑ 上一页文件",Ui.Tone.ACCENT,v->{dialog.dismiss();browse(root,folder,Math.max(0,offset-60));}));
                    for(File file:entries.subList(Math.min(offset,entries.size()),Math.min(offset+60,entries.size()))){
                        String label=(file.isDirectory()?"文件夹 · ":"文件 · ")+file.getName();
                        ui.addRow(list,ui.actionRow(label,Ui.Tone.NEUTRAL,v->{
                            dialog.dismiss();
                            if(file.isDirectory())browse(root,file);
                            else try{selected.accept(LocalFiles.within(root,file));}catch(Exception e){new UiDialog(activity,ui,"无法选择文件").message(e.getMessage()).positive("返回",Ui.Look.TINT,()->browse(root,folder)).show();}
                        }));
                    }
                    if(offset+60<entries.size())ui.addRow(list,ui.actionRow("下一页文件 →（共 "+entries.size()+" 项）",Ui.Tone.ACCENT,v->{dialog.dismiss();browse(root,folder,offset+60);}));
                });
            }catch(Exception e){activity.runOnUiThread(()->{if(!closed&&dialog.isShowing()){list.removeAllViews();ui.addRow(list,ui.infoRow(ui.text(e.getMessage(),28,ui.danger,false)));}});}
        });
    }
    @Override public void close(){closed=true;io.shutdownNow();if(active!=null)active.dismiss();}
}
