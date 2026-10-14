package com.kooo.evdashboard.core;

/** A detached, serialized resource switch. Only temporary bind mounts are changed. */
public final class AudioPlan {
    public static final String ROOT="/run/instrument-workshop";
    private static String q(String s){return FilesEx.quote(s);}
    public static String build(String target,String replacement,String service,String tag,String job)throws Exception{
        FilesEx.require(target.equals("/usr/avas/sound_type_1")||target.matches("/usr/share/chime-contents/V3_Geely_DNA_DHU/V3_[A-Za-z_]+\\.wav"),"Unsupported audio target");
        FilesEx.require(service.equals("app_1.8.service")||service.equals("cluster-cmw-audioplay.service"),"Unsupported audio service");
        FilesEx.require(tag.matches("avas|lock|cruise-[01]")&&job.matches(ROOT+"/stage-[a-f0-9-]{36}"),"Invalid audio operation");
        FilesEx.require(replacement.isEmpty()||replacement.matches(ROOT+"/stage-[a-f0-9-]{36}/[A-Za-z0-9.-]+"),"Invalid audio replacement");
        String pointer=ROOT+"/active-"+tag;
        return "#!/bin/sh\nset -eu\numask 077\ncd "+q(job)+"\n"+
            "target="+q(target)+"\npointer="+q(pointer)+"\nreplacement="+q(replacement)+"\nservice="+q(service)+"\n"+
            "mounted_path() { awk -v dst=\"$target\" '$5==dst {print $4}' /proc/self/mountinfo; }\n"+
            "owned() { case \"$1\" in ''|/instrument-workshop/*|/run/instrument-workshop/*) return 0;; *) return 1;; esac; }\n"+
            "armed=0; committed=0; old=''; was_active=0\n"+
            "mkdir "+q(ROOT+"/operation.lock")+" || { echo BUSY > result; exit 60; }\n"+
            "finish() { rc=$?; trap - EXIT HUP INT TERM; set +e; rollback=0; " +
            "if [ \"$armed\" = 1 ] && [ \"$committed\" = 0 ]; then " +
            "systemctl stop \"$service\" || rollback=1; current=$(mounted_path); " +
            "if ! owned \"$current\"; then rollback=1; " +
            "elif [ -n \"$current\" ]; then umount \"$target\" || rollback=1; fi; " +
            "if [ \"$rollback\" = 0 ] && [ -n \"$old\" ]; then mount --bind \"$old\" \"$target\" || rollback=1; fi; " +
            "if [ \"$was_active\" = 1 ]; then systemctl start \"$service\" && systemctl is-active --quiet \"$service\" || rollback=1; fi; " +
            "if [ \"$rollback\" = 0 ]; then if [ -n \"$old\" ]; then ln -sfn \"$old\" \"$pointer\" || rollback=1; else rm -f \"$pointer\" || rollback=1; fi; fi; fi; " +
            "if [ \"$committed\" = 1 ] && [ \"$rc\" = 0 ]; then echo PASS > result; " +
            "elif [ \"$rollback\" = 0 ]; then echo FAILED_RESTORED > result; else echo FAILED_RECOVERY_REQUIRED > result; fi; " +
            "rmdir "+q(ROOT+"/operation.lock")+"; exit \"$rc\"; }\n"+
            "trap finish EXIT\ntrap 'exit 129' HUP\ntrap 'exit 130' INT\ntrap 'exit 143' TERM\n"+
            "mounted=$(mounted_path); owned \"$mounted\" || { echo '其他软件的覆盖，停止操作' >&2; exit 62; }\n"+
            "if [ -n \"$mounted\" ]; then test -L \"$pointer\"; old=$(readlink \"$pointer\"); case \"$old\" in "+ROOT+"/stage-*/*) ;; *) exit 63;; esac; test -e \"$old\"; test \"$mounted\" = \"$old\" || test \"$mounted\" = \"${old#/run}\"; fi\n"+
            "if [ -n \"$replacement\" ]; then test -e \"$replacement\"; fi\n"+
            "if systemctl is-active --quiet \"$service\"; then was_active=1; fi\n"+
            "armed=1; systemctl stop \"$service\"\n"+
            "if [ -n \"$mounted\" ]; then umount \"$target\"; fi\n"+
            "if [ -n \"$replacement\" ]; then mount --bind \"$replacement\" \"$target\"; fi\n"+
            "systemctl start \"$service\"; systemctl is-active --quiet \"$service\"\n"+
            "if [ -n \"$replacement\" ]; then ln -sfn \"$replacement\" \"$pointer\"; else rm -f \"$pointer\"; fi\n"+
            "committed=1\n";
    }
}
