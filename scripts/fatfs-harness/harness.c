/*
 * Teste independente do exFAT virtual com o FatFs do PS2 (ps2sdk iop/fs/bdmfs_fatfs, que usa
 * github.com/fjtrujy/FatFs ramo iop-r0.16), compilado no PC. Ver build.sh.
 *
 *   harness <disco.img> <pasta-origem>   monta, lista, compara com a pasta, lê trechos,
 *                                        f_getfree, grava, remonta e confere de novo
 *   harness tcp:<porta> <pasta-origem>   o mesmo no VirtualExFat ao vivo (ExFatDevMain "servir")
 *   harness --vectors                    imprime vetores de teste (NameHash, SetChecksum, boot)
 *   harness --mkfs-ref <novo.img>        formata com f_mkfs e imprime a tabela up-case do FatFs
 *
 * ff.c é incluído aqui para usar as funções internas (get_fat, xname_sum, xdir_sum...).
 */
#define _FILE_OFFSET_BITS 64
#include "ff.c"

#include <stdarg.h>
#include <stdlib.h>
#include <unistd.h>

int raw_open(const char *spec);
uint64_t raw_sectors(void);
int raw_read(uint64_t lba, uint32_t count, void *buf);
int raw_write(uint64_t lba, uint32_t count, const void *buf);
void raw_close(void);
extern uint64_t g_offset, g_count;
extern unsigned long long g_reads, g_writes, g_read_sectors, g_write_sectors;

static int g_fail;
static void fail(const char *fmt, ...)
{
    va_list ap;
    g_fail++;
    if (g_fail > 40) return;
    va_start(ap, fmt);
    printf("  FALHA: ");
    vprintf(fmt, ap);
    printf("\n");
    va_end(ap);
}

/* ------------------------------------------------------------------ utilidades */

