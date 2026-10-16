# PS2 Ponte

App Android (sem root) que serve jogos para o PlayStation 2 pelo cabo de rede: celular → hub USB-C com porta Ethernet → PS2.

- **SMB1** (TCP 1445) para o OPL de qualquer versão, de 0.9 a 1.2 beta.
- **UDPFS** (UDP 62966) para NHDDL + Neutrino, serve uma pasta direto; o mais rápido.
- **UDPBD** (UDP 48573) para Neutrino/XEB+: um disco exFAT virtual gerado da pasta, ou o pendrive inteiro lido direto pela USB Host API.

Como usar no PS2: [docs/GUIA.md](docs/GUIA.md). Desenho: [docs/especificacao.md](docs/especificacao.md).

## Estrutura

| Onde | O quê |
|---|---|
| `core/` | Kotlin puro (JVM, compatível com Android 11+): `smb/`, `udpfs/`, `udpbd/`, `exfat/`, `block/`, `fs/`, `net/` |
| `app/` | Tela (`MainActivity`), serviço em primeiro plano (`ServidorService`), driver USB Mass Storage (`usb/UsbMassStorage`) |
| `scripts/` | Clientes de teste que imitam o PS2 (UDPFS, UDPBD), teste SMB com pysmb/impacket, harness do FatFs do ps2sdk |

## Compilar e testar

Requer JDK 17 e Android SDK 35 (aqui em `C:\Users\Pedro\AndroidDev`, usados pelo `g.ps1`).

```
.\g.ps1 :core:test               # testes do núcleo (SMB1 do OPL, exFAT, UDPBD)
.\g.ps1 :app:assembleDebug       # APK em app\build\outputs\apk\debug\app-debug.apk
.\g.ps1 :app:lintDebug
```

Testes de integração (servidores reais + clientes que imitam o PS2):

```
.\g.ps1 :core:runMain "-PmainClass=br.ps2ponte.core.PonteDevMainKt" "--args=<pasta> 64"
python -I scripts\ps2_udpfs_cliente.py --pasta <pasta> --imagem <exfat.img> --perda 0.03
python -I scripts\ps2_udpbd_cliente.py --imagem <exfat.img>
```

A imagem de comparação sai de `ExFatDevMainKt` com `--args=<pasta> <saida.img> 64`. O cliente UDPFS foi conferido antes contra o `udpfs_server.py` de referência do Neutrino.

Fontes de referência dos protocolos: Neutrino (`iop/udpbd`, `iop/udpfs`, `pc/udpfs_server.py`), `udpbd-server` (ps2max), `smbman` do ps2sdk e `cdvdman/smb.c` do OPL.
