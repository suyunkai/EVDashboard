package com.kooo.evdashboard.core;

/** Reclaims only unmounted WAVs from finished tasks, after successful verification. */
public final class AudioCleanup {
    private AudioCleanup() {}
    public static String script() {
        String root = FilesEx.quote(AudioPlan.ROOT);
        return "set -eu\nroot=" + root + "\n"
            + "[ -d \"$root\" ] && [ ! -L \"$root\" ] || exit 0\n"
            + "mkdir \"$root/operation.lock\" 2>/dev/null || exit 0\n"
            + "trap 'rmdir \"$root/operation.lock\"' EXIT\ntrap 'exit 129' HUP\ntrap 'exit 130' INT\ntrap 'exit 143' TERM\n"
            + "{ for stage in \"$root\"/stage-*; do\n"
            + "[ -d \"$stage\" ] && [ ! -L \"$stage\" ] || continue\n"
            + "name=${stage##*/}\nprintf '%s\\n' \"$name\" | grep -Eq '^stage-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$' || continue\n"
            + "[ -f \"$stage/result\" ] && [ ! -L \"$stage/result\" ] || continue\n"
            + "case $(cat \"$stage/result\") in PASS|FAILED_RESTORED) ;; *) continue;; esac\n"
            + "for file in \"$stage\"/*.wav; do\n"
            + "[ -f \"$file\" ] && [ ! -L \"$file\" ] || continue\n"
            + "name=${file##*/}; hash=${name%.wav}\n[ ${#hash} = 64 ] || continue\ncase \"$hash\" in *[!a-f0-9]*) continue;; esac\n"
            + "printf '%s\\n' \"$file\"\ndone\ndone\n} | "
            // Scan mount roots AND mount points once, instead of a shell loop per WAV.
            // Directory bind sources protect every descendant, including alias paths.
            + "awk -v root=\"$root\" '\n"
            + "BEGIN { alias=\"/instrument-workshop\"; rows=0; bad=0; while ((rc=(getline line < \"/proc/self/mountinfo\")) > 0) { rows++; n=split(line,p,\" \"); if (n<10 || substr(p[4],1,1)!=\"/\" || substr(p[5],1,1)!=\"/\") bad=1;\n"
            + "for (i=4;i<=5;i++) { ref=p[i]; if (ref==alias || index(ref,alias\"/\")==1) ref=root substr(ref,length(alias)+1);\n"
            + "if (ref==root || index(ref,root\"/\")==1) refs[ref]=1; } } if (rc<0 || rows==0 || bad) exit 61; }\n"
            + "length($0)>0 { used=0; for (ref in refs) if ($0==ref || index($0,ref\"/\")==1) { used=1; break; } if (!used) print; }\n"
            + "' | { reclaimed=0\nwhile IFS= read -r file; do\n"
            + "[ -f \"$file\" ] && [ ! -L \"$file\" ] || continue\n"
            + "size=$(stat -c %s \"$file\"); rm \"$file\"; reclaimed=$((reclaimed + size))\n"
            + "done\nprintf 'RECLAIMED=%s\\n' \"$reclaimed\"\n}\n";
    }
}
