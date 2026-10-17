#!/usr/bin/env python3
"""
Cliente de teste que imita iop/udpbd/src/udpbd.c do Neutrino (UDPBD v2 clássico, porta 0xBDBD).

    python ps2_udpbd_cliente.py --imagem <dump do disco> [--host 127.0.0.1]

Lê o disco inteiro (ou os primeiros --setores) em pedidos de até 512 setores, como o PS2,
conferindo a ordem dos pacotes RDMA e o conteúdo com a imagem.
"""
import argparse
import random
import socket
import struct
import sys
import time

CMD_INFO, CMD_INFO_REPLY, CMD_READ, CMD_READ_RDMA = 0, 1, 2, 3
RDMA_MAX_PAYLOAD = 1466


def hdr(cmd, cmdid, cmdpkt):
    return struct.pack('<H', (cmd & 0x1F) | ((cmdid & 7) << 5) | ((cmdpkt & 0xFF) << 8))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--porta', type=lambda x: int(x, 0), default=0xBDBD)
    ap.add_argument('--imagem', required=True)
    ap.add_argument('--setores', type=int, default=0, help='0 = disco todo da imagem')
    a = ap.parse_args()

    img = open(a.imagem, 'rb')
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if hasattr(socket, 'SIO_UDP_CONNRESET'):
        s.ioctl(socket.SIO_UDP_CONNRESET, False)
    # O servidor manda até 183 pacotes em rajada (sem controle de fluxo, como o original): buffer grande
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)
    s.bind(('0.0.0.0', 0))
    s.settimeout(2)
    srv = (a.host, a.porta)

    s.sendto(hdr(CMD_INFO, 0, 0), srv)
    r, _ = s.recvfrom(2048)
    h = struct.unpack('<H', r[:2])[0]
    assert h & 0x1F == CMD_INFO_REPLY, 'sem INFO_REPLY'
    sector_size, sector_count = struct.unpack('<II', r[2:10])
    print(f'INFO: setor {sector_size} B, {sector_count} setores ({sector_count * 512 / 2**30:.2f} GiB)')

    total = min(a.setores or sector_count, sector_count)
    cmdid = 0
    lidos = 0
    t0 = time.time()

    def ler(lba, count):
        nonlocal cmdid
        cmdid = (cmdid + 1) & 7
        s.sendto(hdr(CMD_READ, cmdid, 0) + struct.pack('<IH', lba, count), srv)
        out = bytearray()
        expect = 1
        while len(out) < count * 512:
            r, _ = s.recvfrom(2048)
            h = struct.unpack('<H', r[:2])[0]
            if (h >> 5) & 7 != cmdid:
                continue
            assert h & 0x1F == CMD_READ_RDMA
            assert (h >> 8) & 0xFF == expect & 0xFF, f'ordem do cmdpkt em {lba}'
            expect += 1
            bt = struct.unpack('<I', r[2:6])[0]
            size = ((bt >> 4) & 0x1FF) << ((bt & 0xF) + 2)
            assert size <= RDMA_MAX_PAYLOAD and size == len(r) - 6
            out += r[6:6 + size]
        return bytes(out)

    lba = 0
    while lba < total:
        n = min(512, total - lba)
        got = ler(lba, n)
        img.seek(lba * 512)
        if got != img.read(n * 512):
            print(f'FALHOU: conteúdo diferente em {lba}+{n}')
            sys.exit(1)
        lidos += n
        lba += n
    rnd = random.Random(7)
    for _ in range(200):  # leituras aleatórias pequenas, como o FatFs faz
        l = rnd.randrange(0, sector_count - 8)
        n = rnd.randint(1, 8)
        got = ler(l, n)
        img.seek(l * 512)
        if got != img.read(n * 512):
            print(f'FALHOU: leitura aleatória em {l}+{n}')
            sys.exit(1)
    dt = time.time() - t0
    print(f'OK: {lidos} setores em sequência + 200 leituras aleatórias conferidos ({lidos * 512 / 2**20 / dt:.1f} MiB/s no loopback)')


if __name__ == '__main__':
    main()
