#!/bin/sh
# Compila o harness do exFAT virtual com o FatFs que o PS2 usa (bdmfs_fatfs do ps2sdk).
#
# Uso (sh do msys2/cygwin ou Linux):
#   git clone --depth 1 -b iop-r0.16 https://github.com/fjtrujy/FatFs.git <fatfs>   # o que o ps2sdk baixa
#   scripts/fatfs-harness/build.sh <fatfs> <saída>
#
# Gera dois executáveis:
#   <saída>/harness_ps2   ffconf.h do ps2sdk sem mudanças (FF_FS_MBR=0, página 869, FF_FS_TINY...);
#                         o harness acha a partição na MBR como o driver de partição do BDM.
#   <saída>/harness_mbr   mesma coisa com FF_FS_MBR=1 (o FatFs acha a partição sozinho), nomes em
#                         UTF-8 (compara acentos) e f_mkfs (tabela up-case de referência).
#
# Depois (imagem gerada por ExFatDevMain; o teste grava nela, então gere uma por execução):
#   <saída>/harness_ps2 disco.img <pasta-origem>
#   <saída>/harness_mbr disco.img <pasta-origem>
#   <saída>/harness_ps2 tcp:7788 <pasta-origem>      (disco ao vivo: ExFatDevMain "servir <pasta> 7788";
#                                                    a gravação no VMC vai para o arquivo real da pasta)
#   <saída>/harness_mbr --vectors
#   <saída>/harness_mbr --mkfs-ref ref.img
set -e
FATFS=${1:?pasta do FatFs}
OUT=${2:?pasta de saída}
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$FATFS/source"
CC=${CC:-gcc}

mkdir -p "$OUT/ps2" "$OUT/mbr"
cp "$SRC/include/ff.h" "$SRC/include/diskio.h" "$SRC/include/ffconf.h" "$OUT/ps2/"
cp "$SRC/include/ff.h" "$SRC/include/diskio.h" "$OUT/mbr/"
sed -E \
    -e 's/^#define[[:space:]]+FF_FS_MBR[[:space:]]+0/#define FF_FS_MBR 1/' \
    -e 's/^#define[[:space:]]+FF_LFN_UNICODE[[:space:]]+0/#define FF_LFN_UNICODE 2/' \
    -e 's/^#define[[:space:]]+FF_USE_MKFS[[:space:]]+0/#define FF_USE_MKFS 1/' \
    "$SRC/include/ffconf.h" > "$OUT/mbr/ffconf.h"
for opt in "FF_FS_MBR 1" "FF_LFN_UNICODE 2" "FF_USE_MKFS 1"; do
    grep -q "^#define $opt" "$OUT/mbr/ffconf.h" || { echo "ffconf.h: não consegui ligar $opt"; exit 1; }
done

for v in ps2 mbr; do
    "$CC" -O2 -std=gnu11 -Wall -Wno-unused-function -Wno-unused-variable \
        -I"$OUT/$v" -I"$HERE/include" -I"$SRC" \
        -o "$OUT/harness_$v" "$HERE/harness.c" "$HERE/host_glue.c" "$HERE/host_walk.c" "$SRC/ffunicode.c"
    echo "ok: $OUT/harness_$v"
done
