/* Instrument Workshop image inspector and minimal SSH transform.
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Reads regular files only; never writes block devices. */
#define main tree_roundtrip_main
#include "image_tree.c"
#undef main

static const char *socket_path="./etc/systemd/system/sockets.target.wants/dropbear.socket";
static const char *socket_target="/lib/systemd/system/dropbear.socket";
static const char *socket_template="/etc/systemd/system/sockets.target.wants/tftpd-hpa.socket";
struct edit { const char *path; unsigned char *value; size_t size; unsigned seen; };
static struct edit edits[3];
static int edit_count;
static bool add_socket, socket_checked;

static struct erofs_inode lookup(const char *path) {
    struct erofs_inode i={.sbi=&src}; check(erofs_ilookup(path,&i),"lookup"); return i;
}
static unsigned char *read_inode_text(struct erofs_inode *i) {
    if ((!S_ISREG(i->i_mode) && !S_ISLNK(i->i_mode)) || i->i_size>1048576) fail("unsupported text inode");
    unsigned char *p=alloc(i->i_size+1); struct erofs_vfile v;
    check(erofs_iopen(&v,i),"open text"); check(erofs_pread(&v,p,i->i_size,0),"read text");
    if (memchr(p,0,i->i_size)) fail("embedded NUL in configuration"); return p;
}
static unsigned char *read_text(const char *path) { struct erofs_inode i=lookup(path); return read_inode_text(&i); }
static bool exists(const char *path) {
    struct erofs_inode i={.sbi=&src}; int r=erofs_ilookup(path,&i);
    if(r==-ENOENT)return false; check(r,"lookup optional path"); return true;
}
static void json_text(const unsigned char *s) {
    putchar('"'); for(;*s;s++) { if(*s=='"'||*s=='\\') {putchar('\\');putchar(*s);} else if(*s<32) printf("\\u%04x",*s); else putchar(*s); } putchar('"');
}
static void require_regular_file(const char *path) {
    struct stat st; if(lstat(path,&st)||!S_ISREG(st.st_mode)) fail("regular non-symlink image required");
}
static void root_field(unsigned char *shadow, unsigned char **start, unsigned char **end) {
    int count=0; for(unsigned char *p=shadow;*p;) {
        unsigned char *nl=(unsigned char*)strchr((char*)p,'\n');
        if(!strncmp((char*)p,"root:",5)) {
            *start=p+5; *end=(unsigned char*)strchr((char*)p+5,':');
            if(!*end || (nl && *end>nl))fail("invalid root shadow row"); count++;
        }
        if(!nl)break;p=nl+1;
    }
    if(count!=1)fail("root shadow row must be unique");
}
static const char *password_state(unsigned char *shadow) {
    unsigned char *a=NULL,*b=NULL;root_field(shadow,&a,&b);
    return a==b?"EMPTY":(*a=='!'||*a=='*')?"LOCKED":"SET";
}
/* Inspect active assignments, not substrings hidden in comments or other keys. */
static bool setting(const unsigned char *text,const char *section,const char *key,const char *wanted) {
    char *copy=strdup((const char*)text),*save=NULL,*line;bool in=!section,matched=false;unsigned count=0;
    for(line=strtok_r(copy,"\n",&save);line;line=strtok_r(NULL,"\n",&save)) {
        while(*line==' '||*line=='\t')line++;size_t n=strlen(line);while(n&&(line[n-1]==' '||line[n-1]=='\t'||line[n-1]=='\r'))line[--n]=0;
        if(!*line||*line=='#'||*line==';')continue;
        if(*line=='['){in=section&&!strcmp(line,section);continue;}
        if(!in)continue;char *eq=strchr(line,'=');if(!eq)continue;*eq=0;char *end=eq;while(end>line&&(end[-1]==' '||end[-1]=='\t'))*--end=0;
        if(strcmp(line,key))continue;char *value=eq+1;while(*value==' '||*value=='\t')value++;count++;matched=!strcmp(value,wanted);
    }
    free(copy);return count==1&&matched;
}
static bool blank_policy(const unsigned char *text) {return setting(text,NULL,"DROPBEAR_EXTRA_ARGS","\" -B\"");}
static void check_root_account(void) {
    unsigned char *passwd=read_text("/etc/passwd");char *save=NULL,*line;unsigned roots=0;
    for(line=strtok_r((char*)passwd,"\n",&save);line;line=strtok_r(NULL,"\n",&save)) {
        if(strncmp(line,"root:",5))continue;roots++;char *fields[8]={0},*p=line;unsigned n=0;
        while(n<8&&(fields[n++]=strsep(&p,":"))){}
        if(n!=8||fields[7]!=NULL||strcmp(fields[1],"x")||strcmp(fields[2],"0")||strcmp(fields[3],"0")||
            (strcmp(fields[6],"/bin/sh")&&strcmp(fields[6],"/bin/bash")))fail("unsupported root passwd policy");
    }
    free(passwd);if(roots!=1)fail("root passwd row must be unique");
}
static bool overrides(void) {
    const char *paths[]={"/etc/systemd/system/dropbear.socket","/etc/systemd/system/dropbear.socket.d","/lib/systemd/system/dropbear.socket.d",
        "/etc/systemd/system/dropbear@.service","/etc/systemd/system/dropbear@.service.d","/lib/systemd/system/dropbear@.service.d"};
    for(unsigned k=0;k<sizeof(paths)/sizeof(paths[0]);k++)if(exists(paths[k]))return true;return false;
}
static const char *socket_state(void) {
    if(overrides())return "OVERRIDE_UNKNOWN";
    unsigned char *unit=read_text("/lib/systemd/system/dropbear.socket");bool known=setting(unit,"[Socket]","ListenStream","22")&&setting(unit,"[Socket]","Accept","yes");free(unit);if(!known)return "UNKNOWN";
    unsigned char *service=read_text("/lib/systemd/system/dropbear@.service");known=setting(service,"[Service]","EnvironmentFile","-/etc/default/dropbear")&&setting(service,"[Service]","StandardInput","socket")&&setting(service,"[Service]","ExecStart","-/usr/sbin/dropbear -i -r ${DROPBEAR_RSAKEY_DIR}/dropbear_rsa_host_key $DROPBEAR_EXTRA_ARGS");free(service);if(!known)return "UNKNOWN";
    if(!exists(socket_path))return "DISABLED";
    struct erofs_inode i=lookup(socket_path);if(!S_ISLNK(i.i_mode))return "UNKNOWN";
    unsigned char *s=read_inode_text(&i);bool ok=!strcmp((char*)s,socket_target);free(s);
    return ok?"ENABLED":"UNKNOWN";
}
static void add_edit(const char *path,unsigned char *p) {
    struct erofs_inode i=lookup(path);
    if(!S_ISREG(i.i_mode)||i.i_nlink!=1)fail("configuration must be a single regular inode");
    edits[edit_count++]=(struct edit){path,p,strlen((char*)p),0};
}
static void prepare(bool enable,bool clear) {
    if(src.blkszbits!=12 || src.feature_compat!=1 || src.feature_incompat)fail("unsupported EROFS feature profile");
    check_root_account();
    const char *state=socket_state(); if(strcmp(state,"ENABLED")&&strcmp(state,"DISABLED"))fail("socket override or mask requires manual review");
    unsigned char *preset=read_text("/lib/systemd/system-preset/98-dropbear.preset");
    if(strcmp((char*)preset,"disable dropbear.socket\n")&&strcmp((char*)preset,"enable dropbear.socket\n"))fail("unsupported preset");
    if(enable) {
        add_socket=!strcmp(state,"DISABLED");
        if(!strcmp((char*)preset,"disable dropbear.socket\n"))add_edit("./lib/systemd/system-preset/98-dropbear.preset",(unsigned char*)strdup("enable dropbear.socket\n"));
    }
    free(preset);
    if(clear) {
        unsigned char *defaults=read_text("/etc/default/dropbear");
        /* The supported configuration permits blank passwords with -B. Unknown policies
           are deliberately rejected, rather than rewriting arbitrary shell. */
        if(!blank_policy(defaults))fail("empty-password policy is not the supported configuration");free(defaults);
        unsigned char *s=read_text("/etc/shadow"),*a=NULL,*b=NULL;root_field(s,&a,&b);
        if(*a=='!'||*a=='*')fail("locked root account is not a password-only change");
        if(a!=b){memmove(a,b,strlen((char*)b)+1);add_edit("./etc/shadow",s);}else free(s);
    }
}
static bool transform(struct erofs_inode *i,const char *path,const unsigned char **data) {
    for(int k=0;k<edit_count;k++)if(!strcmp(path,edits[k].path)) {
        if(edits[k].seen++)fail("duplicate edited path");i->i_size=edits[k].size;*data=edits[k].value;return true;
    }return false;
}
static bool verify_edit(struct erofs_inode *a,struct erofs_inode *b,const char *path) {
    (void)a;
    for(int k=0;k<edit_count;k++)if(!strcmp(path,edits[k].path)) {
        if(edits[k].seen++ || b->i_size!=edits[k].size)fail("edit count or size mismatch");
        unsigned char *p=read_inode_text(b);if(memcmp(p,edits[k].value,edits[k].size))fail("edited content mismatch");free(p);return true;
    }return false;
}
static void check_link(uint64_t nid,const char *path) {
    if(!add_socket||socket_checked||strcmp(path,socket_path))fail("unexpected added path");
    struct erofs_inode b=inode(&dst,nid),t=lookup(socket_template);
    if(b.i_mode!=(S_IFLNK|0777)||b.i_uid||b.i_gid||b.i_nlink!=1||b.i_mtime!=t.i_mtime||b.i_mtime_nsec!=t.i_mtime_nsec)fail("new link metadata mismatch");
    int na,nb;char *ka=names(&t,&na),*kb=names(&b,&nb);if(na!=nb)fail("link xattrs changed");
    for(char *key=ka;key<ka+na;key+=strlen(key)+1){int n=erofs_getxattr(&t,key,NULL,0),m=erofs_getxattr(&b,key,NULL,0);if(n<0||n!=m)fail("link xattr length");char *x=alloc(n),*y=alloc(n);if(erofs_getxattr(&t,key,x,n)!=n||erofs_getxattr(&b,key,y,n)!=n||memcmp(x,y,n))fail("link xattr value");free(x);free(y);}free(ka);free(kb);
    unsigned char *p=read_inode_text(&b);if(strcmp((char*)p,socket_target))fail("link target mismatch");free(p);socket_checked=true;
}
static void append_socket(void) {
    struct erofs_inode parent=lookup("/etc/systemd/system/sockets.target.wants"),t=lookup(socket_template);
    if(!S_ISDIR(parent.i_mode)||!S_ISLNK(t.i_mode))fail("missing directory or metadata template");
    struct pax p={0};char number[64];paxstr(&p,"path",socket_path);paxstr(&p,"uid","0");paxstr(&p,"gid","0");paxstr(&p,"linkpath",socket_target);
    snprintf(number,sizeof(number),"%llu.%09u",(unsigned long long)t.i_mtime,t.i_mtime_nsec);paxstr(&p,"mtime",number);
    int nk;char *keys=names(&t,&nk);for(char *key=keys;key<keys+nk;key+=strlen(key)+1){int n=erofs_getxattr(&t,key,NULL,0);check(n,"xattr size");char *v=alloc(n),*pk=alloc(strlen(key)+14);if(erofs_getxattr(&t,key,v,n)!=n)fail("xattr read");sprintf(pk,"SCHILY.xattr.%s",key);paxadd(&p,pk,v,n);free(pk);free(v);}free(keys);
    t.i_mode=0777;header("PaxHeader",'x',p.n,&t,NULL);output(p.p,p.n);pad(p.n);free(p.p);header("PaxEntry",'2',0,&t,socket_target);
}
int main(int argc,char **argv) {
    erofs_init_configure();cfg.c_dbg_lvl=0;
    if(argc==4&&!strcmp(argv[1],"inspect")) {
        require_regular_file(argv[2]);open_image(&src,argv[2],argv[3]);
        unsigned char *s=read_text("/etc/shadow"),*build=read_text("/etc/build.json"),*preset=read_text("/lib/systemd/system-preset/98-dropbear.preset"),*args=read_text("/etc/default/dropbear");
        check_root_account();printf("{\"socket\":\"%s\",\"rootPassword\":\"%s\",\"blankAllowed\":%s,\"presetEnabled\":%s,\"build\":",socket_state(),password_state(s),blank_policy(args)?"true":"false",!strcmp((char*)preset,"enable dropbear.socket\n")?"true":"false");json_text(build);puts("}");return 0;
    }
    bool exporting=argc==7&&!strcmp(argv[1],"export"),verifying=argc==8&&!strcmp(argv[1],"verify");
    if(!exporting&&!verifying)fail("usage: inspect IMAGE OFFSET | export IMAGE OFFSET ENABLE CLEAR NEW_TAR | verify IMAGE OFFSET ENABLE CLEAR CANDIDATE OFFSET");
    require_regular_file(argv[2]);open_image(&src,argv[2],argv[3]);
    if((strcmp(argv[4],"0")&&strcmp(argv[4],"1"))||(strcmp(argv[5],"0")&&strcmp(argv[5],"1")))fail("invalid flags");
    prepare(!strcmp(argv[4],"1"),!strcmp(argv[5],"1"));
    if(exporting){int fd=open(argv[6],O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);if(fd<0)fail("exclusive output required");tarout=fdopen(fd,"wb");if(!tarout)fail("fdopen");replace_export=transform;export_inode(src.root_nid,".",0);if(add_socket)append_socket();unsigned char zero[1024]={0};output(zero,sizeof(zero));if(fflush(tarout)||fsync(fd)||fclose(tarout))fail("flush failed");}
    else {require_regular_file(argv[6]);open_image(&dst,argv[6],argv[7]);if(src.blkszbits!=dst.blkszbits||src.feature_compat!=dst.feature_compat||src.feature_incompat!=dst.feature_incompat||memcmp(src.uuid,dst.uuid,16))fail("filesystem identity/features changed");validate_modified=verify_edit;validate_extra=check_link;compare(src.root_nid,dst.root_nid,".",0);if(add_socket&&!socket_checked)fail("missing new socket link");}
    for(int k=0;k<edit_count;k++)if(edits[k].seen!=1)fail("required edit not checked");
    printf("{\"result\":\"PASS\",\"paths\":%llu,\"edits\":%d,\"addedSocket\":%s}\n",(unsigned long long)paths,edit_count,add_socket?"true":"false");return 0;
}
