/* Substituto de iop/fs/bdmfs_fatfs/src/include/module_debug.h para compilar o FatFs no PC. */
#ifndef _MODULE_DEBUG_H
#define _MODULE_DEBUG_H

#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef uint8_t u8;
typedef uint16_t u16;
typedef uint32_t u32;
typedef uint64_t u64;

#define M_PRINTF(format, args...) printf("FatFs: " format, ##args)

#ifdef DEBUG
#define M_DEBUG M_PRINTF
#else
#define M_DEBUG(format, args...)
#endif

#define U64_2XU32(val) \
    u32 val##_u32[2];  \
    memcpy(val##_u32, &val, sizeof(val##_u32))

#ifdef DEBUG
#define DEBUG_U64_2XU32(val) U64_2XU32(val)
#else
#define DEBUG_U64_2XU32(val) do { } while (0)
#endif

#endif
