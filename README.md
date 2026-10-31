# Pocket PS2 Server

Turn an Android phone into a game server for the PlayStation 2 over a network cable:
**phone → USB-C hub/adapter with an Ethernet port → network cable → PS2**.
No PC, no Wi-Fi bridge, no root required.

[Português abaixo](#português)

| Protocol | Port | For |
|---|---|---|
| **SMB1** | TCP 1445 | Open PS2 Loader (any version, 0.9 → 1.2 beta), RiptOPL |
| **UDPFS** | UDP 62966 | NHDDL + Neutrino, RiptOPL — serves the folder directly; the fastest |
| **UDPBD** | UDP 48573 | Neutrino, XEB+, RiptOPL — a virtual exFAT disk built from the folder, or a whole USB drive read directly |

## Features

- Serves a folder from the phone storage or a USB drive (exFAT, FAT32, NTFS) with the usual OPL layout (`DVD/`, `CD/`, `ART/`, …).
- "Whole USB drive" mode: reads a pendrive/HDD directly over USB (block device for UDPBD/UDPFS).
- File access in three ways: **Normal** (Android permissions), **Root** (Magisk/KernelSU) or **Shizuku** — the last two can read folders Android hides from apps, like the root of the internal storage or `/mnt/media_rw`.
- Live diagnosis: tells you when the cable network is missing, when the PS2 never showed up (and what to check), when the PS2 is on another network, when the hub glitched or a transfer stalled.
- A log on screen (copy/clear) so you can see what went wrong.
- Runs in the background with the screen off (foreground service + wake lock).
- English and Portuguese, following the phone language.
- Android 5.0 and newer.

## Requirements

- A phone that can do **Ethernet tethering** with a USB Ethernet adapter. Stock Android has it from Android 11; older or locked phones may get it with a custom ROM (e.g. LineageOS). If tethering is not available, the server still works on any network that reaches the PS2.
- A USB-C (or OTG) hub/adapter with an Ethernet port. A hub with its own power supply is more stable.
- A PS2 with a network adapter (fat) or a slim, and a loader: OPL, NHDDL + Neutrino, XEB+, RiptOPL…

## Quick start

1. Plug the hub into the phone and the cable into the PS2. Turn on **Settings › Hotspot & tethering › Ethernet tethering**.
2. Open the app. Step 1 shows the phone IP and a suggested IP for the PS2.
3. Step 2: choose the games folder (or tap *Create OPL folders*). Put the `.iso` files in `DVD/` or `CD/`.
4. Tap **Start**, then set up the loader (step 3 › *How to set up the PS2* shows the exact values with your IPs).

OPL › Network settings: IP type DHCP (or the suggested IP), server address = phone IP, port **1445**, share **PS2SMB**, user `guest`, empty password.

More details (in Portuguese): [docs/GUIA.md](docs/GUIA.md).

## Troubleshooting

The app writes hints (`↳ …`) in the log when something fails:

| Message | Where to look |
|---|---|
| No network on the cable | Hub power, cable, Ethernet tethering switch |
| No sign of the PS2 after 30 s | PS2 IP in the same network (or DHCP), server address = phone IP, loader using the right protocol |
| PS2 is not on the cable network | Its IP doesn't match the phone's subnet; replies may go out over Wi-Fi |
| Network adapter dropped for a moment | Hub without enough power, loose USB-C plug |
| PS2 stopped answering mid-transfer | Cable/hub glitch, or the game froze on the console |
| Failed to read the game file | USB drive loose/unpowered, folder access revoked, damaged file |
| Another app is using this port | Close other SMB/PS2 server apps |

## Building

Requires JDK 17 and the Android SDK 35.

```
./gradlew :core:test            # core tests (OPL SMB1, exFAT, UDPBD)
./gradlew :app:assembleRelease  # APK in app/build/outputs/apk/release/
```

(On Windows the helper `g.ps1` runs a local Gradle; set `JAVA_HOME`, `ANDROID_HOME` and `GRADLE` or edit the defaults at the top.)

For a signed release, create `keystore.properties` in the root (not committed):

```
storeFile=path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Integration tests (real servers + clients that imitate the PS2):

```
./gradlew :core:runMain -PmainClass=br.ps2ponte.core.PonteDevMainKt --args="<folder> 64"
python scripts/ps2_udpfs_cliente.py --pasta <folder> --perda 0.03
python scripts/ps2_udpbd_cliente.py --imagem <exfat.img>
```

### Layout

| Where | What |
|---|---|
| `core/` | Pure Kotlin (JVM): `smb/`, `udpfs/`, `udpbd/`, `exfat/`, `block/`, `fs/`, `net/` |
| `app/` | UI (`MainActivity`), foreground service, diagnosis (`Dicas`), USB Mass Storage driver, SAF and root/Shizuku file access (`priv/`) |
| `scripts/` | Test clients that imitate the PS2 (UDPFS, UDPBD), SMB test with impacket |

Protocol references: Neutrino (`iop/udpbd`, `iop/udpfs`, `pc/udpfs_server.py`), `udpbd-server`, ps2sdk `smbman` and OPL `cdvdman/smb.c`.

## License

[GPL-3.0](LICENSE). PlayStation 2 is a trademark of Sony Interactive Entertainment; this project is not affiliated with Sony. Use it with games you own.

---

## Português

App Android que serve jogos para o PlayStation 2 pelo cabo de rede: **celular → hub/adaptador USB-C com porta de rede → cabo → PS2**. Sem PC, sem root.

- **SMB1** (OPL), **UDPFS** (NHDDL/Neutrino, o mais rápido) e **UDPBD** (Neutrino/XEB+).
- Pasta do celular ou do pendrive, ou o pendrive inteiro lido direto pelo USB.
- Acesso aos arquivos **Normal**, **Root** ou **Shizuku** (os dois últimos leem a raiz do armazenamento e o pendrive em `/mnt/media_rw`, que o Android esconde).
- Diagnóstico na tela e dicas no log (`↳ …`) apontando onde está o problema: cabo, IP do PS2, hub, pendrive.
- Android 5.0 ou mais novo. A ancoragem Ethernet vem de fábrica no Android 11+; em aparelhos mais antigos pode aparecer com ROMs como a LineageOS.

Passo a passo: ligue o hub, ative **Ancoragem Ethernet**, escolha a pasta dos jogos, toque em **Iniciar** e configure o loader com os valores que o app mostra em *Como configurar o PS2*. Guia completo: [docs/GUIA.md](docs/GUIA.md).
