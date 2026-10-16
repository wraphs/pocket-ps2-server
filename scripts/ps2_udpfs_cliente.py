#!/usr/bin/env python3
"""
Cliente de teste que imita o lado PS2 do UDPFS/UDPRDMA (iop/udpfs/src/udprdma.c + udpfs_core.c do Neutrino).

Serve para conferir um servidor (o de referência `udpfs_server.py` ou o do app) sem PS2:
    python ps2_udpfs_cliente.py --porta 62966 --pasta <pasta servida> [--imagem <img do handle 0>] [--perda 0.03]

--perda descarta aleatoriamente pacotes de dados recebidos, para exercitar NACK/retransmissão.
"""
import argparse
import hashlib
import os
import random
import socket
import struct
import sys
import time

PT_DISCOVERY, PT_INFORM, PT_DATA = 0, 1, 2
DF_ACK, DF_FIN = 1, 2
SVC = 0xF5F5
RX_ACK_WINDOW = 6

OPEN_REQ, OPEN_REPLY, CLOSE_REQ, CLOSE_REPLY = 0x10, 0x11, 0x12, 0x13
READ_REQ, WRITE_REQ, WRITE_DATA, WRITE_DONE = 0x14, 0x16, 0x17, 0x18
LSEEK_REQ, LSEEK_REPLY, DREAD_REQ, DREAD_REPLY = 0x1A, 0x1B, 0x1C, 0x1D
GETSTAT_REQ, GETSTAT_REPLY, MKDIR_REQ, REMOVE_REQ, RMDIR_REQ, RESULT_REPLY = 0x1E, 0x1F, 0x20, 0x22, 0x24, 0x26
BREAD_REQ, BWRITE_REQ = 0x28, 0x2A
O_RDONLY, O_WRONLY, O_RDWR, O_APPEND, O_CREAT, O_TRUNC = 1, 2, 3, 0x100, 0x200, 0x400


def base_hdr(t, seq):
    return struct.pack('<H', (t & 0xF) | ((seq & 0xFFF) << 4))


def data_hdr(ack, flags, hwc, dbc):
    return struct.pack('<I', (ack & 0xFFF) | ((flags & 3) << 12) | ((hwc & 0xF) << 14) | ((dbc & 0x3FFF) << 18))


