# PS2 Ponte — especificação (16/10/2026)

App Android (Galaxy S25, Android 15+, sem root) que serve jogos para o PS2 pelo cabo de rede.
O PS2 está ligado por cabo no adaptador Ethernet de um hub USB-C conectado ao celular; no mesmo hub pode haver pendrive/HD.

## Fontes de dados

| Fonte | Como o app lê | Protocolos ligados |
|---|---|---|
| **Pasta** (celular ou pendrive montado pelo Android) | "Acesso a todos os arquivos" (`MANAGE_EXTERNAL_STORAGE`) + `java.io.File`; permite usar a raiz do pendrive | SMB1, UDPFS (arquivos + bloco via exFAT virtual), UDPBD (exFAT virtual) |
| **Pendrive/HD inteiro** (modo bloco) | USB Host API direto: Bulk-Only Transport + SCSI (READ/WRITE 10/16). O Android solta a montagem enquanto o servidor roda | UDPBD, UDPFS (bloco) |

## Protocolos (núcleo em Kotlin puro, `core/`, compatível com Android API 30)

1. **UDPBD v2 clássico** — porta UDP 0xBDBD (48573). Cliente: `iop/udpbd` do Neutrino (manda tudo em broadcast, de 0xBDBE). Comportamento idêntico ao `udpbd-server` original (gitlab ps2max): INFO_REPLY, READ_RDMA em rajada com `block_shift` escolhido por requisição, WRITE_RDMA de 512 bytes, WRITE_DONE.
2. **UDPFS sobre UDPRDMA** — porta UDP 0xF5F6 (62966), serviço 0xF5F5. Port fiel de `pc/udpfs_server.py` do Neutrino (sem compressão): modo arquivo (pasta) e handle 0 = dispositivo de bloco.
3. **SMB1** (dialeto "NT LM 0.12") — porta TCP 1445 (configurável; o Android não deixa abrir 445). Subconjunto que o OPL usa (menu: `smbman` do ps2sdk; jogo: `cdvdman/smb.c` do OPL). Aceita qualquer usuário/senha e qualquer nome de compartilhamento (exceto `IPC$`); lista um compartilhamento `PS2SMB`.
4. SMB2 não: nenhum loader de PS2 usa.

**Respostas UDP:** se o IP do PS2 não está em nenhuma sub-rede do celular, a resposta vai em broadcast pela interface cabeada (`ReplyRouter`) — a ministack do PS2 não confere o IP de destino.

## exFAT virtual (pasta → dispositivo de bloco)

- Disco de setores de 512 B: MBR com 1 partição tipo 0x07 começando no LBA 2048; volume exFAT gerado na hora a partir da árvore da pasta (fotografia tirada ao iniciar).
- Cluster de 128 KiB; cada arquivo e diretório contíguo; FAT com cadeias completas (NoFatChain = 0); bitmap, tabela up-case completa (checksum correto), rótulo "PS2".
- Leituras: metadados gerados sob demanda; dados lidos do arquivo real.
- Escritas: se caem nos dados de um arquivo existente (dentro do tamanho dele) vão para o arquivo real (VMC, configs); o resto fica numa camada em memória (limite 64 MiB) que se perde ao parar.
- Espaço livre virtual extra de 4 GiB para o PS2 conseguir criar arquivos durante a sessão.
- Validação: montar a imagem com o FatFs do ps2sdk (o mesmo código do PS2) compilado no PC.

## Rede

O usuário liga **Ancoragem Ethernet** no S25 (o celular vira servidor DHCP no cabo). O app mostra o IP do celular na interface cabeada e um IP sugerido para o PS2 (mesma sub-rede, final .200).

## App

- Uma tela: permissões (arquivos, notificações), fonte (lista de volumes montados com "usar raiz", botão "escolher pasta", lista de pendrives USB para modo bloco), botão Iniciar/Parar, IPs, log e velocidade.
- Serviço em primeiro plano (`connectedDevice`) com wake lock enquanto o servidor roda.
- Pacote `br.ps2ponte`, minSdk 30, targetSdk 34, sem bibliotecas externas.
