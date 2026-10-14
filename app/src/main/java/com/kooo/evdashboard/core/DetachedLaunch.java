package com.kooo.evdashboard.core;

/** Keep the initiating shell alive until the detached worker acknowledges entry. */
public final class DetachedLaunch {
    public static String writer(String job)throws Exception{
        FilesEx.require(job.matches("/data/local/evdashboard/jobs/[a-f0-9-]{36}"),"Invalid writer job");
        String q=FilesEx.quote(job);
        // Legacy ADB shell is a PTY. Its exit may send SIGHUP before nohup/setsid
        // has even executed. Ignore HUP in the parent before forking and wait for
        // a worker-owned acknowledgement. No missing acknowledgement means success.
        return "trap '' HUP; setsid toybox nohup /system/bin/sh "+q+"/writer.sh >"+q+"/writer.log 2>&1 </dev/null & " +
            "launch_pid=$!; launch_wait=0; while [ ! -f "+q+"/pid ] && [ ! -f "+q+"/result ]; do " +
            "launch_wait=$((launch_wait+1)); if [ \"$launch_wait\" -ge 100 ]; then echo 'Writer did not acknowledge startup; inspect retained job, do not retry' >&2; exit 48; fi; sleep 0.1; done; " +
            "echo WRITER_ACKNOWLEDGED";
    }
}
