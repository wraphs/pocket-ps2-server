# PS2 Ponte — guia rápido

Celular (Galaxy S25) → hub USB-C com porta de rede → cabo de rede → PS2.
O pendrive/HD com os jogos pode ficar no mesmo hub ou os jogos podem estar no próprio celular.

Pré-requisitos: PS2 desbloqueado que roda ELFs (FMCB, PS2BBL, FreeDVDBoot… com wLaunchELF). PS2 Fat precisa do adaptador de rede; o Slim já tem a porta.

## 1. Instalar e preparar o app

1. Copie o `PS2Ponte.apk` para o celular e instale (permita "instalar apps desconhecidos").
2. Abra e toque em **Permitir acesso a todos os arquivos** e em **Não deixar a bateria parar o servidor**. Aceite as notificações.
3. Ligue o hub e o cabo no PS2. Toque em **Abrir Ancoragem** e ligue **Ancoragem Ethernet** (Configurações › Conexões › Roteador Wi-Fi e ancoragem). O celular passa a distribuir IP pelo cabo.
4. A seção **1. Rede** mostra o IP do celular (ex.: `192.168.42.129`) e o IP sugerido para o PS2 (ex.: `192.168.42.200`). Esses dois números são usados abaixo.

## 2. Organizar os jogos

Em uma pasta do celular ou na raiz do pendrive (de preferência **exFAT**, aceita ISO maior que 4 GB):

```
DVD/   jogos de DVD (.iso)
CD/    jogos de CD (.iso)
ART/  CFG/  CHT/  VMC/  THM/  LNG/  APPS/  POPS/
```

O app tem o botão **Criar as pastas do OPL aqui**. No OPL 0.9.x o nome precisa ser `SLUS_123.45.Nome do Jogo.iso`; do 1.0 em diante qualquer nome serve.

No app: **3. O que servir** › **Uma pasta** › "Usar a raiz de: (pendrive)" ou "Escolher outra pasta…" › **Iniciar servidor**.
No modo Pasta ficam ligados os três protocolos ao mesmo tempo: SMB1 (TCP 1445), UDPFS (UDP 62966) e UDPBD (UDP 48573, com um disco exFAT virtual da pasta).

## 3A. OPL pelo SMB — o mais simples (qualquer versão)

Recomendado: **OPL 1.2.0-Beta** mais recente (`OPNPS2LD.ELF` em github.com/ps2homebrew/Open-PS2-Loader/releases). OPL 0.9.3, 1.0.0 e 1.1.0 também funcionam.

No OPL: **Settings › Network Settings**

| Opção | Valor |
|---|---|
| Ethernet Link Mode | Auto |
| IP Address Type | **DHCP** (ou Static: IP `192.168.42.200`, Mask `255.255.255.0`, Gateway = IP do celular) |
| - SMB Server - Address Type | IP |
| Address | **IP do celular** (ex.: `192.168.42.129`) |
| Port | **1445** |
| Share | `PS2SMB` (qualquer nome serve; vazio = lista os compartilhamentos) |
| User | `guest` |
| Password | vazio |
| ETH Prefix Path | vazio |

Depois, em **Settings**: **ETH Start Mode = Auto**. Salve (**Save Changes**) e reinicie o OPL. Os jogos aparecem em **ETH Games**.
Velocidade típica do SMB no PS2: 1–2,5 MB/s. A maioria dos jogos roda bem; jogos com muitos vídeos podem engasgar. Nesses casos use o caminho 3B.

## 3B. NHDDL + Neutrino pelo UDPFS — o mais rápido (~10 MB/s)

1. Baixe o **NHDDL v1.2.2** (github.com/pcm720/nhddl/releases/tag/v1.2.2): o `nhddl-v1.2.2.zip` **e** o `neutrino_v1.8.0-35-…7z` da mesma página.
2. No cartão de memória (com o wLaunchELF), crie `mc0:/APPS/NHDDL/` e copie para lá: `nhddl.elf`, `neutrino.elf`, as pastas `config/` e `modules/` do Neutrino.
3. Crie `mc0:/APPS/NHDDL/nhddl.yaml` com:
   ```yaml
   mode: udpfs
   udpfs_ip: 192.168.42.200
   ```
4. Edite `mc0:/APPS/NHDDL/config/bsd-udpfs.toml` e troque `ip=192.168.1.10` pelo IP do PS2: `args = ["ip=192.168.42.200"]`. É o IP que o Neutrino usa dentro do jogo.
5. No app, modo **Pasta**, Iniciar. No PS2, abra o `nhddl.elf`. A lista de jogos vem da pasta do celular/pendrive.

## 3C. Neutrino/XEB+ pelo UDPBD (avançado)

- Modo **Pasta**: o PS2 enxerga a pasta como um disco exFAT (virtual).
- Modo **Pendrive/HD inteiro**: o app lê o pendrive direto pelo USB (bloco a bloco, sem depender da montagem do Android). Enquanto o servidor roda, o Android deixa de mostrar o pendrive; depois de parar, desconecte e reconecte.
- Em `config/bsd-udpbd.toml` do Neutrino, troque o IP: `args = ["ip=192.168.42.200"]`. Exemplo direto: `neutrino.elf -bsd=udpbd -dvd=udpbd:DVD/Jogo.iso`.

## Problemas comuns

| Sintoma | O que fazer |
|---|---|
| App diz "Nenhuma rede por cabo" | Ligue a Ancoragem Ethernet; troque o cabo; confira o LED da porta do hub |
| OPL: "Cannot connect to SMB server" | Address = IP do celular, Port = 1445, servidor iniciado no app |
| OPL: "DHCP server unavailable" | Ancoragem Ethernet desligada; ou use IP Static |
| Para com a tela apagada | Libere a bateria do app (botão na tela) e deixe a notificação do servidor ativa |
| No modo Pasta, arquivos novos criados pelo PS2 via UDPBD somem | Esperado: no exFAT virtual, só escritas em arquivos que já existem (VMC, configs) são gravadas; use SMB/UDPFS para criar arquivos |