static uint32_t crc_tab[256];
static void crc_init(void)
{
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
        crc_tab[i] = c;
    }
}
static uint32_t crc32_upd(uint32_t crc, const uint8_t *p, size_t n)
{
    crc = ~crc;
    while (n--) crc = crc_tab[(crc ^ *p++) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

static uint64_t rnd_state = 0x5053325050534332ull;
static uint64_t rnd(void)
{
    uint64_t x = rnd_state;
    x ^= x << 13; x ^= x >> 7; x ^= x << 17;
    return rnd_state = x;
}

/* Byte do padrão de teste na posição i (acesso aleatório). */
static uint8_t pat(uint64_t seed, uint64_t i)
{
    uint64_t z = seed * 0x9E3779B97F4A7C15ull + i * 0xBF58476D1CE4E5B9ull;
    z ^= z >> 31; z *= 0x94D049BB133111EBull; z ^= z >> 29;
    return (uint8_t)z;
}

static int utf8_to_utf16(const char *s, WCHAR *out, int max)
{
    const unsigned char *p = (const unsigned char *)s;
    int n = 0;
    while (*p) {
        uint32_t c;
        if (*p < 0x80) c = *p++;
        else if ((*p & 0xE0) == 0xC0) { c = (*p++ & 0x1F) << 6; c |= *p++ & 0x3F; }
        else if ((*p & 0xF0) == 0xE0) { c = (*p++ & 0x0F) << 12; c |= (*p++ & 0x3F) << 6; c |= *p++ & 0x3F; }
        else { c = (*p++ & 0x07) << 18; c |= (*p++ & 0x3F) << 12; c |= (*p++ & 0x3F) << 6; c |= *p++ & 0x3F; }
        if (c >= 0x10000) {
            if (n + 2 > max) return -1;
            c -= 0x10000;
            out[n++] = (WCHAR)(0xD800 + (c >> 10));
            out[n++] = (WCHAR)(0xDC00 + (c & 0x3FF));
        } else {
            if (n + 1 > max) return -1;
            out[n++] = (WCHAR)c;
        }
    }
    out[n] = 0;
    return n;
}

/* Nome como a API do FatFs devolve (UTF-8 ou página de código); 0 se não dá para representar. */
static int api_name(const char *utf8, char *out, size_t outsz)
{
#if FF_LFN_UNICODE == 2
    if (strlen(utf8) + 1 > outsz) return 0;
    strcpy(out, utf8);
    return 1;
#else
    WCHAR w[300];
    int n = utf8_to_utf16(utf8, w, 299), j = 0;
    if (n < 0 || (size_t)n + 1 > outsz) return 0;
    for (int i = 0; i < n; i++) {
        WCHAR c = w[i];
        if (c >= 0x80) {
            c = ff_uni2oem(c, FF_CODE_PAGE);
            if (c == 0 || c >= 0x100) return 0;
        }
        out[j++] = (char)c;
    }
    out[j] = 0;
    return 1;
#endif
}

static int le16(const uint8_t *b) { return b[0] | b[1] << 8; }
static uint32_t le32(const uint8_t *b) { return (uint32_t)b[0] | (uint32_t)b[1] << 8 | (uint32_t)b[2] << 16 | (uint32_t)b[3] << 24; }
static uint64_t le64(const uint8_t *b) { return le32(b) | (uint64_t)le32(b + 4) << 32; }

static uint32_t sum32(uint32_t sum, uint8_t b) { return ((sum & 1) ? 0x80000000u : 0) + (sum >> 1) + b; }

/* ------------------------------------------------------------------ árvores */

typedef struct {
    char *rel;        /* "/DVD/jogo.iso" na codificação da API do FatFs */
    char *host;       /* caminho na pasta de origem (NULL: arquivo criado pelo teste) */
    uint64_t size;
    int dir;
    uint64_t pseed, poff, plen; /* trecho sobrescrito pelo padrão pat(pseed, i) */
} Ent;

typedef struct { Ent *v; int n, cap; } List;

static void list_add(List *l, Ent e)
{
    if (l->n == l->cap) {
        l->cap = l->cap ? l->cap * 2 : 1024;
        l->v = realloc(l->v, (size_t)l->cap * sizeof(Ent));
    }
    l->v[l->n++] = e;
}

static int ent_cmp(const void *a, const void *b)
{
    const Ent *x = a, *y = b;
    int c = strcmp(x->rel, y->rel);
    if (c) return c;
    if (x->size != y->size) return x->size < y->size ? -1 : 1;
    return x->dir - y->dir;
}

static int g_unrepresentable;

typedef void (*host_cb)(void *ctx, const char *name, const char *path, int isdir, uint64_t size);
int host_list(const char *dir, host_cb cb, void *ctx); /* host_walk.c */

typedef struct { List *l; const char *rel; } WalkCtx;

static void host_item(void *vctx, const char *name, const char *path, int isdir, uint64_t size)
{
    WalkCtx *c = vctx;
    char nm[1024], rp[4096];
    if (isdir < 0) { fail("stat %s", path); return; }
    int ok = api_name(name, nm, sizeof nm);
    if (!ok) { g_unrepresentable++; strcpy(nm, "?"); }
    snprintf(rp, sizeof rp, "%s/%s", c->rel, nm);
    Ent e = { strdup(rp), ok ? strdup(path) : NULL, size, isdir, 0, 0, 0 };
    list_add(c->l, e);
}

static void walk_host(List *l, const char *host, const char *rel)
{
    int start = l->n;
    WalkCtx c = { l, rel };
    if (host_list(host, host_item, &c) < 0) { fail("não abri %s", host); return; }
    int end = l->n;
    for (int i = start; i < end; i++)
        if (l->v[i].dir && l->v[i].host) walk_host(l, l->v[i].host, l->v[i].rel);
}

static void walk_fat(List *l, const char *rel)
{
    DIR dj;
    FILINFO fno;
    char path[4096];
    snprintf(path, sizeof path, "0:%s/", rel);
    FRESULT r = f_opendir(&dj, path);
    if (r != FR_OK) { fail("f_opendir(%s) = %d", path, r); return; }
    for (;;) {
        r = f_readdir(&dj, &fno);
        if (r != FR_OK) { fail("f_readdir(%s) = %d", path, r); break; }
        if (!fno.fname[0]) break;
        char rp[4096];
        snprintf(rp, sizeof rp, "%s/%s", rel, fno.fname);
        int dir = (fno.fattrib & AM_DIR) != 0;
        Ent e = { strdup(rp), NULL, dir ? 0 : (uint64_t)fno.fsize, dir, 0, 0, 0 };
        list_add(l, e);
        if (dir && strcmp(fno.fname, "?")) walk_fat(l, rp);
    }
    f_closedir(&dj);
}

static Ent *find(List *l, const char *rel)
{
    for (int i = 0; i < l->n; i++) if (!strcmp(l->v[i].rel, rel)) return &l->v[i];
    return NULL;
}

static int compare_trees(List *exp, List *got)
{
    qsort(exp->v, exp->n, sizeof(Ent), ent_cmp);
    qsort(got->v, got->n, sizeof(Ent), ent_cmp);
    int i = 0, j = 0, bad = 0;
    while (i < exp->n || j < got->n) {
        int c = i >= exp->n ? 1 : j >= got->n ? -1 : ent_cmp(&exp->v[i], &got->v[j]);
        if (c == 0) { i++; j++; continue; }
        bad++;
        if (c < 0) { fail("falta no FatFs: %s (%llu)", exp->v[i].rel, (unsigned long long)exp->v[i].size); i++; }
        else { fail("sobra no FatFs: %s (%llu)", got->v[j].rel, (unsigned long long)got->v[j].size); j++; }
    }
    return bad;
}

/* ------------------------------------------------------------------ conteúdo */

static void expected(const Ent *e, uint64_t off, uint8_t *buf, size_t len)
{
    memset(buf, 0, len);
    if (e->host) {
        FILE *f = fopen(e->host, "rb");
        if (f) {
            if (fseeko(f, (off_t)off, SEEK_SET) == 0) (void)!fread(buf, 1, len, f);
            fclose(f);
        }
    }
    for (size_t k = 0; k < len; k++) {
        uint64_t p = off + k;
        if (e->plen && p >= e->poff && p < e->poff + e->plen) buf[k] = pat(e->pseed, p);
    }
}

static uint8_t g_a[1 << 20], g_b[1 << 20];
static unsigned long long g_ranges, g_bytes;

static int check_range(FIL *fp, const Ent *e, uint64_t off, size_t len, uint32_t *crc)
{
    UINT br = 0;
    if (off >= e->size) return 0;
    if (off + len > e->size) len = (size_t)(e->size - off);
    FRESULT r = f_lseek(fp, off);
    if (r == FR_OK) r = f_read(fp, g_a, (UINT)len, &br);
    if (r != FR_OK || br != len) { fail("f_read %s @%llu+%zu: res=%d br=%u", e->rel, (unsigned long long)off, len, r, br); return 1; }
    expected(e, off, g_b, len);
    g_ranges++; g_bytes += len;
    if (crc) *crc = crc32_upd(*crc, g_a, len);
    if (memcmp(g_a, g_b, len)) {
        size_t k = 0;
        while (g_a[k] == g_b[k]) k++;
        fail("conteúdo diferente em %s @%llu (byte %zu)", e->rel, (unsigned long long)off, k);
        return 1;
    }
    return 0;
}

/* ------------------------------------------------------------------ alocação */

static uint8_t *g_mark;
static FATFS g_fs;

/* Percorre a cadeia como o get_frag_list do bdmfs_fatfs; marca os clusters; devolve quantos. */
static long walk_chain(FFOBJID *obj, const char *what, long expect, int *frags)
{
    FATFS *fs = obj->fs;
    DWORD c = obj->sclust;
    long n = 0;
    *frags = 1;
    for (;;) {
        if (c < 2 || c >= fs->n_fatent) { fail("%s: cluster inválido %u", what, c); return -1; }
        if (g_mark[c]) fail("%s: cluster %u já usado por outro objeto", what, c);
        g_mark[c] = 1;
        n++;
        if (expect >= 0 && n > expect) { fail("%s: cadeia maior que o tamanho (%ld)", what, expect); return -1; }
        DWORD nx = get_fat(obj, c);
        if (nx == 0x7FFFFFFF) break;
        if (nx < 2 || nx >= fs->n_fatent) { fail("%s: cadeia quebrada no cluster %u (valor %08x)", what, c, nx); return -1; }
        if (nx != c + 1) (*frags)++;
        c = nx;
    }
    if (expect >= 0 && n != expect) fail("%s: %ld clusters, esperado %ld", what, n, expect);
    return n;
}

static long g_files_checked, g_dirs_checked, g_multi_frag, g_fatchain_objs;

/* Buffers de nome por nível (com FF_USE_LFN=2 o FatFs os põe na pilha de cada chamada da API). */
#define MAX_DEPTH 80
static BYTE g_dbuf[MAX_DEPTH][MAXDIRB(FF_MAX_LFN)];
static WCHAR g_lbuf[MAX_DEPTH][FF_MAX_LFN + 1];

static void set_bufs(int depth)
{
    g_fs.dirbuf = g_dbuf[depth];
    g_fs.lfnbuf = g_lbuf[depth];
}

/* Nome do conjunto de entradas em fs->dirbuf, em UTF-8 (só para mensagens). */
static void entry_name(const BYTE *db, char *out, size_t sz)
{
    size_t j = 0;
    for (int i = 0; i < db[XDIR_NumName] && j + 4 < sz; i++) {
        WCHAR c = ld_16(db + 64 + (i / 15) * 32 + 2 + (i % 15) * 2);
        if (c < 0x80) out[j++] = (char)c;
        else if (c < 0x800) { out[j++] = (char)(0xC0 | c >> 6); out[j++] = (char)(0x80 | (c & 0x3F)); }
        else { out[j++] = (char)(0xE0 | c >> 12); out[j++] = (char)(0x80 | ((c >> 6) & 0x3F)); out[j++] = (char)(0x80 | (c & 0x3F)); }
    }
    out[j] = 0;
}

/*
 * Percorre as pastas com as funções internas do FatFs (dir_sdi/dir_read/dir_next), sem depender
 * da página de código: pega todos os objetos, inclusive os de nome que o PS2 mostra como "?".
 * strict: mais de um fragmento é falha (arquivos originais); senão só informa.
 */
static void alloc_walk(FFOBJID *dobj, int depth, const char *rel, int strict)
{
    DIR dj;
    DWORD csz = g_fs.csize * 512u;
    if (depth >= MAX_DEPTH) { fail("%s: fundo demais", rel); return; }
    memset(&dj, 0, sizeof dj);
    dj.obj = *dobj;
    set_bufs(depth);
    FRESULT r = dir_sdi(&dj, 0);
    if (r != FR_OK) { fail("%s: dir_sdi = %d", rel, r); return; }
    for (;;) {
        set_bufs(depth);
        r = dir_read(&dj, 0);
        if (r == FR_NO_FILE) break;
        if (r != FR_OK) { fail("%s: dir_read = %d (SetChecksum?)", rel, r); break; }
        const BYTE *db = g_fs.dirbuf;
        char nm[1024], rp[4096];
        entry_name(db, nm, sizeof nm);
        snprintf(rp, sizeof rp, "%s/%s", rel, nm);
        FFOBJID o;
        memset(&o, 0, sizeof o);
        o.fs = &g_fs;
        o.sclust = ld_32(db + XDIR_FstClus);
        o.objsize = ld_64(db + XDIR_FileSize);
        o.stat = db[XDIR_GenFlags] & 2;
        int dir = (db[XDIR_Attr] & AM_DIR) != 0, frags = 1;
        if (o.objsize == 0) {
            if (o.sclust != 0) fail("%s: vazio mas com cluster", rp);
        } else {
            walk_chain(&o, rp, (long)((o.objsize + csz - 1) / csz), &frags);
            if (o.stat == 0) g_fatchain_objs++;
            if (frags != 1) {
                g_multi_frag++;
                if (strict) fail("%s: %d fragmentos", rp, frags);
                else printf("  (info) %s: %d fragmentos (alocação do próprio FatFs)\n", rp, frags);
            }
        }
        if (dir) {
            g_dirs_checked++;
            if (o.objsize % csz) fail("%s: tamanho da pasta não é múltiplo do cluster", rp);
            alloc_walk(&o, depth + 1, rp, strict);
        } else {
            g_files_checked++;
        }
        r = dir_next(&dj, 0);
        if (r == FR_NO_FILE) break;
        if (r != FR_OK) { fail("%s: dir_next = %d", rel, r); break; }
    }
}

typedef struct { DWORD bitmap_cl, upcase_cl; uint64_t bitmap_len, upcase_len; uint32_t upcase_sum; int label_len; WCHAR label[12]; } RootInfo;

static int read_root_info(RootInfo *ri)
{
    static uint8_t sec[512];
    memset(ri, 0, sizeof *ri);
    for (UINT s = 0; s < g_fs.csize; s++) {
        if (disk_read(0, sec, clst2sect(&g_fs, (DWORD)g_fs.dirbase) + s, 1) != RES_OK) return 0;
        for (int o = 0; o < 512; o += 32) {
            uint8_t t = sec[o];
            if (t == 0) return 1;
            if (t == 0x81) { ri->bitmap_cl = le32(sec + o + 20); ri->bitmap_len = le64(sec + o + 24); }
            if (t == 0x82) { ri->upcase_sum = le32(sec + o + 4); ri->upcase_cl = le32(sec + o + 20); ri->upcase_len = le64(sec + o + 24); }
            if (t == 0x83) { ri->label_len = sec[o + 1]; for (int k = 0; k < 11; k++) ri->label[k] = (WCHAR)le16(sec + o + 2 + k * 2); }
        }
    }
    return 1;
}

/* Confere bitmap do disco x clusters realmente usados; devolve os clusters livres no bitmap. */
static long check_allocation(const char *when, int strict)
{
    RootInfo ri;
    int frags;
    DWORD ncl = g_fs.n_fatent - 2, csz = g_fs.csize * 512u;
    free(g_mark);
    g_mark = calloc(g_fs.n_fatent, 1);
    g_files_checked = g_dirs_checked = g_multi_frag = g_fatchain_objs = 0;
    if (!read_root_info(&ri)) { fail("raiz ilegível"); return -1; }

    FFOBJID o;
    memset(&o, 0, sizeof o);
    o.fs = &g_fs;
    o.sclust = ri.bitmap_cl; o.objsize = ri.bitmap_len; o.stat = 0;
    walk_chain(&o, "bitmap", (long)((ri.bitmap_len + csz - 1) / csz), &frags);
    o.sclust = ri.upcase_cl; o.objsize = ri.upcase_len;
    walk_chain(&o, "up-case", (long)((ri.upcase_len + csz - 1) / csz), &frags);
    o.sclust = (DWORD)g_fs.dirbase; o.objsize = 0;
    walk_chain(&o, "raiz", -1, &frags);
    o.sclust = 0; /* raiz para dir_sdi */
    alloc_walk(&o, 0, "", strict);

    static uint8_t sec[512];
    long set = 0, leak = 0, danger = 0;
    for (DWORD i = 0; i < ncl; i++) {
        if (i % 4096 == 0 && disk_read(0, sec, g_fs.bitbase + i / 4096, 1) != RES_OK) { fail("bitmap ilegível"); return -1; }
        int bit = (sec[(i % 4096) / 8] >> (i % 8)) & 1;
        int used = g_mark[i + 2];
        set += bit;
        if (bit && !used) leak++;
        if (!bit && used) danger++;
    }
    printf("  [%s] objetos conferidos: %ld arquivos, %ld pastas; %ld com cadeia no FAT; %ld com mais de 1 fragmento\n",
           when, g_files_checked, g_dirs_checked, g_fatchain_objs, g_multi_frag);
    printf("  [%s] bitmap: %ld de %u clusters ocupados; ocupados sem dono: %ld; usados mas livres no bitmap: %ld\n",
           when, set, ncl, leak, danger);
    if (leak) fail("%ld clusters marcados no bitmap sem nenhum objeto", leak);
    if (danger) fail("%ld clusters em uso aparecem livres no bitmap", danger);
    return (long)ncl - set;
}

static void check_boot_and_upcase(int compare_backup)
{
    static uint8_t b[24 * 512];
    for (int s = 0; s < 24; s++)
        if (disk_read(0, b + s * 512, g_fs.volbase + s, 1) != RES_OK) { fail("boot ilegível"); return; }
    uint32_t sum = 0;
    for (int i = 0; i < 11 * 512; i++) if (i != 106 && i != 107 && i != 112) sum = sum32(sum, b[i]);
    int okc = 1;
    for (int i = 0; i < 128; i++) if (le32(b + 11 * 512 + i * 4) != sum) okc = 0;
    for (int s = 1; s <= 8; s++) if (le32(b + s * 512 + 508) != 0xAA550000u) fail("setor de boot estendido %d sem assinatura", s);
    printf("  boot: checksum %08X %s; PercentInUse %u%%; serial %08X\n", sum, okc ? "confere" : "NÃO confere", b[112], le32(b + 100));
    if (!okc) fail("checksum da região de boot");
    if (compare_backup) {
        if (memcmp(b, b + 12 * 512, 12 * 512)) fail("região de boot reserva difere da principal");
        else printf("  boot: região reserva (setores 12-23) igual à principal\n");
    }

    RootInfo ri;
    if (!read_root_info(&ri)) { fail("raiz ilegível"); return; }
    char lab[40] = { 0 };
    for (int k = 0; k < ri.label_len && k < 11; k++) lab[k] = (char)ri.label[k];
    printf("  raiz: rótulo \"%s\"; bitmap no cluster %u (%llu bytes); up-case no cluster %u (%llu bytes)\n", lab,
           ri.bitmap_cl, (unsigned long long)ri.bitmap_len, ri.upcase_cl, (unsigned long long)ri.upcase_len);
    if (ri.upcase_len == 0 || ri.upcase_len > 131072) { fail("tabela up-case ausente"); return; }
    static uint8_t t[131072];
    UINT ns = (UINT)((ri.upcase_len + 511) / 512);
    if (disk_read(0, t, clst2sect(&g_fs, ri.upcase_cl), ns) != RES_OK) { fail("up-case ilegível"); return; }
    uint32_t us = 0;
    for (uint64_t i = 0; i < ri.upcase_len; i++) us = sum32(us, t[i]);
    printf("  up-case: TableChecksum %08X, calculado %08X, CRC32 %08X\n", ri.upcase_sum, us, crc32_upd(0, t, (size_t)ri.upcase_len));
    if (us != ri.upcase_sum) fail("TableChecksum da up-case");
    /* Descomprime e compara com ff_wtoupper do FatFs. */
    static WCHAR m[65536];
    for (int i = 0; i < 65536; i++) m[i] = (WCHAR)i;
    uint64_t i = 0;
    DWORD idx = 0;
    while (i + 1 < ri.upcase_len && idx < 65536) {
        WCHAR v = (WCHAR)le16(t + i);
        i += 2;
        if (v == 0xFFFF && i + 1 < ri.upcase_len) { idx += le16(t + i); i += 2; }
        else m[idx++] = v;
    }
    int diff = 0;
    for (DWORD c = 0; c < 65536; c++) if (m[c] != (WCHAR)ff_wtoupper(c)) diff++;
    printf("  up-case: %d de 65536 caracteres diferem do ff_wtoupper do FatFs\n", diff);
    if (diff) fail("tabela up-case difere do FatFs em %d caracteres", diff);
}

/* ------------------------------------------------------------------ montagem */

static uint64_t g_disk;

static int open_image(const char *img, int whole)
{
    if (!raw_open(img)) { printf("não abri %s\n", img); return 0; }
    uint64_t disk = g_disk = raw_sectors();
    g_offset = 0; g_count = disk;
    printf("disco: %s, %llu setores (%.2f GiB)\n", img, (unsigned long long)disk, disk / 2097152.0);
    if (whole) return 1;

    /* Igual a part_connect_mbr() do BDM. */
    uint8_t mbr[512];
    if (!raw_read(0, 1, mbr)) return 0;
    if (le16(mbr + 510) != 0xAA55) { fail("MBR sem 0x55AA"); return 0; }
    if (mbr[446 + 4] == 0xEE) { fail("MBR protetora de GPT"); return 0; }
    int valid = 0, active = 0;
    for (int i = 0; i < 4; i++) {
        const uint8_t *p = mbr + 446 + i * 16;
        if (p[4] != 0) {
            if (le32(p + 8) == 0 || le32(p + 8) >= disk) { fail("BDM: partição %d inválida", i + 1); return 0; }
            active++;
        }
        valid++;
    }
    if (!(valid == 4 && active > 0)) { fail("BDM: MBR rejeitada"); return 0; }
    for (int i = 0; i < 4; i++) {
        const uint8_t *p = mbr + 446 + i * 16;
        if (le32(p + 12) == 0 || p[4] == 0) continue;
        g_offset = le32(p + 8);
        g_count = le32(p + 12);
        printf("BDM: partição %d tipo 0x%02X, LBA %llu, %llu setores; assinatura do disco %08X\n", i + 1, p[4],
               (unsigned long long)g_offset, (unsigned long long)g_count, le32(mbr + 440));
        if (g_offset + g_count > disk) { fail("partição passa do fim do disco"); return 0; }
        return 1;
    }
    fail("nenhuma partição");
    return 0;
}

static int mount(void)
{
    memset(&g_fs, 0, sizeof g_fs);
    FRESULT r = f_mount(&g_fs, "0:", 1);
    printf("f_mount = %d%s\n", r, r == FR_OK ? " (FR_OK)" : "");
    if (r != FR_OK) { fail("f_mount = %d", r); return 0; }
    printf("  fs_type %u (4 = exFAT), cluster %u setores, %u clusters, volbase %llu, fatbase %llu, database %llu, raiz cluster %llu, bitmap setor %llu\n",
           g_fs.fs_type, g_fs.csize, g_fs.n_fatent - 2, (unsigned long long)g_fs.volbase,
           (unsigned long long)g_fs.fatbase, (unsigned long long)g_fs.database, (unsigned long long)g_fs.dirbase,
           (unsigned long long)g_fs.bitbase);
    if (g_fs.fs_type != FS_EXFAT) fail("não é exFAT");
    return 1;
}

static long getfree(void)
{
    DWORD n;
    FATFS *fs;
    FRESULT r = f_getfree("0:", &n, &fs);
    if (r != FR_OK) { fail("f_getfree = %d", r); return -1; }
    printf("  f_getfree: %u clusters livres (%.1f MiB)\n", n, n * (double)fs->csize / 2048.0);
    return (long)n;
}

static void verify_contents(List *exp, const char *when)
{
    g_ranges = g_bytes = 0;
    long files = 0, bad = 0;
    uint32_t big_crc = 0;
    for (int i = 0; i < exp->n; i++) {
        Ent *e = &exp->v[i];
        if (e->dir || strchr(e->rel, '?') || e->size == 0) continue;
        char fp[4096];
        FIL f;
        snprintf(fp, sizeof fp, "0:%s", e->rel);
        FRESULT r = f_open(&f, fp, FA_READ);
        if (r != FR_OK) { fail("f_open(%s) = %d", fp, r); bad++; continue; }
        files++;
        if (e->size <= (1u << 20)) {
            bad += check_range(&f, e, 0, (size_t)e->size, NULL);
        } else {
            uint32_t crc = 0;
            bad += check_range(&f, e, 0, 65536, &crc);
            bad += check_range(&f, e, e->size - 70000, 70000, &crc);
            if (e->size > 0x100000000ull) {
                bad += check_range(&f, e, 0xFFFFFFFFull - 4096, 8192, &crc); /* cruza 4 GiB */
                bad += check_range(&f, e, 0x100000000ull + 123457, 300000, &crc);
            }
            for (int k = 0; k < 12; k++) {
                uint64_t off = rnd() % e->size;
                size_t len = 1 + (size_t)(rnd() % (1u << 20));
                bad += check_range(&f, e, off, len, &crc);
            }
            if (e->size > 0x100000000ull) big_crc = crc;
        }
        f_close(&f);
    }
    printf("  [%s] conteúdo: %ld arquivos, %llu trechos, %.1f MiB comparados com a origem; %ld diferenças%s",
           when, files, g_ranges, g_bytes / 1048576.0, bad, big_crc ? "" : "\n");
    if (big_crc) printf("; CRC32 dos trechos do arquivo > 4 GiB: %08X\n", big_crc);
}

static int write_file(const char *path, uint64_t seed, uint64_t start, uint64_t len, BYTE mode)
{
    FIL f;
    UINT bw;
    FRESULT r = f_open(&f, path, mode);
    if (r != FR_OK) { fail("f_open(%s, escrita) = %d", path, r); return 0; }
    if (mode & FA_OPEN_APPEND) {
        if (f.fptr != start) fail("append em %s começou em %llu", path, (unsigned long long)f.fptr);
    } else if (start) {
        r = f_lseek(&f, start);
        if (r != FR_OK) { fail("f_lseek(%s) = %d", path, r); f_close(&f); return 0; }
    }
    uint64_t done = 0;
    while (done < len) {
        size_t n = len - done > sizeof g_a ? sizeof g_a : (size_t)(len - done);
        for (size_t k = 0; k < n; k++) g_a[k] = pat(seed, start + done + k);
        r = f_write(&f, g_a, (UINT)n, &bw);
        if (r != FR_OK || bw != n) { fail("f_write(%s) = %d (%u de %zu)", path, r, bw, n); f_close(&f); return 0; }
        done += n;
    }
    r = f_close(&f);
    if (r != FR_OK) { fail("f_close(%s) = %d", path, r); return 0; }
    return 1;
}

static int run_image(const char *img, const char *src, int whole)
{
    crc_init();
    printf("== FatFs %s (FF_FS_MBR=%d, FF_LFN_UNICODE=%d, FF_CODE_PAGE=%d, FF_FS_TINY=%d, FF_LBA64=%d)\n",
           whole ? "disco inteiro" : "como no PS2: partição achada pelo BDM", FF_FS_MBR, FF_LFN_UNICODE, FF_CODE_PAGE,
           FF_FS_TINY, FF_LBA64);
    if (!open_image(img, whole)) return 1;
    if (!whole) {
        /* Sem o BDM, o FatFs do ps2sdk (FF_FS_MBR=0) não acha a partição sozinho. */
        uint64_t o = g_offset, c = g_count;
        g_offset = 0; g_count = g_disk;
        FATFS t;
        memset(&t, 0, sizeof t);
        FRESULT r = f_mount(&t, "0:", 1);
        printf("(informativo) f_mount no disco inteiro sem o driver de partição: %d%s\n", r,
               r == FR_NO_FILESYSTEM ? " (FR_NO_FILESYSTEM, esperado com FF_FS_MBR=0)" : "");
        f_unmount("0:");
        g_offset = o; g_count = c;
    }
    if (!mount()) return 1;
    check_boot_and_upcase(1);

    List exp = { 0 }, got = { 0 };
    walk_host(&exp, src, "");
    walk_fat(&got, "");
    int nd = 0, nf = 0;
    for (int i = 0; i < exp.n; i++) exp.v[i].dir ? nd++ : nf++;
    printf("  origem: %d arquivos, %d pastas (%d nomes fora da página de código %d)\n", nf, nd, g_unrepresentable, FF_CODE_PAGE);
    int diff = compare_trees(&exp, &got);
    printf("  listagem recursiva: %d entradas no FatFs, %d diferenças de nome/tamanho\n", got.n, diff);

    long free0 = check_allocation("antes", 1);
    long gf0 = getfree();
    if (gf0 != free0) fail("f_getfree (%ld) difere do bitmap (%ld)", gf0, free0);
    verify_contents(&exp, "antes");

    /* ---- gravações como o PS2 faria ---- */
    printf("-- gravando: apaga 1 arquivo, cria pasta + arquivo de 3 MiB, cria .cfg, estende um .cfg, regrava trecho do VMC\n");
    Ent *art = NULL;
    for (int i = 0; i < exp.n; i++)
        if (!strncmp(exp.v[i].rel, "/ART/", 5) && !exp.v[i].dir && exp.v[i].size > 0) { art = &exp.v[i]; break; }
    char p[4096];
    if (art) {
        snprintf(p, sizeof p, "0:%s", art->rel);
        FRESULT r = f_unlink(p);
        if (r != FR_OK) fail("f_unlink(%s) = %d", p, r);
        printf("  f_unlink(%s) = %d\n", p, r);
    }
    FRESULT r = f_mkdir("0:/PS2PONTE");
    printf("  f_mkdir(0:/PS2PONTE) = %d\n", r);
    if (r != FR_OK) fail("f_mkdir = %d", r);
    uint64_t nb = 3 * 1048576 + 123;
    write_file("0:/PS2PONTE/novo.bin", 42, 0, nb, FA_CREATE_ALWAYS | FA_WRITE);
    write_file("0:/CFG/novo.cfg", 43, 0, 1500, FA_CREATE_ALWAYS | FA_WRITE);
    Ent *cfg = find(&exp, "/CFG/conf_opl.cfg");
    Ent *vmc = find(&exp, "/VMC/slot0.bin");
    if (cfg) write_file("0:/CFG/conf_opl.cfg", 44, cfg->size, 200000, FA_OPEN_APPEND | FA_WRITE);
    if (vmc) write_file("0:/VMC/slot0.bin", 45, 65536, 8192, FA_OPEN_EXISTING | FA_WRITE);
    f_unmount("0:");
    printf("  gravações no disco: %llu comandos, %llu setores\n", g_writes, g_write_sectors);

    /* Resultado esperado. */
    if (art) { free(art->rel); art->rel = strdup("/ART/\x01apagado"); }
    List exp2 = { 0 };
    for (int i = 0; i < exp.n; i++) if (strcmp(exp.v[i].rel, "/ART/\x01apagado")) list_add(&exp2, exp.v[i]);
    Ent d = { "/PS2PONTE", NULL, 0, 1, 0, 0, 0 };
    Ent n1 = { "/PS2PONTE/novo.bin", NULL, nb, 0, 42, 0, nb };
    Ent n2 = { "/CFG/novo.cfg", NULL, 1500, 0, 43, 0, 1500 };
    list_add(&exp2, d); list_add(&exp2, n1); list_add(&exp2, n2);
    Ent *c2 = find(&exp2, "/CFG/conf_opl.cfg");
    if (c2) { c2->pseed = 44; c2->poff = c2->size; c2->plen = 200000; c2->size += 200000; }
    Ent *v2 = find(&exp2, "/VMC/slot0.bin");
    if (v2) { v2->pseed = 45; v2->poff = 65536; v2->plen = 8192; }

    printf("-- remontando\n");
    if (!mount()) return 1;
    check_boot_and_upcase(0);
    List got2 = { 0 };
    walk_fat(&got2, "");
    diff = compare_trees(&exp2, &got2);
    printf("  listagem depois das gravações: %d entradas, %d diferenças\n", got2.n, diff);
    long free1 = check_allocation("depois", 0);
    long gf1 = getfree();
    if (gf1 != free1) fail("f_getfree (%ld) difere do bitmap (%ld)", gf1, free1);
    long csz = g_fs.csize * 512L;
    long used = (long)((nb + csz - 1) / csz) + 1 /* pasta nova */;
    printf("  clusters usados pelas gravações: %ld (esperado ~%ld + extensão do .cfg - arquivo apagado)\n", gf0 - gf1, used);
    verify_contents(&exp2, "depois");
    f_unmount("0:");
    raw_close();

    printf("leituras do FatFs: %llu comandos, %llu setores\n", g_reads, g_read_sectors);
    if (g_fail) printf("RESULTADO: FALHOU (%d problemas)\n", g_fail);
    else printf("RESULTADO: OK\n");
    return g_fail ? 1 : 0;
}

/* ------------------------------------------------------------------ vetores */

static void name_vec(const char *utf8)
{
    WCHAR w[300];
    utf8_to_utf16(utf8, w, 299);
    printf("nameHash \"%s\" = 0x%04X\n", utf8, xname_sum(w));
}

static int run_vectors(void)
{
    const char *names[] = { "PS2", "DVD", "conf_opl.cfg", "SLUS_200.62.Jogo de Teste.iso", "Ação", "Coleção é ç",
                            "ÿµı", "aBc", "ABC", NULL };
    for (int i = 0; names[i]; i++) name_vec(names[i]);
    BYTE set[96];
    for (int i = 0; i < 96; i++) set[i] = (BYTE)(i * 7 + 3);
    set[XDIR_NumSec] = 2;
    printf("setChecksum(padrao i*7+3, NumSec=2, 96 bytes) = 0x%04X\n", xdir_sum(set));
    static BYTE boot[11 * 512];
    for (int i = 0; i < 11 * 512; i++) boot[i] = (BYTE)(i * 13 + 5);
    DWORD sum = 0;
    for (int i = 0; i < 11 * 512; i++)
        if (i != BPB_VolFlagEx && i != BPB_VolFlagEx + 1 && i != BPB_PercInUseEx) {
#if FF_USE_MKFS
            sum = xsum32(boot[i], sum); /* a função do f_mkfs */
#else
            sum = sum32(sum, boot[i]);
#endif
        }
    printf("bootChecksum(padrao i*13+5) = 0x%08X\n", sum);
    DWORD samples[] = { 'a', 'z', 0xE7, 0xE9, 0xFF, 0xB5, 0x131, 0x3C2, 0x1F80, 0xFF41, 0x10428, 0 };
    for (int i = 0; samples[i]; i++) printf("ff_wtoupper(U+%04X) = U+%04X\n", samples[i], ff_wtoupper(samples[i]));
    return 0;
}

#if FF_USE_MKFS
static int run_mkfs_ref(const char *img)
{
    crc_init();
    FILE *f = fopen(img, "wb");
    if (!f || fseeko(f, 131072LL * 512 - 1, SEEK_SET) != 0 || fputc(0, f) == EOF) return 1;
    fclose(f);
    if (!raw_open(img)) return 1;
    g_offset = 0; g_count = 131072; /* 64 MiB */
    static BYTE work[65536];
    MKFS_PARM opt = { FM_EXFAT | FM_SFD, 0, 0, 0, 0 };
    FRESULT r = f_mkfs("0:", &opt, work, sizeof work);
    printf("f_mkfs = %d\n", r);
    if (r != FR_OK || !mount()) return 1;
    check_boot_and_upcase(1);
    f_unmount("0:");
    raw_close();
    return g_fail ? 1 : 0;
}
#endif

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc >= 2 && !strcmp(argv[1], "--vectors")) return run_vectors();
#if FF_USE_MKFS
    if (argc >= 3 && !strcmp(argv[1], "--mkfs-ref")) return run_mkfs_ref(argv[2]);
#endif
    if (argc < 3) {
        printf("uso: %s <disco.img> <pasta-origem> | --vectors | --mkfs-ref <novo.img>\n", argv[0]);
        return 2;
    }
    return run_image(argv[1], argv[2], FF_FS_MBR);
}
