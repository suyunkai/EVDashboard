/* Independent byte-level, read-only verifier. No partition writing. */
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void fail(const char *s) { fprintf(stderr,"FAIL: %s\n",s); exit(1); }
static uint64_t le64(const unsigned char *p) { uint64_t v=0; for(int i=0;i<8;i++) v|=(uint64_t)p[i]<<(8*i); return v; }
static int input(const char *p, struct stat *s) {
    int fd=open(p,O_RDONLY|O_NOFOLLOW);
    if(fd<0 || fstat(fd,s) || !S_ISREG(s->st_mode)) fail("regular input required");
    return fd;
}
static void rd(int fd,void *p,size_t n,off_t off) {
    unsigned char *q=p;
    while(n) { ssize_t r=pread(fd,q,n,off); if(r<=0) fail("short read"); q+=r; off+=r; n-=r; }
}
static void equal_region(int a,off_t ao,int b,off_t bo,off_t length,const char *what) {
    unsigned char x[65536],y[65536];
    while(length) {
        size_t n=length<(off_t)sizeof(x)?length:sizeof(x);
        rd(a,x,n,ao);rd(b,y,n,bo);
        if(memcmp(x,y,n)) fail(what);
        length-=n;ao+=n;bo+=n;
    }
}
int main(int argc,char **argv) {
    if(argc!=4) fail("usage: cp_fip_verify ORIGINAL REBUILT CANDIDATE");
    struct stat sa,sb,sc;
    int a=input(argv[1],&sa),b=input(argv[2],&sb),c=input(argv[3],&sc);
    if(sa.st_size!=sc.st_size || sb.st_size<=0 || sb.st_size>sa.st_size-4096) fail("size mismatch");
    unsigned char x[4096],y[4096];rd(a,x,sizeof(x),0);rd(c,y,sizeof(y),0);
    if(le64(y+40)!=(uint64_t)sb.st_size || le64(y+72)!=(uint64_t)sb.st_size+4096) fail("FIP length fields");
    memcpy(x+40,y+40,8);memcpy(x+72,y+72,8);
    if(memcmp(x,y,sizeof(x))) fail("unexpected header modification");
    equal_region(b,0,c,4096,sb.st_size,"payload mismatch");
    equal_region(a,4096+sb.st_size,c,4096+sb.st_size,sa.st_size-4096-sb.st_size,"opaque trailer mismatch");
    printf("{\"result\":\"PASS\",\"header_only_size_fields_changed\":true,\"payload_byte_exact\":true,\"trailer_byte_exact\":true,\"trailer_bytes\":%llu}\n",(unsigned long long)(sa.st_size-4096-sb.st_size));
    close(a);close(b);close(c);return 0;
}
