# Smart Remote

Controle remoto universal para Smart TVs, escrito em Kotlin nativo (Android
View system, sem Compose/MVVM/DI) — descobre TVs na rede local, pareia, e
controla via WebSocket, com uma tela de diagnóstico embutida para depurar
conexão e comandos em tempo real.

> **v0.7** — evolução completa da camada de descoberta de dispositivos
> (múltiplos protocolos, deduplicação inteligente, diagnóstico estruturado).
> Veja [Descoberta de TVs](#descoberta-de-tvs) para detalhes.

## Funcionalidades

- **Controle remoto completo**: d-pad, power, volume, canal, mute,
  play/pause, back, home, teclado numérico e alfabético.
- **Entrada de texto por voz**: usa o reconhecimento de fala nativo do
  Android e envia o texto reconhecido para a TV (mesmo caminho do teclado
  digitado).
- **Atalhos de streaming**: Netflix, Prime Video, Globoplay direto na tela
  principal, mais uma grade de apps (Disney+, Max, YouTube, Apple TV+,
  Paramount+, Crunchyroll, Plex) que se adapta ao que a TV conectada
  realmente suporta.
- **Descoberta e pareamento de TVs** na rede local (SSDP/UPnP + mDNS +
  confirmação via API nativa da Samsung), com feedback claro de status
  (Conectando.../Pareando.../Conectada).
- **Múltiplas TVs pareadas simultaneamente** — troca de TV ativa sem perder
  o pareamento das demais; reconexão automática, ao abrir o app, sempre com
  a última TV efetivamente usada (não a primeira pareada).
- **Painel de diagnóstico** embutido (acessível pelo botão de informação):
  mostra IP, marca, modelo, sistema, protocolo, status, ping, token
  (mascarado) e um log cronológico de eventos de conexão — útil tanto para
  o usuário final entender por que uma TV não conecta quanto para
  desenvolvimento.

## TVs suportadas

| Fabricante | Sistema | Status |
|---|---|---|
| Samsung | Tizen | ✅ Controle via WebSocket (`SamsungTizenController`) |
| LG | webOS | ✅ Controle via WebSocket/SSAP (`LgWebOsController`) |
| Sony/TCL/Philips/Hisense | Android TV / Google TV | 🔜 Planejado |
| Amazon | Fire OS | 🔜 Planejado |
| Roku | Roku OS | 🔜 Planejado |
| Hisense | VIDAA | 🔜 Planejado |

A **descoberta** já reconhece TVs desses fabricantes na rede (via
SSDP/mDNS), mesmo que o **controle** ainda não esteja implementado — nesse
caso, o app mostra a TV na lista, mas ao tentar conectar informa "TV ainda
não suportada nesta versão".

## Descoberta de TVs

A partir da v0.7, a descoberta deixou de depender de um único método
(`SSDP` com um Search Target fixo) e passou a ser um pipeline de scanners
independentes:

```
DeviceDiscoveryActivity
        │
        ▼
   DeviceScanner                 (orquestrador)
        │
        ├── DiscoveryCache            (estado temporário da busca atual)
        │      └── DiscoveryAggregator (dedup / merge / confidence)
        │
        ├── SsdpScanner    — M-SEARCH com múltiplos Search Targets
        │                    (ssdp:all, DIAL, UDAP da LG, MediaRenderer, ECP da Roku)
        ├── MdnsScanner    — Chromecast, Android TV Remote, DIAL, AirPlay, webOS
        └── SamsungDiscoveryScanner
                            — confirma candidatos via API HTTP nativa da
                              Samsung (`:8001/api/v2/`), identificando a TV
                              mesmo quando o SSDP falha
```

A deduplicação usa uma prioridade de identidade (`UUID → deviceId → MAC →
nome+modelo → IP`) com pontuação de confiança, para que um resultado mais
completo sempre substitua um genérico — sem nunca duplicar ou esconder uma
TV da lista. Cada scanner registra seu próprio ciclo de vida
(`DiscoveryDiagnostics`), permitindo rastrear exatamente onde e por que uma
TV específica não foi encontrada.

A arquitetura foi desenhada para que suportar um fabricante novo (LG,
Android TV, Roku, Fire TV, VIDAA) seja só criar um scanner que implemente
`DiscoveryScanner` e registrá-lo — sem alterar o restante do pipeline nem a
UI.

## Como funciona (visão geral da arquitetura)

```
app/src/main/java/com/example/smartremote/
├── discovery/     Descoberta de TVs na rede (SSDP, mDNS, confirmação por
│                   fabricante) + UI de busca/pareamento
├── manager/        TvManager (fachada única da UI para TVs pareadas/
│                   conectadas) + ConnectionManager (estado da conexão ativa)
├── controller/     Um TvController por fabricante (Samsung/Tizen, LG/webOS),
│                   cada um isolado no próprio protocolo de rede
├── model/          TvDevice, RemoteKey, enums de protocolo/SO
├── util/           Constants, DeviceStorage (persistência), CredentialStore
│                   (tokens de pareamento), NetworkUtils
├── diagnostic/      DiagnosticManager — estado/log de conexão exibido no
│                   painel de diagnóstico da tela principal
└── ui/             BottomSheets (teclado, texto, apps) da tela principal
```

Princípios que o projeto segue (e que qualquer contribuição deve manter):

- **Um `TvController` por fabricante**, isolado no próprio protocolo — a UI
  e o `TvManager` nunca sabem como cada marca fala com sua TV.
- **`TvManager` é o único ponto de acesso da UI** às TVs pareadas e à
  conexão ativa — decide qual `TvController` usar com base no
  `TvOperatingSystem` detectado na descoberta.
- **Três conceitos que nunca se misturam**: TVs *descobertas* (temporárias,
  recriadas a cada busca), TVs *salvas* (persistentes, podem ser várias) e
  TV *conectada* (uma só, por vez).
- **`TvDevice.stableKey()`** é a identidade de pareamento/credenciais — não
  depende do IP (que muda com renovação de DHCP), e é diferente da
  identidade multi-critério usada só durante a descoberta.

## Requisitos

- Android Studio (versão compatível com AGP 9.2.1 / compileSdk 37.1).
- Dispositivo/emulador com **minSdk 26** (Android 8.0+).
- TV e celular na **mesma rede Wi-Fi** — a descoberta é feita por multicast
  na rede local (SSDP/mDNS), então não funciona entre redes diferentes ou
  com isolamento de cliente (AP/client isolation) habilitado no roteador.

## Como rodar

```bash
git clone https://github.com/brunoCodds/smartremote.git
cd smartremote
./gradlew assembleDebug
```

Ou abra a pasta no Android Studio e rode normalmente (`Run ▶`).

## Stack

- **Kotlin** puro, sem Compose — Android Views + ViewBinding.
- **OkHttp** para WebSocket com as TVs.
- **RecyclerView + Material Components** para as listas/bottom sheets.
- Persistência simples via `SharedPreferences` + JSON nativo
  (`org.json`) — sem Room/DataStore.
- Sem MVVM, Repository, DI, LiveData ou Flow — arquitetura deliberadamente
  simples, organizada por responsabilidade de pacote.

## Roadmap

- [ ] Scanners de descoberta "do zero" para LG, Android TV, Roku, Fire TV
      e VIDAA (a arquitetura de Discovery já está pronta para recebê-los).
- [ ] Suporte de **controle** (não só descoberta) para os fabricantes acima.
- [ ] Lançamento de apps de streaming via protocolo nativo de cada
      fabricante (hoje `supportedApps()` já existe por `TvController`, mas
      poucos apps têm App ID confiável mapeado).
- [ ] Listener passivo de `NOTIFY ssdp:alive` (captura TVs que ligam durante
      a janela de busca).
- [ ] Varredura de sub-rede por porta conhecida como fallback manual
      explícito, para redes com multicast bloqueado.

## Licença

Ainda não definida. Se pretende deixar o projeto aberto para outras
pessoas usarem/contribuírem, vale adicionar um arquivo `LICENSE` (MIT e
Apache 2.0 são as escolhas mais comuns para projetos Android hobby/OSS).
