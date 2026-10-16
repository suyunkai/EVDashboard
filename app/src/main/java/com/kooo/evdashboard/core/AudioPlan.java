package com.kooo.evdashboard.core;

/** A detached, serialized resource switch. Only temporary bind mounts are changed. */
public final class AudioPlan {
    public static final String ROOT="/run/instrument-workshop";
    private static String q(String s){return FilesEx.quote(s);}
    public static final class Change {
        public final String target, replacement, expectedCurrent;
        public Change(String target, String replacement, String expectedCurrent) {
            this.target = target; this.replacement = replacement; this.expectedCurrent = expectedCurrent;
        }
    }

    /** One lock and one service interruption for the entire configuration, with reverse-order rollback. */
    public static String batch(java.util.List<Change> changes, java.util.Set<String> services, String job) throws Exception {
        FilesEx.require(!changes.isEmpty() && changes.size() <= 64 && !services.isEmpty()
            && job.matches(ROOT + "/stage-[a-f0-9-]{36}"), "Invalid sound transaction");
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (Change c : changes) FilesEx.require(SoundCatalog.allowedPath(c.target)
            && (c.target.startsWith(SoundCatalog.CHIMES) || c.target.startsWith("/usr/avas/"))
            && targets.add(c.target) && c.expectedCurrent.matches("[a-f0-9]{64}")
            && (c.replacement.isEmpty() || c.replacement.matches(ROOT + "/stage-[a-f0-9-]{36}/[a-f0-9]{64}\\.wav")), "Invalid sound resource");
        for (String service : services) FilesEx.require(SoundCatalog.allowedService(service), "Invalid sound service");
        StringBuilder s = new StringBuilder("#!/bin/sh\nset -eu\numask 077\ncd " + q(job) + "\n");
        s.append("mounted_path() { awk -v dst=\"$1\" '$5==dst {print $4}' /proc/self/mountinfo; }\n")
            .append("owned() { case \"$1\" in ''|/instrument-workshop/*|/run/instrument-workshop/*) return 0;; *) return 1;; esac; }\n")
            .append("armed=0; committed=0\n");
        for (int i = 0; i < changes.size(); i++) s.append("old").append(i).append("=''; touched").append(i).append("=0\n");
        int si = 0; for (String ignored : services) s.append("active").append(si++).append("=0\n");
        s.append("mkdir ").append(q(ROOT + "/operation.lock")).append(" || { echo BUSY > result; exit 60; }\n")
            .append("finish() { rc=$?; trap - EXIT HUP INT TERM; set +e; rollback=0\nif [ \"$armed\" = 1 ] && [ \"$committed\" = 0 ]; then\n");
        for (String service : services) s.append("systemctl stop ").append(q(service)).append(" || rollback=1\n");
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change c = changes.get(i);
            s.append("if [ \"$touched").append(i).append("\" = 1 ]; then\ncurrent=$(mounted_path ").append(q(c.target)).append(")\n")
                .append("if ! owned \"$current\"; then rollback=1; else\n")
                .append("if [ -n \"$current\" ]; then umount ").append(q(c.target)).append(" || rollback=1; fi\n")
                .append("if [ -n \"$old").append(i).append("\" ]; then mount --bind \"$old").append(i).append("\" ").append(q(c.target)).append(" || rollback=1; fi\nfi\nfi\n");
        }
        si = 0;
        for (String service : services) s.append("if [ \"$active").append(si++).append("\" = 1 ]; then systemctl start ").append(q(service))
            .append(" && systemctl is-active --quiet ").append(q(service)).append(" || rollback=1; fi\n");
        s.append("fi\nif [ \"$committed\" = 1 ] && [ \"$rc\" = 0 ]; then echo PASS > result; elif [ \"$rollback\" = 0 ]; then echo FAILED_RESTORED > result; else echo FAILED_RECOVERY_REQUIRED > result; fi\n")
            .append("rmdir ").append(q(ROOT + "/operation.lock")).append("\nexit \"$rc\"; }\ntrap finish EXIT\ntrap 'exit 129' HUP\ntrap 'exit 130' INT\ntrap 'exit 143' TERM\n");
        for (int i = 0; i < changes.size(); i++) {
            Change c = changes.get(i);
            s.append("test -z \"$(awk -v dst=").append(q(c.target)).append(" '$5!=\"/\" && index(dst,$5\"/\")==1 {print $5}' /proc/self/mountinfo)\" || exit 62\n");
            s.append("mounted=$(mounted_path ").append(q(c.target)).append(")\nowned \"$mounted\" || exit 62\n")
                .append("if [ -n \"$mounted\" ]; then case \"$mounted\" in /instrument-workshop/*) old").append(i).append("=/run$mounted;; *) old").append(i).append("=$mounted;; esac; test -f \"$old").append(i).append("\"; fi\n")
                .append("test \"$(sha256sum ").append(q(c.target)).append(" | cut -d ' ' -f 1)\" = ").append(q(c.expectedCurrent)).append(" || exit 64\n");
            if (!c.replacement.isEmpty()) s.append("test -f ").append(q(c.replacement)).append("\n");
        }
        si = 0; for (String service : services) s.append("if systemctl is-active --quiet ").append(q(service)).append("; then active").append(si++).append("=1; fi\n");
        s.append("armed=1\n");
        for (String service : services) s.append("systemctl stop ").append(q(service)).append("\n");
        for (int i = 0; i < changes.size(); i++) {
            Change c = changes.get(i);
            s.append("touched").append(i).append("=1\nif [ -n \"$old").append(i).append("\" ]; then umount ").append(q(c.target)).append("; fi\n");
            if (!c.replacement.isEmpty()) s.append("mount --bind ").append(q(c.replacement)).append(' ').append(q(c.target)).append("\n");
        }
        si = 0; for (String service : services) s.append("if [ \"$active").append(si++).append("\" = 1 ]; then systemctl start ").append(q(service))
            .append("; systemctl is-active --quiet ").append(q(service)).append("; fi\n");
        for (Change c : changes) if (!c.replacement.isEmpty()) s.append("test \"$(sha256sum ").append(q(c.target))
            .append(" | cut -d ' ' -f 1)\" = \"$(sha256sum ").append(q(c.replacement)).append(" | cut -d ' ' -f 1)\"\n");
        return s.append("committed=1\n").toString();
    }
    public static String build(String target,String replacement,String service,String tag,String job)throws Exception{
        FilesEx.require(target.equals("/usr/avas/sound_type_1")||target.matches("/usr/share/chime-contents/V3_Geely_DNA_DHU/V3_[A-Za-z_]+\\.wav"),"Unsupported audio target");
        FilesEx.require(SoundCatalog.allowedService(service),"Unsupported audio service");
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
