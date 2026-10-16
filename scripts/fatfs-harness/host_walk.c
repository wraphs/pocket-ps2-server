/* Lista uma pasta do PC (separado porque o DIR do POSIX conflita com o DIR do FatFs). */
#define _FILE_OFFSET_BITS 64
#include <dirent.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

typedef void (*host_cb)(void *ctx, const char *name, const char *path, int isdir, uint64_t size);

/* Chama cb para cada item de dir (sem . e ..); devolve -1 se não abriu. */
int host_list(const char *dir, host_cb cb, void *ctx)
{
    DIR *d = opendir(dir);
    struct dirent *de;
    if (!d) return -1;
    while ((de = readdir(d)) != NULL) {
        char p[4096];
        struct stat st;
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
        snprintf(p, sizeof p, "%s/%s", dir, de->d_name);
        if (stat(p, &st) != 0) {
            cb(ctx, de->d_name, p, -1, 0);
            continue;
        }
        cb(ctx, de->d_name, p, S_ISDIR(st.st_mode) ? 1 : 0, S_ISDIR(st.st_mode) ? 0 : (uint64_t)st.st_size);
    }
    closedir(d);
    return 0;
}
