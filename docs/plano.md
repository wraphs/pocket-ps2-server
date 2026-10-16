# PS2 Ponte — plano

Referências baixadas (fora do Git): `research/` na pasta de rascunho da sessão — Neutrino (`udpbd.c`, `udprdma.c`, `UDPFS.md`, `UDPRDMA.md`, `udpfs_server.py`), `udpbd-server/main.cpp`, clientes SMB do OPL (`cdvdman/smb.c`) e do ps2sdk (`smbman`).

Cada tarefa: testes JUnit em `core/src/test` + validação independente indicada. `core` não usa APIs do Java além do que o Android API 30 tem.

| # | Tarefa | Pacote | Validação | Quem |
|---|---|---|---|---|
| 1 | Esqueleto Gradle (core JVM + app Android), interfaces comuns | `core` | build verde | coordenador |
| 2 | Servidor SMB1 | `core/smb` | impacket (Python) + replay dos pacotes do OPL | agente |
| 3 | exFAT virtual | `core/exfat` | FatFs do ps2sdk compilado com gcc monta e lê a imagem | agente |
| 4 | Servidor UDPBD clássico | `core/udpbd` | cliente Python que imita `iop/udpbd/udpbd.c` | coordenador |
| 5 | Servidor UDPFS/UDPRDMA | `core/udpfs` | cliente Python que imita o PS2, conferido contra `udpfs_server.py` | coordenador |
| 6 | Driver USB Mass Storage (BOT/SCSI) | `app/usb` | revisão + teste no aparelho | coordenador |
| 7 | App: tela, serviço, permissões, rede | `app` | `assembleDebug` + lint | coordenador |
| 8 | Guia do usuário (OPL/NHDDL/Neutrino, passo a passo) | `docs/` | — | coordenador |
