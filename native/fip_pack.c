/* Candidate-file generator for the supported one-entry CP FIP structure.
 * Refuses existing destinations and all non-regular input files.
 * Does not provide partition writing. SPDX-License-Identifier: MIT
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void die(const char *s) { fprintf(stderr, "%s (errno=%d)\n", s, errno); exit(1); }
static uint64_t le(const unsigned char *p, int n) { uint64_t x=0; for(int j=0;j<n;j++) x|=(uint64_t)p[j]<<(j*8); return x; }
static void put64(unsigned char *p,uint64_t x) { for(int j=0;j<8;j++) p[j]=(unsigned char)(x>>(j*8)); }
static int regular(const char *p, struct stat *st) {
    int fd=open(p,O_RDONLY|O_NOFOLLOW); if(fd<0 || fstat(fd,st) || !S_ISREG(st->st_mode)) die("input must be a regular file"); return fd;
}
static void read_exact(int fd,void *p,size_t n,off_t off) {
    unsigned char *q=p; while(n) { ssize_t r=pread(fd,q,n,off); if(r<=0) die("short read"); q+=r;n-=r;off+=r; }
}
static void write_exact(int fd,const void *p,size_t n) {
    const unsigned char *q=p; while(n) { ssize_t r=write(fd,q,n); if(r<=0) die("short write"); q+=r;n-=r; }
}
int main(int argc,char **argv) {
    if(argc!=4) die("usage: cp_fip_candidate ORIGINAL_FIP REBUILT_EROFS NEW_CANDIDATE");
    struct stat a,b; int fa=regular(argv[1],&a),fb=regular(argv[2],&b);
    unsigned char hdr[4096],sb[128],buf[65536];
    static const unsigned char uuid[16]={0x8a,0x50,0x46,0x7f,0x89,0x9d,0x4a,0xb3,0x9e,0x97,0x8f,0xe0,0xed,0x6d,0x1d,0xcb};
    read_exact(fa,hdr,sizeof(hdr),0);
    if(a.st_size!=1073741824 || le(hdr,4)!=0xaa640001 || le(hdr+4,4)!=0x12345678 || le(hdr+8,8) || memcmp(hdr+16,uuid,16)) die("unrecognized original FIP header");
    uint64_t old_size=le(hdr+40,8);
    if(le(hdr+32,8)!=4096 || le(hdr+48,8) || old_size> (uint64_t)a.st_size-4096 || le(hdr+72,8)!=4096+old_size) die("unexpected FIP entry");
    for(int j=56;j<72;j++) if(hdr[j]) die("multiple FIP entries");
    for(int j=80;j<4096;j++) if(hdr[j]) die("nonzero FIP reserved bytes");
    read_exact(fb,sb,sizeof(sb),1024);
    if(le(sb,4)!=0xe0f5e1e2 || sb[12]!=12 || le(sb+8,4)!=1 || le(sb+80,4)!=0 || b.st_size<4096 || b.st_size%4096 || b.st_size>a.st_size-4096) die("unsupported candidate filesystem");
    if(le(sb+36,4)*4096!=(uint64_t)b.st_size) die("candidate block count differs from file size");
    /* A shorter OTA payload can leave opaque bytes from the older image.
     * Preserve them exactly, and refuse growth into any nonzero trailer byte.
     * This is NOT a determination that the bootloader accepts the image.
     */
    for(uint64_t off=4096+old_size;off<4096+(uint64_t)b.st_size;) {
        size_t n=4096+(uint64_t)b.st_size-off<sizeof(buf)?4096+(uint64_t)b.st_size-off:sizeof(buf);
        read_exact(fa,buf,n,off);
        for(size_t j=0;j<n;j++) if(buf[j]) die("candidate growth overlaps nonzero original trailer");
        off+=n;
    }
    put64(hdr+40,b.st_size); put64(hdr+72,4096+b.st_size);
    int out=open(argv[3],O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600); if(out<0) die("create exclusive candidate");
    write_exact(out,hdr,sizeof(hdr));
    for(off_t off=0;off<b.st_size;) { size_t n=b.st_size-off<(off_t)sizeof(buf)?b.st_size-off:sizeof(buf);read_exact(fb,buf,n,off);write_exact(out,buf,n);off+=n; }
    for(off_t off=4096+b.st_size;off<a.st_size;) { size_t n=a.st_size-off<(off_t)sizeof(buf)?a.st_size-off:sizeof(buf);read_exact(fa,buf,n,off);write_exact(out,buf,n);off+=n; }
    if(ftruncate(out,a.st_size)||fsync(out)||close(out)) die("candidate flush");
    printf("{\"result\":\"PASS\",\"original_payload_bytes\":%llu,\"candidate_payload_bytes\":%llu,\"partition_bytes\":%llu,\"modified_header_fields\":[\"entry_size\",\"terminator_offset\"],\"opaque_trailer_preserved\":true,\"partition_written\":false}\n",(unsigned long long)old_size,(unsigned long long)b.st_size,(unsigned long long)a.st_size);
    close(fa);close(fb);return 0;
}
