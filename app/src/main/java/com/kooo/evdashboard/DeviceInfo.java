package com.kooo.evdashboard;

import com.kooo.evdashboard.core.*;
import java.io.IOException;
import java.util.*;

final class DeviceInfo {
    String model,device,slot,block,bootId;long bytes;
    static DeviceInfo detect(RootAccess root)throws Exception{
        String s=root.run("set -e; test \"$(id -u)\" = 0; slot=$(cat /proc/boot_slot); case \"$slot\" in cp:a) part=cp_rootfs_a;; cp:b) part=cp_rootfs_b;; *) exit 41;; esac; target=$(readlink -f /dev/block/by-name/$part); test -b \"$target\"; bytes=$(blockdev --getsize64 \"$target\"); boot=$(cat /proc/sys/kernel/random/boot_id); model=$(getprop ro.product.model); serial=$(getprop ro.serialno); printf 'model=%s\\nserial=%s\\nslot=%s\\nboot=%s\\nblock=%s\\nbytes=%s\\n' \"$model\" \"$serial\" \"$slot\" \"$boot\" \"$target\" \"$bytes\"",20);
        Map<String,String> values=new HashMap<>();for(String line:s.split("\n")){int at=line.indexOf('=');if(at>0)values.put(line.substring(0,at),line.substring(at+1).trim());}
        DeviceInfo d=new DeviceInfo();d.model=values.getOrDefault("model","未知");String serial=values.getOrDefault("serial","");FilesEx.require(!serial.isBlank(),"无法取得稳定设备标识，禁止建立备份归属");d.device=FilesEx.hashText(serial);d.slot=values.get("slot");d.block=values.get("block");d.bootId=values.get("boot");d.bytes=Long.parseLong(values.getOrDefault("bytes","0"));
        FilesEx.require("cp:a".equals(d.slot)||"cp:b".equals(d.slot),"无法确定 CP 当前槽位");FilesEx.require(d.block!=null&&d.block.matches("/dev/block/[A-Za-z0-9_./-]+")&&!d.block.contains(".."),"非法块设备路径");FilesEx.require(d.bytes==FipImage.PARTITION_BYTES,"分区容量不属于支持的结构");return d;
    }
    void same(DeviceInfo next)throws Exception{FilesEx.require(device.equals(next.device)&&slot.equals(next.slot)&&block.equals(next.block)&&bootId.equals(next.bootId)&&bytes==next.bytes,"设备、槽位、启动会话或分区发生变化，请重新识别");}
    String summary(){return model+"  ·  "+slot+"\n"+block+"  ·  1 GiB\n按 CP 槽位与分区结构识别，不使用 Android 槽位推断";}
}