class Udprdma:
    def __init__(self, host, port, perda=0.0):
        self.s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if hasattr(socket, 'SIO_UDP_CONNRESET'):  # Windows: não estourar ICMP "porta inalcançável"
            self.s.ioctl(socket.SIO_UDP_CONNRESET, False)
        self.s.bind(('0.0.0.0', 0))
        self.host, self.disc_port = host, port
        self.peer = None
        self.tx_seq = 0
        self.tx_acked = 0
        self.rx_expected = 0
        self.perda = perda
        self.rng = random.Random(1234)
        self.descartados = 0
        self.nacks = 0
        # estado de recepção (equivalente ao rx_buffer do PS2)
        self.rx_on = False
        self.rx_hdr = b''
        self.rx_data = bytearray()
        self.rx_fin = False
        self.rx_win = 0
        self.rx_nack_sent = False
        self.got_ack = False

    def discover(self, timeout=2.0):
        pkt = base_hdr(PT_DISCOVERY, 0) + struct.pack('<HH', SVC, 0)
        for _ in range(4):
            self.s.sendto(pkt, (self.host, self.disc_port))
            self.s.settimeout(timeout / 4)
            try:
                while True:
                    data, addr = self.s.recvfrom(2048)
                    h = struct.unpack('<H', data[:2])[0]
                    if h & 0xF == PT_INFORM and struct.unpack('<H', data[2:4])[0] == SVC:
                        self.peer = addr  # porta = porta de origem do INFORM
                        return
            except socket.timeout:
                continue
        raise RuntimeError('sem INFORM do servidor')

    def _ack(self, is_ack):
        a = (self.rx_expected - 1) & 0xFFF if is_ack else self.rx_expected
        self.s.sendto(base_hdr(PT_DATA, self.tx_seq) + data_hdr(a, DF_ACK if is_ack else 0, 0, 0), self.peer)

    def _process(self, data):
        """Igual ao _ist() do PS2 para pacotes DATA."""
        if len(data) < 6:
            return
        h = struct.unpack('<H', data[:2])[0]
        if h & 0xF != PT_DATA:
            return
        seq = (h >> 4) & 0xFFF
        dh = struct.unpack('<I', data[2:6])[0]
        ack, flags, hwc, dbc = dh & 0xFFF, (dh >> 12) & 3, (dh >> 14) & 0xF, (dh >> 18) & 0x3FFF
        if flags & DF_ACK:
            self.tx_acked = ack
            self.got_ack = True
        hdr_size = hwc * 4
        if hdr_size + dbc == 0 or not self.rx_on:
            return
        if self.perda and self.rng.random() < self.perda:
            self.descartados += 1
            return
        if seq == self.rx_expected:
            if hdr_size and not self.rx_hdr:
                self.rx_hdr = data[6:6 + hdr_size]
            self.rx_data += data[6 + hdr_size:6 + hdr_size + dbc]
            self.rx_expected = (seq + 1) & 0xFFF
            self.rx_nack_sent = False
            if flags & DF_FIN:
                self.rx_fin = True
            else:
                self.rx_win += 1
                if self.rx_win >= RX_ACK_WINDOW:
                    self.rx_win = 0
                    self._ack(True)
        else:
            if not self.rx_nack_sent:
                self.rx_nack_sent = True
                self.nacks += 1
                self._ack(False)

    def _pump(self, timeout):
        self.s.settimeout(timeout)
        try:
            data, _ = self.s.recvfrom(4096)
        except socket.timeout:
            return False
        self._process(data)
        return True

    def transact(self, app_hdr, data=b'', timeout=5.0):
        """udprdma_set_rx_buffer + udprdma_send_ll + udprdma_recv. Devolve (cabeçalho, dados)."""
        self.rx_on, self.rx_hdr, self.rx_data, self.rx_fin, self.rx_win = True, b'', bytearray(), False, 0
        pad = (len(data) + 3) & ~3
        for _ in range(4):
            sent = self.tx_seq
            pkt = base_hdr(PT_DATA, self.tx_seq) + data_hdr((self.rx_expected - 1) & 0xFFF, DF_ACK | DF_FIN, len(app_hdr) // 4, pad) + app_hdr + data.ljust(pad, b'\0')
            self.tx_seq = (self.tx_seq + 1) & 0xFFF
            self.got_ack = False
            self.s.sendto(pkt, self.peer)
            t0 = time.time()
            while not self.got_ack and time.time() - t0 < 0.5:
                self._pump(0.05)
            if self.got_ack:
                break
            self.tx_seq = sent
        else:
            raise RuntimeError('send: sem ACK')
        t0 = time.time()
        while not self.rx_fin:
            if time.time() - t0 > timeout:
                raise RuntimeError('recv: timeout')
            self._pump(0.1)
        self._ack(True)
        self.rx_on = False
        return bytes(self.rx_hdr), bytes(self.rx_data)


class Udpfs:
    def __init__(self, rdma):
        self.r = rdma

    @staticmethod
    def _path(p):
        b = p.encode('utf-8') + b'\0'
        return b.ljust((len(b) + 3) & ~3, b'\0')

    def _simple(self, msg):
        hdr, data = self.r.transact(msg)
        return data

    def open(self, path, flags=O_RDONLY, is_dir=False):
        d = self._simple(struct.pack('<BBHi', OPEN_REQ, 1 if is_dir else 0, flags, 0o644) + self._path(path))
        assert d[0] == OPEN_REPLY, d[:4]
        handle, mode, size, hisize = struct.unpack('<iIII', d[4:20])
        return handle, mode, size | (hisize << 32)

    def close(self, h):
        d = self._simple(struct.pack('<BBBBi', CLOSE_REQ, 0, 0, 0, h))
        assert d[0] == CLOSE_REPLY
        return struct.unpack('<i', d[4:8])[0]

    def read(self, h, size):
        hdr, data = self.r.transact(struct.pack('<BBBBiI', READ_REQ, 0, 0, 0, h, size))
        assert hdr[0] == RESULT_REPLY, hdr
        n = struct.unpack('<i', hdr[4:8])[0]
        return n, data[:max(n, 0)]

    def lseek(self, h, off, whence=0):
        d = self._simple(struct.pack('<BBHiIi', LSEEK_REQ, whence, 0, h, off & 0xFFFFFFFF, off >> 32))
        assert d[0] == LSEEK_REPLY
        lo, hi = struct.unpack('<II', d[4:12])
        return lo | (hi << 32)

    def dread(self, h):
        d = self._simple(struct.pack('<BBBBi', DREAD_REQ, 0, 0, 0, h))
        assert d[0] == DREAD_REPLY
        name_len = struct.unpack('<H', d[2:4])[0]
        result, mode, attr, size, hisize = struct.unpack('<iIIII', d[4:24])
        name = d[48:48 + name_len].decode('utf-8') if result > 0 else None
        return result, name, mode, size | (hisize << 32)

    def getstat(self, path):
        msg = struct.pack('<BBBB', GETSTAT_REQ, 0, 0, 0) + (self._path(path) if path else b'')
        d = self._simple(msg)
        assert d[0] == GETSTAT_REPLY
        result, mode, attr, size, hisize = struct.unpack('<iIIII', d[4:24])
        return result, mode, size | (hisize << 32)

    def write(self, h, data):
        chunks = [data[i:i + 1392] for i in range(0, len(data), 1392)] or [b'']
        total = len(chunks)
        req = struct.pack('<BBBBiI', WRITE_REQ, 0, 0, 0, h, len(data))
        return self._chunks(req, chunks, total)

    def bwrite(self, h, sector, data):
        chunks = [data[i:i + 1392] for i in range(0, len(data), 1392)]
        req = struct.pack('<BBHiII', BWRITE_REQ, 0, len(data) // 512, h, sector & 0xFFFFFFFF, sector >> 32)
        return self._chunks(req, chunks, len(chunks))

    def _chunks(self, req, chunks, total):
        # 1º pacote: requisição + WRITE_DATA como cabeçalho de aplicação, chunk 0 como dados
        first = req + struct.pack('<BBHHH', WRITE_DATA, 0, 0, len(chunks[0]), total)
        if total == 1:
            hdr, d = self.r.transact(first, chunks[0])
        else:
            self._send_only(first, chunks[0])
            for i in range(1, total):
                wd = struct.pack('<BBHHH', WRITE_DATA, 0, i, len(chunks[i]), total)
                if i < total - 1:
                    self._send_only(wd, chunks[i])
                else:
                    hdr, d = self.r.transact(wd, chunks[i])
        d = d if d else hdr
        assert d[0] == WRITE_DONE, d[:8]
        return struct.unpack('<i', d[4:8])[0]

    def _send_only(self, app_hdr, data):
        r = self.r
        pad = (len(data) + 3) & ~3
        for _ in range(4):
            sent = r.tx_seq
            pkt = base_hdr(PT_DATA, r.tx_seq) + data_hdr((r.rx_expected - 1) & 0xFFF, DF_ACK | DF_FIN, len(app_hdr) // 4, pad) + app_hdr + data.ljust(pad, b'\0')
            r.tx_seq = (r.tx_seq + 1) & 0xFFF
            r.got_ack = False
            r.s.sendto(pkt, r.peer)
            t0 = time.time()
            while not r.got_ack and time.time() - t0 < 0.5:
                r._pump(0.05)
            if r.got_ack:
                return
            r.tx_seq = sent
        raise RuntimeError('send: sem ACK')

    def bread(self, h, sector, count):
        hdr, data = self.r.transact(struct.pack('<BBHiII', BREAD_REQ, 0, count, h, sector & 0xFFFFFFFF, sector >> 32))
        assert hdr[0] == RESULT_REPLY, hdr
        n = struct.unpack('<i', hdr[4:8])[0]
        return n, data[:max(n, 0)]

    def simple_path(self, msg_type, path):
        d = self._simple(struct.pack('<BBH', msg_type, 0, 0o755) + self._path(path))
        assert d[0] == RESULT_REPLY
        return struct.unpack('<i', d[4:8])[0]


def md5(b):
    return hashlib.md5(b).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--porta', type=lambda x: int(x, 0), default=0xF5F6)
    ap.add_argument('--pasta', required=True)
    ap.add_argument('--imagem')
    ap.add_argument('--perda', type=float, default=0.0)
    a = ap.parse_args()

    r = Udprdma(a.host, a.porta, a.perda)
    r.discover()
    fs = Udpfs(r)
    ok = 0

    def check(cond, msg):
        nonlocal ok
        if not cond:
            print('FALHOU:', msg)
            sys.exit(1)
        ok += 1

    if a.imagem:
        img = open(a.imagem, 'rb').read()
        res, mode, size = fs.getstat('')
        check(res == 0 and size == len(img) // 512 * 512, f'getstat bloco: {res} {size}')
        for sec, cnt in [(0, 1), (3, 7), (100, 128), (len(img) // 512 - 5, 5)]:
            n, data = fs.bread(0, sec, cnt)
            check(n == cnt * 512 and data == img[sec * 512:(sec + cnt) * 512], f'bread {sec}+{cnt}')
        new = os.urandom(1024)
        check(fs.bwrite(0, 10, new) == 0, 'bwrite')
        n, data = fs.bread(0, 10, 2)
        check(data == new, 'bread após bwrite')

    # listagem da raiz
    h, mode, _ = fs.open('/', is_dir=True)
    check(h > 0, f'dopen {h}')
    nomes = []
    while True:
        res, name, mode, size = fs.dread(h)
        if res <= 0:
            break
        nomes.append(name)
    check(fs.close(h) == 0, 'dclose')
    check(sorted(nomes) == sorted(os.listdir(a.pasta)), f'listagem {sorted(nomes)} != {sorted(os.listdir(a.pasta))}')

    # leitura de arquivo grande em blocos de 64 KiB
    alvo = os.path.join(a.pasta, 'DVD', 'teste.iso')
    local = open(alvo, 'rb').read()
    h, mode, size = fs.open('DVD/teste.iso')
    check(h > 0 and size == len(local), f'open {h} {size}')
    got = bytearray()
    while True:
        n, data = fs.read(h, 65536)
        if n <= 0:
            break
        got += data
    check(md5(got) == md5(local), 'conteúdo lido')
    check(fs.lseek(h, 12345) == 12345, 'lseek')
    n, data = fs.read(h, 1000)
    check(data == local[12345:13345], 'read após lseek')
    check(fs.lseek(h, 0, 2) == len(local), 'lseek END')
    check(fs.close(h) == 0, 'close')

    # BREAD num handle de arquivo (udpfs_fhi)
    h, _, _ = fs.open('DVD/teste.iso')
    n, data = fs.bread(h, 4, 16)
    check(data == local[4 * 512:20 * 512], 'bread em arquivo')
    fs.close(h)

    # escrita
    payload = os.urandom(5000)
    h, _, _ = fs.open('CFG/novo.cfg', O_WRONLY | O_CREAT | O_TRUNC)
    check(h > 0, 'open escrita')
    check(fs.write(h, payload) == len(payload), 'write')
    fs.close(h)
    check(open(os.path.join(a.pasta, 'CFG', 'novo.cfg'), 'rb').read() == payload, 'arquivo gravado')
    res, mode, size = fs.getstat('CFG/novo.cfg')
    check(res == 0 and size == 5000, 'getstat arquivo')
    check(fs.simple_path(REMOVE_REQ, 'CFG/novo.cfg') == 0, 'remove')
    check(fs.getstat('CFG/novo.cfg')[0] < 0, 'getstat após remove')
    check(fs.simple_path(MKDIR_REQ, 'TMPDIR') == 0, 'mkdir')
    check(fs.simple_path(RMDIR_REQ, 'TMPDIR') == 0, 'rmdir')
    check(fs.open('nao/existe.iso')[0] < 0, 'open inexistente')

    print(f'OK: {ok} verificações; pacotes descartados={r.descartados}, NACKs={r.nacks}')


if __name__ == '__main__':
    main()
