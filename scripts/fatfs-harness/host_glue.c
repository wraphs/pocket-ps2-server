/*
 * Camada de disco do FatFs no PC. O "dispositivo de bloco" é um arquivo .img ou, com "tcp:PORTA",
 * o VirtualExFat ao vivo servido pelo ExFatDevMain ("servir"), para exercitar as gravações de verdade.
 * Como o driver de partição do BDM (ps2sdk iop/fs/bdm/src/part_driver_mbr.c), o FatFs pode
 * enxergar só a partição: g_offset é somado a todo LBA e g_count limita o tamanho.
 * Substitui também o que é do PS2 em diskio.c/ffsystem.c (relógio do CDVD e semáforos).
 */
#define _FILE_OFFSET_BITS 64
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "ff.h"
#include "diskio.h"

static FILE *g_img;
static int g_sock = -1;
uint64_t g_offset; /* setor inicial do dispositivo visto pelo FatFs */
uint64_t g_count;  /* setores do dispositivo visto pelo FatFs */
unsigned long long g_reads, g_writes, g_read_sectors, g_write_sectors;

/* ---- acesso cru ao disco inteiro (imagem ou TCP) ---- */

static int send_all(const void *b, size_t n)
{
    const char *p = b;
    while (n) {
        ssize_t k = send(g_sock, p, n, 0);
        if (k <= 0) return 0;
        p += k; n -= (size_t)k;
    }
    return 1;
}

static int recv_all(void *b, size_t n)
{
    char *p = b;
    while (n) {
        ssize_t k = recv(g_sock, p, n, 0);
        if (k <= 0) return 0;
        p += k; n -= (size_t)k;
    }
    return 1;
}

static void put_le(uint8_t *b, uint64_t v, int n) { for (int i = 0; i < n; i++) b[i] = (uint8_t)(v >> (8 * i)); }

static int tcp_cmd(char op, uint64_t lba, uint32_t count, const void *out, void *in)
{
    uint8_t h[13], st;
    h[0] = (uint8_t)op;
    put_le(h + 1, lba, 8);
    put_le(h + 9, count, 4);
    if (!send_all(h, 13)) return 0;
    if (op == 'W' && !send_all(out, (size_t)count * 512)) return 0;
    if (!recv_all(&st, 1) || st != 0) return 0;
    if (op == 'R' && !recv_all(in, (size_t)count * 512)) return 0;
    if (op == 'S' && !recv_all(in, 8)) return 0;
    return 1;
}

int raw_open(const char *spec)
{
    if (!strncmp(spec, "tcp:", 4)) {
        struct sockaddr_in a;
        int one = 1;
        g_sock = socket(AF_INET, SOCK_STREAM, 0);
        memset(&a, 0, sizeof a);
        a.sin_family = AF_INET;
        a.sin_port = htons((uint16_t)atoi(spec + 4));
        a.sin_addr.s_addr = htonl(0x7F000001);
        if (connect(g_sock, (struct sockaddr *)&a, sizeof a) != 0) { g_sock = -1; return 0; }
        setsockopt(g_sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
        return 1;
    }
    g_img = fopen(spec, "r+b");
    return g_img != NULL;
}

uint64_t raw_sectors(void)
{
    if (g_sock >= 0) {
        uint8_t b[8];
        if (!tcp_cmd('S', 0, 0, NULL, b)) return 0;
        uint64_t v = 0;
        for (int i = 7; i >= 0; i--) v = v << 8 | b[i];
        return v;
    }
    fseeko(g_img, 0, SEEK_END);
    return (uint64_t)ftello(g_img) / 512;
}

int raw_read(uint64_t lba, uint32_t count, void *buf)
{
    if (g_sock >= 0) return tcp_cmd('R', lba, count, NULL, buf);
    if (fseeko(g_img, (off_t)(lba * 512), SEEK_SET) != 0) return 0;
    return fread(buf, 512, count, g_img) == count;
}

int raw_write(uint64_t lba, uint32_t count, const void *buf)
{
    if (g_sock >= 0) return tcp_cmd('W', lba, count, buf, NULL);
    if (fseeko(g_img, (off_t)(lba * 512), SEEK_SET) != 0) return 0;
    return fwrite(buf, 512, count, g_img) == count;
}

void raw_close(void)
{
    if (g_sock >= 0) {
        tcp_cmd('Q', 0, 0, NULL, NULL);
        close(g_sock);
        g_sock = -1;
    }
    if (g_img) { fclose(g_img); g_img = NULL; }
}

/* ---- interface do FatFs ---- */

DSTATUS disk_status(BYTE pdrv) { return (pdrv == 0 && (g_img || g_sock >= 0)) ? 0 : (STA_NOINIT | STA_NODISK); }
DSTATUS disk_initialize(BYTE pdrv) { return disk_status(pdrv); }

DRESULT disk_read(BYTE pdrv, BYTE *buff, LBA_t sector, UINT count)
{
    if (disk_status(pdrv)) return RES_NOTRDY;
    if (sector + count > g_count) {
        fprintf(stderr, "disk_read fora do dispositivo: %llu+%u\n", (unsigned long long)sector, count);
        return RES_PARERR;
    }
    if (!raw_read(g_offset + sector, count, buff)) return RES_ERROR;
    g_reads++; g_read_sectors += count;
    return RES_OK;
}

DRESULT disk_write(BYTE pdrv, const BYTE *buff, LBA_t sector, UINT count)
{
    if (disk_status(pdrv)) return RES_NOTRDY;
    if (sector + count > g_count) {
        fprintf(stderr, "disk_write fora do dispositivo: %llu+%u\n", (unsigned long long)sector, count);
        return RES_PARERR;
    }
    if (!raw_write(g_offset + sector, count, buff)) return RES_ERROR;
    g_writes++; g_write_sectors += count;
    return RES_OK;
}

DRESULT disk_ioctl(BYTE pdrv, BYTE cmd, void *buff)
{
    if (disk_status(pdrv)) return RES_NOTRDY;
    switch (cmd) {
    case CTRL_SYNC: if (g_img) fflush(g_img); return RES_OK;
    case GET_SECTOR_COUNT: *(LBA_t *)buff = (LBA_t)g_count; return RES_OK;
    case GET_SECTOR_SIZE: *(WORD *)buff = 512; return RES_OK;
    case GET_BLOCK_SIZE: *(DWORD *)buff = 1; return RES_OK;
    }
    return RES_PARERR;
}

DWORD get_fattime(void)
{
    /* 16/10/2026 12:00:00 fixo, para o teste ser repetível */
    return ((DWORD)(2026 - 1980) << 25) | ((DWORD)10 << 21) | ((DWORD)16 << 16) | ((DWORD)12 << 11);
}

#if FF_FS_REENTRANT
/* O teste roda numa thread só: semáforos vazios (no PS2 são CreateSema/WaitSema). */
int ff_mutex_create(int vol) { (void)vol; return 1; }
void ff_mutex_delete(int vol) { (void)vol; }
int ff_mutex_take(int vol) { (void)vol; return 1; }
void ff_mutex_give(int vol) { (void)vol; }
#endif
