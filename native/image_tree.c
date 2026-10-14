/* Research-only EROFS round-trip validator; never opens a block device for writing.
 * Built against pinned erofs-utils v1.9.1. SPDX-License-Identifier: GPL-2.0-or-later
 */
#include "erofs/config.h"
#include "erofs/dir.h"
#include "erofs/io.h"
#include "erofs/xattr.h"
#include <fcntl.h>
#include <inttypes.h>
#include <stdarg.h>
#include <sys/stat.h>
#ifndef __APPLE__
#include <sys/sysmacros.h>
#endif
#include <unistd.h>

static struct erofs_sb_info src, dst;
static uint64_t paths, files, dirs, links, xattrs, bytes, aliases;
static unsigned char data_a[65536], data_b[65536];
static FILE *tarout;
/* A supported 1 GiB image must not expand into an unbounded staging archive. */
static uint64_t tarbytes;
#define TAR_LIMIT (UINT64_C(1536) * 1024 * 1024)
/* Optional research injection validator. Strict roundtrip remains the default. */
static void (*validate_extra)(uint64_t nid, const char *path);
/* Scoped content replacement, after every original metadata check. */
static bool (*validate_modified)(struct erofs_inode *, struct erofs_inode *, const char *);
static bool (*replace_export)(struct erofs_inode *, const char *, const unsigned char **);
struct identity { uint64_t nid, peer; char *path; bool used; };
static struct identity forward[131072], reverse[131072];

