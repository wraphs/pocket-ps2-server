#!/usr/bin/env python3
"""Teste de interoperabilidade do servidor SMB1 do PS2 Ponte com um cliente SMB1 genérico.

Usa o impacket (SMBConnection com preferredDialect=SMB_DIALECT). Se o impacket não estiver
instalado por inteiro (ex.: o Windows Defender coloca o impacket/__init__.py em quarentena),
usa o pysmb em modo SMB1 e faz os mesmos passos.

Uso (com o servidor no ar, ex.: .\\g.ps1 :core:runMain "-PmainClass=br.ps2ponte.core.smb.SmbDevMainKt" "--args=C:\\pasta 1445"):
    python scripts/teste_smb_impacket.py --pasta C:\\pasta --porta 1445 [--cliente impacket|pysmb]

A pasta é a mesma servida: o script cria nela os arquivos de teste e confere o que foi escrito.
"""
import argparse
import hashlib
import io
import os
import sys
import time

COMPARTILHAMENTO = "PS2SMB"


def passo(msg):
    print(f"[ok] {msg}", flush=True)


def falha(msg):
    print(f"[FALHA] {msg}", flush=True)
    sys.exit(1)


def prepara_pasta(pasta):
    os.makedirs(os.path.join(pasta, "DVD"), exist_ok=True)
    os.makedirs(os.path.join(pasta, "CD"), exist_ok=True)
    os.makedirs(os.path.join(pasta, "CFG"), exist_ok=True)
    dados = os.urandom(3 * 1024 * 1024 + 4321)  # vários pedidos de leitura
    with open(os.path.join(pasta, "DVD", "SLUS_200.62.Teste.iso"), "wb") as f:
        f.write(dados)
    for nome in ("teste_escrita.cfg", "renomeado.cfg"):
        caminho = os.path.join(pasta, "CFG", nome)
        if os.path.exists(caminho):
            os.remove(caminho)
    nova = os.path.join(pasta, "PASTA_NOVA")
    if os.path.isdir(nova):
        os.rmdir(nova)
    return dados


def impacket_disponivel():
    try:
        import impacket  # noqa: F401
    except ImportError:
        return False
    # Sem __init__.py o Python carrega um "namespace package": instalação incompleta.
    return getattr(impacket, "__file__", None) is not None


class ClienteImpacket:
    nome = "impacket"

    def __init__(self, host, porta):
        from impacket.smbconnection import SMBConnection, SMB_DIALECT
        self.c = SMBConnection(host, host, sess_port=porta, preferredDialect=SMB_DIALECT)
        self.c.login("ps2", "qualquer-senha")
        assert self.c.getDialect() == SMB_DIALECT, self.c.getDialect()

    def lista(self, pasta):
        padrao = "*" if not pasta else pasta.replace("/", "\\") + "\\*"
        return [(f.get_longname(), f.is_directory() != 0, f.get_filesize()) for f in self.c.listPath(COMPARTILHAMENTO, padrao)]

    def le(self, caminho):
        b = io.BytesIO()
        self.c.getFile(COMPARTILHAMENTO, caminho.replace("/", "\\"), b.write)
        return b.getvalue()

    def escreve(self, caminho, dados):
        self.c.putFile(COMPARTILHAMENTO, caminho.replace("/", "\\"), io.BytesIO(dados).read)

    def apaga(self, caminho):
        self.c.deleteFile(COMPARTILHAMENTO, caminho.replace("/", "\\"))

    def renomeia(self, de, para):
        self.c.rename(COMPARTILHAMENTO, de.replace("/", "\\"), para.replace("/", "\\"))

    def cria_pasta(self, caminho):
        self.c.createDirectory(COMPARTILHAMENTO, caminho)

    def apaga_pasta(self, caminho):
        self.c.deleteDirectory(COMPARTILHAMENTO, caminho)

    def eco(self):
        return None  # o SMBConnection do impacket não expõe ECHO

    def fecha(self):
        self.c.logoff()
        self.c.close()


