<div align="center">

# TeleClone

**Clonador nativo de canais e grupos do Telegram para Android com transferência nuvem-a-nuvem (MTProto / TDLib).**

[![Build APK](https://github.com/hyenorthh-oss/TeleClone/actions/workflows/build.yml/badge.svg)](https://github.com/hyenorthh-oss/TeleClone/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/hyenorthh-oss/TeleClone?color=E5252A&label=Release)](https://github.com/hyenorthh-oss/TeleClone/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-141418?logo=android&logoColor=2ECC71)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-141418?logo=kotlin&logoColor=E5252A)](https://kotlinlang.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-141418)](LICENSE)

[**Baixar APK (v1.1)**](https://github.com/hyenorthh-oss/TeleClone/releases/latest/download/TeleClone-v1.1.apk) · [**Todas as Releases**](https://github.com/hyenorthh-oss/TeleClone/releases) · [**Relatar Bug**](https://github.com/hyenorthh-oss/TeleClone/issues)

</div>

---

## Visão Geral

**TeleClone** é um aplicativo Android nativo desenvolvido em **Kotlin** e **Jetpack Compose** sobre a **TDLib (Telegram Database Library)** para espelhamento e replicação de conteúdo entre canais e grupos do Telegram.

Na versão **v1.1**, o motor de clonagem opera inteiramente **nuvem-a-nuvem** nos servidores do Telegram via `ForwardMessages` (`sendCopy = true`), replicando mensagens e álbuns em ordem cronológica sem a tag *"Encaminhado de..."* e sem consumir banda ou armazenamento local com download e re-upload de mídias.

---

## Download Direto (Android)

> Requer **Android 8.0 (API 26)** ou superior (`arm64-v8a` / `armeabi-v7a`).

1. Baixe o instalador diretamente pela página de Releases:
   - [**TeleClone-v1.1.apk**](https://github.com/hyenorthh-oss/TeleClone/releases/latest/download/TeleClone-v1.1.apk)
2. No dispositivo Android, permita a instalação de fontes desconhecidas para o navegador/gerenciador de arquivos quando solicitado.
3. Abra o app e insira suas credenciais da API do Telegram (`API_ID` e `API_HASH`).

---

## Principais Recursos

- **Cópia Nuvem-a-Nuvem (`sendCopy = true`):** Encaminhamento limpo direto nos servidores do Telegram sem exibir a origem e sem baixar mídias para o aparelho.
- **Agrupamento Nativo de Álbuns:** Mensagens consecutivas com o mesmo `mediaAlbumId` são enviadas juntas em um único álbum intacto.
- **Execução em Segundo Plano com Retomada:** `CloneForegroundService` (`dataSync`) persiste `lastClonedMessageId` e `clonedCount` no banco **Room**, permitindo pausar, retomar e acompanhar o progresso pela notificação persistente.
- **Proteção Anti-Ban e `FLOOD_WAIT`:**
  - Cadência configurável entre envios (`1.0s` a `10.0s`).
  - Interceptação automática de erros `FLOOD_WAIT_X` / `429` com contagem regressiva e retomada automática.
- **Filtros e Tratamento de Texto:**
  - Filtros individuais por tipo de conteúdo (`Texto`, `Fotos`, `Vídeos`, `Áudios`, `Documentos`).
  - Remoção opcional de `@menções` e links (`EditMessageText` / `EditMessageCaption` pós-cópia).
  - Assinatura customizada opcional ao final das postagens.
- **Respeito às Permissões Nativas:** Ignora chats com proteção de conteúdo (`hasProtectedContent`) e mensagens em que a cópia não é permitida (`canBeSaved == false`).
- **Interface Dark Underground:** Material 3 em Jetpack Compose com paleta escura de alto contraste e ícones vetoriais minimalistas.

---

## Como Obter `API_ID` e `API_HASH`

O aplicativo não inclui credenciais embutidas no código. Cada usuário autentica com sua própria chave oficial do Telegram:

1. Acesse [my.telegram.org](https://my.telegram.org) e faça login com seu número de telefone.
2. Entre em **API development tools** e crie uma nova aplicação.
3. Copie o **`App api_id`** e o **`App api_hash`**.
4. Informe ambos na tela inicial do **TeleClone**, seguidos pelo telefone (`+55...`), código SMS/Telegram e senha 2FA (se configurada).

As credenciais e preferências são armazenadas localmente via **Jetpack DataStore Preferences**.

---

## Arquitetura e Tecnologias

```
com.teleclone.app
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt          # Room Database
│   │   ├── CloneTaskDao.kt         # CRUD + atualização atômica de progresso
│   │   └── SettingsDataStore.kt    # Credenciais e preferências de clonagem
│   ├── model/                      # Entities, Settings, AuthState, ProgressState
│   └── telegram/
│       └── TdLibManager.kt         # Singleton TDLib (auth, histórico, ForwardMessages)
├── service/
│   └── CloneForegroundService.kt   # Motor de clonagem + notificação + FloodWait
└── ui/
    ├── screens/                    # Login, MainScaffold, Clonagem, Perfil, Configurações
    └── theme/                      # Design System Dark Underground (Color, Type, Theme)
```

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin `1.9.22` + Coroutines / Flow |
| UI | Jetpack Compose (BOM `2024.02.00`), Material 3, Icons Extended |
| Motor Telegram | TDLib (`com.github.tdlibx:td:1.8.56`) — `arm64-v8a`, `armeabi-v7a` |
| Banco Local | Room `2.6.1` (KSP) |
| Preferências | Jetpack DataStore Preferences `1.0.0` |
| Imagens | Coil Compose `2.6.0` |
| CI/CD | GitHub Actions (`./gradlew assembleDebug`) |

---

## Compilação Local (Desenvolvedores)

Requisitos: **JDK 17** e **Android SDK 34**.

```bash
git clone https://github.com/hyenorthh-oss/TeleClone.git
cd TeleClone
chmod +x gradlew
./gradlew assembleDebug
```

O APK compilado será gerado em:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Aviso Legal

Este projeto é uma ferramenta de automação e gerenciamento pessoal que utiliza a biblioteca oficial [TDLib](https://core.telegram.org/tdlib). Utilize-o em conformidade com os [Termos de Serviço do Telegram](https://telegram.org/tos) e respeite os direitos autorais e restrições dos canais de origem.

## Licença

Distribuído sob a licença **MIT**. Consulte o arquivo [LICENSE](LICENSE) para mais detalhes.
