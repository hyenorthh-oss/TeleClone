<div align="center">

# TeleClone

**Clonador nativo de canais e grupos do Telegram para Android com transferência nuvem-a-nuvem via TDLib (MTProto).**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-141418?logo=kotlin&logoColor=E5252A)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-141418?logo=jetpackcompose&logoColor=E5252A)](https://developer.android.com/jetpack/compose)
[![TDLib / MTProto](https://img.shields.io/badge/TDLib%20%2F%20MTProto-1.8.56-141418?logo=telegram&logoColor=2ECC71)](https://core.telegram.org/tdlib)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-141418?logo=android&logoColor=2ECC71)](https://developer.android.com)
[![Build & Release](https://github.com/hyenorthh-oss/TeleClone/actions/workflows/build.yml/badge.svg)](https://github.com/hyenorthh-oss/TeleClone/actions/workflows/build.yml)

[**Baixar APK Direto (Releases)**](https://github.com/hyenorthh-oss/TeleClone/releases/latest) · [**Código-Fonte**](https://github.com/hyenorthh-oss/TeleClone) · [**Licença MIT**](LICENSE)

</div>

---

## Visão Geral e Recursos

O **TeleClone** é um aplicativo Android nativo (Kotlin + Jetpack Compose + Room + DataStore) criado para replicar conteúdos entre canais e grupos do Telegram com interface escura minimalista (**Dark Underground**) e execução resiliente em segundo plano.

- **Clonagem Rápida Nuvem-a-Nuvem (`sendCopy`):** Utiliza `TdApi.ForwardMessages` com `sendCopy = true` diretamente nos servidores do Telegram — sem exibir a tag *"Encaminhado de..."* e sem gastar armazenamento interno ou pacote de dados baixando mídias no aparelho.
- **Seleção Visual de Origem e Destino:** Escolha os canais e grupos por avatar, nome e contagem de membros em uma lista filtrável por busca, sem precisar descobrir ou digitar IDs numéricos.
- **Agrupamento Automático de Álbuns:** Mensagens consecutivas com o mesmo `mediaAlbumId` são enviadas juntas no mesmo álbum, mantendo a organização original da postagem.
- **Filtros e Personalização de Conteúdo:**
  - Filtros individuais para **Texto**, **Fotos**, **Vídeos**, **Áudios** e **Documentos**.
  - Remoção automática opcional de `@menções` e links (`EditMessageText` / `EditMessageCaption`).
  - Assinatura personalizada opcional ao final de cada postagem.
  - Cadência anti-ban configurável por slider (`1.0s` a `10.0s`).
- **Segundo Plano, Retomada e `FLOOD_WAIT`:**
  - Serviço em primeiro plano (`CloneForegroundService`) com notificação persistente de progresso e controles rápidos (`Pausar` / `Retomar`).
  - Persistência local via **Room Database** para retomar exatamente do último `lastClonedMessageId` em caso de pausa ou interrupção.
  - Tratamento nativo de limites de taxa (`FLOOD_WAIT_X` / `429`) com contagem regressiva e retomada automática.

---

## Como Baixar e Instalar

1. Acesse a página oficial de **Releases** do projeto:
   👉 **[https://github.com/hyenorthh-oss/TeleClone/releases/latest](https://github.com/hyenorthh-oss/TeleClone/releases/latest)**
2. Em **Assets**, baixe o arquivo **`TeleClone-v1.1.apk`**.
3. Toque no APK baixado para iniciar a instalação no seu dispositivo Android (**Android 8.0 ou superior**).

> [!NOTE]
> **Permissões e Play Protect:** Como o **TeleClone** é um projeto open-source distribuído diretamente via GitHub (fora da Google Play Store), o Android solicitará a permissão **"Instalar apps desconhecidos"** para o seu navegador ou gerenciador de arquivos. Caso o **Google Play Protect** exiba um aviso genérico de aplicativo não verificado pela Play Store, toque em *"Mais detalhes"* e depois em *"Instalar assim mesmo"*. Todo o código-fonte e o workflow de compilação pública estão auditáveis neste repositório.

---

## Tutorial Rápido de Configuração (`API_ID` e `API_HASH`)

Para conectar sua conta via protocolo oficial MTProto, o Telegram exige suas próprias credenciais de desenvolvedor:

1. Acesse **[https://my.telegram.org](https://my.telegram.org)** pelo navegador e faça login com o seu número de telefone (o código de confirmação será enviado dentro do próprio Telegram).
2. Clique em **API development tools**.
3. Preencha o formulário rápido de criação de aplicativo (ex.: *App title* e *Short name* como `TeleClone`) e confirme.
4. Copie o **`App api_id`** (numérico) e o **`App api_hash`** (sequência alfanumérica).
5. Abra o **TeleClone**, insira o `API_ID` e o `API_HASH`, digite seu número no formato internacional (ex.: `+5511999999999`), confirme o código SMS/Telegram e, se possuir, informe sua senha de verificação em duas etapas (2FA).

> [!IMPORTANT]
> **Privacidade Local:** O código do **TeleClone** não possui nenhuma credencial embutida e não utiliza servidores intermediários. Seu `API_ID`, `API_HASH`, sessão MTProto e preferências ficam armazenados **exclusivamente de forma local** no seu aparelho via TDLib e Jetpack DataStore.

---

## Compilação Manual (Opcional)

Se preferir compilar localmente a partir do código-fonte (JDK 17 + Android SDK 34):

```bash
git clone https://github.com/hyenorthh-oss/TeleClone.git
cd TeleClone
chmod +x gradlew
./gradlew assembleDebug
```

O APK será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

---

## Aviso de Uso Responsável

Esta ferramenta foi desenvolvida para automação pessoal, backup e gerenciamento de conteúdo entre canais e grupos. Utilize o **TeleClone** exclusivamente em canais próprios ou em comunidades onde o encaminhamento e a cópia de conteúdo sejam expressamente autorizados pelo administrador, sempre respeitando os [Termos de Serviço do Telegram](https://telegram.org/tos) e os direitos autorais aplicáveis.

## Licença

Distribuído sob a licença **MIT**. Consulte o arquivo [LICENSE](LICENSE) para mais informações.