class ClientePysmb:
    nome = "pysmb"

    def __init__(self, host, porta):
        from smb import smb_structs
        smb_structs.SUPPORT_SMB2 = False  # força SMB1 ("NT LM 0.12")
        from smb.SMBConnection import SMBConnection
        self.c = SMBConnection("ps2", "qualquer-senha", "TESTE", "PS2PONTE", use_ntlm_v2=False, is_direct_tcp=True)
        if not self.c.connect(host, porta, timeout=10):
            falha("login recusado")
        assert not self.c.isUsingSMB2

    def lista(self, pasta):
        return [(f.filename, f.isDirectory, f.file_size) for f in self.c.listPath(COMPARTILHAMENTO, pasta or "/")]

    def le(self, caminho):
        b = io.BytesIO()
        self.c.retrieveFile(COMPARTILHAMENTO, caminho, b)
        return b.getvalue()

    def escreve(self, caminho, dados):
        self.c.storeFile(COMPARTILHAMENTO, caminho, io.BytesIO(dados))

    def apaga(self, caminho):
        self.c.deleteFiles(COMPARTILHAMENTO, caminho)

    def renomeia(self, de, para):
        self.c.rename(COMPARTILHAMENTO, de, para)

    def cria_pasta(self, caminho):
        self.c.createDirectory(COMPARTILHAMENTO, caminho)

    def apaga_pasta(self, caminho):
        self.c.deleteDirectory(COMPARTILHAMENTO, caminho)

    def eco(self):
        return self.c.echo(b"PS2 KEEPALIVE ECHO", timeout=10)

    def fecha(self):
        self.c.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--porta", type=int, default=1445)
    ap.add_argument("--pasta", required=True, help="pasta servida pelo SmbDevMain")
    ap.add_argument("--cliente", choices=["impacket", "pysmb"])
    a = ap.parse_args()

    escolha = a.cliente or ("impacket" if impacket_disponivel() else "pysmb")
    if escolha == "impacket" and not impacket_disponivel():
        falha("impacket não está instalado por inteiro (antivírus?)")
    if escolha == "pysmb" and a.cliente is None:
        print("impacket indisponível neste PC; usando pysmb (SMB1)", flush=True)

    dados = prepara_pasta(a.pasta)
    t0 = time.time()
    c = (ClienteImpacket if escolha == "impacket" else ClientePysmb)(a.host, a.porta)
    passo(f"{c.nome}: negociou SMB1 e fez login com usuário/senha quaisquer")

    raiz = c.lista("")
    nomes = {n for n, _, _ in raiz}
    if not {"DVD", "CD", "CFG"} <= nomes:
        falha(f"raiz do compartilhamento: {sorted(nomes)}")
    passo(f"listou o compartilhamento {COMPARTILHAMENTO}: {sorted(n for n in nomes if n not in ('.', '..'))}")

    dvd = {n: (d, s) for n, d, s in c.lista("DVD")}
    if dvd.get("SLUS_200.62.Teste.iso") != (False, len(dados)):
        falha(f"listagem de DVD: {dvd}")
    passo(f"listou DVD: SLUS_200.62.Teste.iso com {len(dados)} bytes")

    lido = c.le("DVD/SLUS_200.62.Teste.iso")
    if lido != dados:
        falha(f"leitura: {len(lido)} bytes, md5 {hashlib.md5(lido).hexdigest()} != {hashlib.md5(dados).hexdigest()}")
    passo(f"leu o arquivo inteiro e os bytes conferem (md5 {hashlib.md5(dados).hexdigest()})")

    novo = os.urandom(100_000) + "ç fim".encode()
    c.escreve("CFG/teste_escrita.cfg", novo)
    with open(os.path.join(a.pasta, "CFG", "teste_escrita.cfg"), "rb") as f:
        if f.read() != novo:
            falha("escrita: conteúdo no disco difere")
    if c.le("CFG/teste_escrita.cfg") != novo:
        falha("escrita: releitura difere")
    passo(f"escreveu CFG/teste_escrita.cfg ({len(novo)} bytes), conferido no disco e relendo")

    c.renomeia("CFG/teste_escrita.cfg", "CFG/renomeado.cfg")
    if not os.path.exists(os.path.join(a.pasta, "CFG", "renomeado.cfg")):
        falha("renomear")
    passo("renomeou para CFG/renomeado.cfg")

    c.apaga("CFG/renomeado.cfg")
    if os.path.exists(os.path.join(a.pasta, "CFG", "renomeado.cfg")):
        falha("apagar: o arquivo continua no disco")
    passo("apagou o arquivo")

    c.cria_pasta("PASTA_NOVA")
    if not os.path.isdir(os.path.join(a.pasta, "PASTA_NOVA")):
        falha("criar pasta")
    c.apaga_pasta("PASTA_NOVA")
    if os.path.exists(os.path.join(a.pasta, "PASTA_NOVA")):
        falha("apagar pasta")
    passo("criou e apagou PASTA_NOVA")

    e = c.eco()
    if e is not None:
        if e != b"PS2 KEEPALIVE ECHO":
            falha(f"eco: {e!r}")
        passo("ECHO devolveu os mesmos bytes")

    c.fecha()
    passo(f"desconectou ({time.time() - t0:.2f} s)")
    print("TUDO OK")


if __name__ == "__main__":
    main()
