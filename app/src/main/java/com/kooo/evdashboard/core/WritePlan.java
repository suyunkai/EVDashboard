package com.kooo.evdashboard.core;

/** One-shot root-side writer. No model names and no arbitrary target selector. */
public final class WritePlan {
    private static String q(String value){return FilesEx.quote(value);}
    public static void checkContentMode(String candidate,String live,boolean sameContentTest)throws Exception{
        FilesEx.require(candidate.matches("[a-f0-9]{64}")&&live.matches("[a-f0-9]{64}"),"Invalid digest");
        if(sameContentTest)FilesEx.require(candidate.equals(live),"同内容重写测试只允许摘要与当前分区完全相同的镜像");
        else FilesEx.require(!candidate.equals(live),"目标分区已经与所选镜像一致，无需再次刷写");
    }
    public static String build(String slot,String block,String boot,String job,String candidateSha,String originalSha)throws Exception {
        FilesEx.require(slot.equals("cp:a")||slot.equals("cp:b"),"Unknown CP slot");
        FilesEx.require(block.matches("/dev/block/[A-Za-z0-9_./-]+")&&!block.contains(".."),"Invalid block path");
        FilesEx.require(boot.matches("[a-f0-9-]{36}")&&job.matches("/data/local/evdashboard/jobs/[a-f0-9-]{36}"),"Invalid task identity");
        FilesEx.require(candidateSha.matches("[a-f0-9]{64}")&&originalSha.matches("[a-f0-9]{64}"),"Invalid digest");
        String part=slot.equals("cp:a")?"cp_rootfs_a":"cp_rootfs_b",other=slot.equals("cp:a")?"cp_rootfs_b":"cp_rootfs_a";
        return "#!/system/bin/sh\nset -eu\numask 077\ncd "+q(job)+"\n"+
            "mkdir /data/local/evdashboard/writer.lock\necho $$ > pid\n"+
            "trap 'rc=$?; if [ $rc -ne 0 ]; then if [ -e write.started ]; then echo FAIL > result; else echo FAILED_BEFORE_WRITE > result; rmdir /data/local/evdashboard/writer.lock; fi; sync; fi' EXIT\n"+
            "echo PREFLIGHT > stage\ntest \"$(id -u)\" = 0\ntest \"$(cat /proc/boot_slot)\" = "+q(slot)+"\ntest \"$(cat /proc/sys/kernel/random/boot_id)\" = "+q(boot)+"\n"+
            "target=$(readlink -f /dev/block/by-name/"+part+")\ntest \"$target\" = "+q(block)+"\ntest -b \"$target\"\ntest \"$(blockdev --getsize64 \"$target\")\" = 1073741824\ntest \"$(blockdev --getro \"$target\")\" = 0\n"+
            "dev=$(basename \"$target\"); test -d \"/sys/class/block/$dev/holders\"; test -z \"$(ls -A \"/sys/class/block/$dev/holders\")\"\n"+
            "major_minor=$(cat \"/sys/class/block/$dev/dev\"); if awk -v dev=\"$major_minor\" '$3==dev {found=1} END {exit !found}' /proc/self/mountinfo; then echo 'Target mounted on Android' >&2; exit 42; fi\n"+
            "test \"$(df -k . | tail -n 1 | awk '{print $4}')\" -gt 1179648\n"+
            "test \"$(stat -c %s candidate.img)\" = 1073741824\ntest \"$(sha256sum candidate.img | cut -d ' ' -f1)\" = "+q(candidateSha)+"\ntest \"$(sha256sum \"$target\" | cut -d ' ' -f1)\" = "+q(originalSha)+"\n"+
            "sha256sum /dev/block/by-name/"+other+" /dev/block/by-name/cp_metadata /dev/block/by-name/cp_metadata_bak > untouched.before\n"+
            "test \"$(cat /proc/boot_slot)\" = "+q(slot)+"\ntest ! -e write.started\ndate > write.started\nsync\necho WRITING > stage\ndd if=candidate.img of=\"$target\" bs=4M count=256 conv=fsync\nsync\nblockdev --flushbufs \"$target\"\n"+
            "echo FULL_READBACK > stage\ndd if=\"$target\" of=readback.img bs=4M count=256 conv=fsync\ntest \"$(stat -c %s readback.img)\" = 1073741824\ntest \"$(sha256sum readback.img | cut -d ' ' -f1)\" = "+q(candidateSha)+"\ncmp candidate.img readback.img\n"+
            "sha256sum /dev/block/by-name/"+other+" /dev/block/by-name/cp_metadata /dev/block/by-name/cp_metadata_bak > untouched.after\ncmp untouched.before untouched.after\ntest \"$(cat /proc/boot_slot)\" = "+q(slot)+"\necho COMPLETE > stage\nsync\nrmdir /data/local/evdashboard/writer.lock\necho PASS > result\nsync\n";
    }
}