static void fail(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); vfprintf(stderr, fmt, ap); va_end(ap);
    fputc('\n', stderr); exit(1);
}
static void *alloc(size_t n) {
    void *p = calloc(1, n ? n : 1); if (!p) fail("out of memory"); return p;
}
static void check(int rc, const char *what) {
    if (rc < 0) fail("%s: %s", what, strerror(-rc));
}
static struct identity *identity(struct identity *table, uint64_t nid) {
    size_t i = (nid * UINT64_C(11400714819323198485)) >> 47;
    for (size_t n = 0; n < 131072; n++, i = (i + 1) & 131071)
        if (!table[i].used || table[i].nid == nid) return &table[i];
    fail("inode identity table full"); return NULL;
}
static void open_image(struct erofs_sb_info *sbi, const char *path, const char *off) {
    char *end; unsigned long long n = strtoull(off, &end, 0);
    if (!*off || *end) fail("invalid offset");
    sbi->bdev.offset = n;
    check(erofs_dev_open(sbi, path, O_RDONLY), "open image read-only");
    check(erofs_read_superblock(sbi), "read superblock");
    if (sbi->extra_devices) fail("external blob devices are not supported");
}
static struct erofs_inode inode(struct erofs_sb_info *sbi, uint64_t nid) {
    struct erofs_inode i = {.sbi = sbi, .nid = nid};
    check(erofs_read_inode_from_disk(&i), "read inode"); return i;
}
struct child { char *name; uint64_t nid; };
struct children { struct erofs_dir_context ctx; struct child *v; size_t n, cap; };
static int collect(struct erofs_dir_context *ctx) {
    struct children *c = (void *)ctx;
    if (ctx->dot_dotdot) return 0;
    if (c->n >= 131072) fail("directory entry limit exceeded");
    if (c->n == c->cap) {
        c->cap = c->cap ? c->cap * 2 : 16;
        void *p = realloc(c->v, c->cap * sizeof(*c->v));
        if (!p) fail("out of memory"); c->v = p;
    }
    c->v[c->n].name = strndup(ctx->dname, ctx->de_namelen);
    if (!c->v[c->n].name) fail("out of memory");
    c->v[c->n++].nid = ctx->de_nid;
    return 0;
}
static int childcmp(const void *a, const void *b) {
    return strcmp(((const struct child *)a)->name, ((const struct child *)b)->name);
}
static struct children children(struct erofs_inode *i) {
    struct children c = {.ctx = {.dir = i, .cb = collect}};
    check(erofs_iterate_dir(&c.ctx, false), "read directory");
    qsort(c.v, c.n, sizeof(*c.v), childcmp); return c;
}
static void freechildren(struct children *c) {
    for (size_t i = 0; i < c->n; i++) free(c->v[i].name); free(c->v);
}
static char *names(struct erofs_inode *i, int *size) {
    *size = erofs_listxattr(i, NULL, 0); check(*size, "list xattr size");
    char *p = alloc(*size);
    if (*size && erofs_listxattr(i, p, *size) != *size) fail("list xattrs");
    return p;
}
static void stats(struct erofs_inode *i) {
    if (++paths > 262144) fail("filesystem path limit exceeded");
    if (S_ISREG(i->i_mode)) files++;
    else if (S_ISDIR(i->i_mode)) dirs++;
    else if (S_ISLNK(i->i_mode)) links++;
}
static void compare(uint64_t an, uint64_t bn, const char *path, int depth) {
    if (depth > 128) fail("directory nesting limit");
    struct erofs_inode a = inode(&src, an), b = inode(&dst, bn);
    stats(&a);
#define SAME(field) if (a.field != b.field) fail("mismatch %s: %s (%llu != %llu)", path, #field, (unsigned long long)a.field, (unsigned long long)b.field)
    SAME(i_mode); SAME(i_uid); SAME(i_gid); SAME(i_mtime); SAME(i_mtime_nsec); SAME(i_nlink);
    int na, nb; char *ka = names(&a, &na), *kb = names(&b, &nb);
    if (na != nb) fail("mismatch %s: xattr names length %d != %d", path, na, nb);
    for (char *key = ka; key < ka + na; key += strlen(key) + 1) {
        int sa = erofs_getxattr(&a, key, NULL, 0), sb = erofs_getxattr(&b, key, NULL, 0);
        if (sa < 0 || sa != sb) fail("mismatch %s: xattr %s size", path, key);
        char *va = alloc(sa), *vb = alloc(sb);
        if (erofs_getxattr(&a, key, va, sa) != sa || erofs_getxattr(&b, key, vb, sb) != sb || memcmp(va, vb, sa))
            fail("mismatch %s: xattr %s value", path, key);
        xattrs++; free(va); free(vb);
    }
    free(ka); free(kb);
    if (S_ISDIR(a.i_mode)) {
        struct children ac = children(&a), bc = children(&b);
        if (!validate_extra && ac.n != bc.n) fail("mismatch %s: directory entry count", path);
        size_t j=0,k=0;
        while (j<ac.n || k<bc.n) {
            int order=j==ac.n?1:k==bc.n?-1:strcmp(ac.v[j].name,bc.v[k].name);
            if (order<0) fail("mismatch %s: original child missing",path);
            char *next=alloc(strlen(path)+strlen(bc.v[k].name)+2);
            sprintf(next,"%s/%s",path,bc.v[k].name);
            if (order>0) {
                if (!validate_extra) fail("unexpected added path %s",next);
                validate_extra(bc.v[k].nid,next);
            } else { compare(ac.v[j].nid,bc.v[k].nid,next,depth+1);j++; }
            free(next);k++;
        }
        freechildren(&ac); freechildren(&bc);
    } else {
        struct identity *f = identity(forward, an), *r = identity(reverse, bn);
        if ((f->used && f->peer != bn) || (r->used && r->peer != an)) fail("mismatch %s: hardlink topology", path);
        if (f->used) { aliases++; return; }
        *f = (struct identity){.nid=an,.peer=bn,.used=true};
        *r = (struct identity){.nid=bn,.peer=an,.used=true};
        if (validate_modified && validate_modified(&a, &b, path)) return;
        if (S_ISREG(a.i_mode) || S_ISLNK(a.i_mode)) {
            SAME(i_size);
            struct erofs_vfile av, bv;
            check(erofs_iopen(&av, &a), "open left inode"); check(erofs_iopen(&bv, &b), "open right inode");
            for (uint64_t off = 0; off < a.i_size;) {
                size_t n = a.i_size - off < sizeof(data_a) ? a.i_size - off : sizeof(data_a);
                check(erofs_pread(&av, data_a, n, off), "read left data");
                check(erofs_pread(&bv, data_b, n, off), "read right data");
                if (memcmp(data_a, data_b, n)) fail("mismatch %s: content at offset %llu", path, (unsigned long long)off);
                off += n; bytes += n;
            }
        } else if (S_ISCHR(a.i_mode) || S_ISBLK(a.i_mode)) { SAME(u.i_rdev); }
    }
#undef SAME
}
static void output(const void *p, size_t n) {
    if (n > TAR_LIMIT - tarbytes) fail("staging archive exceeds 1536 MiB safety limit");
    if (fwrite(p, 1, n, tarout) != n) fail("write output: %s", strerror(errno));
    tarbytes += n;
}
static void pad(size_t n) { unsigned char zero[512] = {0}; if (n % 512) output(zero, 512 - n % 512); }
static void octal(unsigned char *p, size_t n, uint64_t x) {
    int written = snprintf((char *)p, n, "%0*llo", (int)n - 1, (unsigned long long)x);
    if (written < 0 || written >= (int)n) fail("tar numeric field overflow");
}
static void header(const char *name, char type, uint64_t size, struct erofs_inode *i, const char *link) {
    unsigned char h[512] = {0};
    snprintf((char *)h, 100, "%s", name);
    octal(h + 100, 8, i->i_mode & 07777); octal(h + 108, 8, 0); octal(h + 116, 8, 0);
    octal(h + 124, 12, size); octal(h + 136, 12, i->i_mtime);
    memset(h + 148, ' ', 8); h[156] = type;
    if (link) snprintf((char *)h + 157, 100, "%s", link);
    memcpy(h + 257, "ustar\0" "00", 8);
    if (type == '3' || type == '4') { octal(h + 329, 8, major(i->u.i_rdev)); octal(h + 337, 8, minor(i->u.i_rdev)); }
    unsigned int sum = 0; for (int j = 0; j < 512; j++) sum += h[j];
    snprintf((char *)h + 148, 8, "%06o", sum); h[155] = ' '; output(h, sizeof(h));
}
struct pax { unsigned char *p; size_t n; };
static void paxadd(struct pax *p, const char *key, const void *value, size_t size) {
    size_t body = strlen(key) + size + 3, n = body + 1;
    char prefix[32];
    for (;;) { int d = snprintf(prefix, sizeof(prefix), "%zu", n); size_t next = body + d; if (next == n) break; n = next; }
    void *q = realloc(p->p, p->n + n); if (!q) fail("out of memory"); p->p = q;
    size_t at = p->n, k = strlen(prefix);
    memcpy(p->p + at, prefix, k); at += k; p->p[at++] = ' ';
    k = strlen(key); memcpy(p->p + at, key, k); at += k; p->p[at++] = '=';
    memcpy(p->p + at, value, size); at += size; p->p[at++] = '\n';
    if (at != p->n + n) fail("PAX length error"); p->n = at;
}
static void paxstr(struct pax *p, const char *key, const char *value) { paxadd(p, key, value, strlen(value)); }
static void export_inode(uint64_t nid, const char *path, int depth) {
    if (depth > 128) fail("directory nesting limit");
    struct erofs_inode i = inode(&src, nid); stats(&i);
    const unsigned char *replacement = NULL;
    if (replace_export) replace_export(&i, path, &replacement);
    struct identity *f = identity(forward, nid);
    bool hard = !S_ISDIR(i.i_mode) && f->used;
    char *link = NULL;
    if (hard) { link = strdup(f->path); aliases++; }
    else if (!S_ISDIR(i.i_mode)) { *f = (struct identity){.nid=nid,.used=true,.path=strdup(path)}; }
    if (S_ISLNK(i.i_mode) && !hard) {
        struct erofs_vfile vf; check(erofs_iopen(&vf, &i), "open symlink");
        link = alloc(i.i_size + 1); check(erofs_pread(&vf, link, i.i_size, 0), "read symlink");
    }
    struct pax p = {0}; char number[64];
    paxstr(&p, "path", path);
    snprintf(number, sizeof(number), "%u", i.i_uid); paxstr(&p, "uid", number);
    snprintf(number, sizeof(number), "%u", i.i_gid); paxstr(&p, "gid", number);
    snprintf(number, sizeof(number), "%llu.%09u", (unsigned long long)i.i_mtime, i.i_mtime_nsec); paxstr(&p, "mtime", number);
    if (link) paxstr(&p, "linkpath", link);
    int nk; char *keys = names(&i, &nk);
    for (char *key = keys; key < keys + nk; key += strlen(key) + 1) {
        int nv = erofs_getxattr(&i, key, NULL, 0); check(nv, "xattr size");
        char *val = alloc(nv), *pk = alloc(strlen(key) + 14);
        if (erofs_getxattr(&i, key, val, nv) != nv) fail("read xattr");
        sprintf(pk, "SCHILY.xattr.%s", key); paxadd(&p, pk, val, nv);
        xattrs++; free(pk); free(val);
    }
    free(keys);
    header("PaxHeader", 'x', p.n, &i, NULL); output(p.p, p.n); pad(p.n); free(p.p);
    char type = hard ? '1' : S_ISDIR(i.i_mode) ? '5' : S_ISLNK(i.i_mode) ? '2' : S_ISREG(i.i_mode) ? '0' : S_ISCHR(i.i_mode) ? '3' : S_ISBLK(i.i_mode) ? '4' : S_ISFIFO(i.i_mode) ? '6' : 0;
    if (!type) fail("unsupported inode type at %s", path);
    uint64_t size = type == '0' ? i.i_size : 0;
    header("PaxEntry", type, size, &i, link); free(link);
    if (size) {
        struct erofs_vfile vf; check(erofs_iopen(&vf, &i), "open file");
        for (uint64_t off = 0; off < size;) {
            size_t n = size - off < sizeof(data_a) ? size - off : sizeof(data_a);
            if (replacement) memcpy(data_a, replacement + off, n);
            else check(erofs_pread(&vf, data_a, n, off), "read file");
            output(data_a, n); off += n; bytes += n;
        }
        pad(size);
    }
    if (S_ISDIR(i.i_mode)) {
        struct children c = children(&i);
        for (size_t j = 0; j < c.n; j++) {
            char *next = alloc(strlen(path) + strlen(c.v[j].name) + 2);
            sprintf(next, "%s/%s", path, c.v[j].name); export_inode(c.v[j].nid, next, depth + 1); free(next);
        }
        freechildren(&c);
    }
}
int main(int argc, char **argv) {
    if (argc < 2) fail("usage: compare IMAGE OFFSET IMAGE OFFSET | export IMAGE OFFSET NEW_TAR");
    erofs_init_configure(); cfg.c_dbg_lvl = 0;
    if (!strcmp(argv[1], "compare") && argc == 6) {
        open_image(&src, argv[2], argv[3]); open_image(&dst, argv[4], argv[5]);
        if (src.blkszbits != dst.blkszbits || dst.feature_incompat != src.feature_incompat || dst.feature_compat != src.feature_compat)
            fail("filesystem feature mismatch: compat %x/%x incompat %x/%x", src.feature_compat, dst.feature_compat, src.feature_incompat, dst.feature_incompat);
        compare(src.root_nid, dst.root_nid, ".", 0);
    } else if (!strcmp(argv[1], "export") && argc == 5) {
        open_image(&src, argv[2], argv[3]);
        int fd = open(argv[4], O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
        if (fd < 0) fail("create new tar: %s", strerror(errno));
        tarout = fdopen(fd, "wb"); if (!tarout) fail("fdopen");
        export_inode(src.root_nid, ".", 0);
        unsigned char zero[1024] = {0}; output(zero, sizeof(zero));
        if (fflush(tarout) || fsync(fd) || fclose(tarout)) fail("flush tar");
    } else fail("invalid arguments");
    printf("{\"result\":\"PASS\",\"operation\":\"%s\",\"paths\":%llu,\"regular_paths\":%llu,\"directories\":%llu,\"symlinks\":%llu,\"xattrs\":%llu,\"hardlink_aliases\":%llu,\"content_bytes\":%llu}\n",
        argv[1], (unsigned long long)paths, (unsigned long long)files, (unsigned long long)dirs, (unsigned long long)links, (unsigned long long)xattrs, (unsigned long long)aliases, (unsigned long long)bytes);
    return 0;
}
