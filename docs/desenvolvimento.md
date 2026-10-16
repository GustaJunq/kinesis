# Desenvolvimento do Kinesis

Como compilar, testar e entender o código. Para baixar e usar o app, veja o [README](../README.md).

## Compilar o app

Requisitos:

- **JDK 17** ou mais recente
- **Android SDK** com a plataforma **API 35** (Android Studio Ladybug ou mais recente instala tudo automaticamente)
- Aparelho ou emulador com **Android 8.0 (API 26)** ou mais recente e **OpenGL ES 3.0**

```bash
git clone https://github.com/gustajunq/kinesis.git
cd kinesis
./gradlew :app:assembleDebug        # APK em app/build/outputs/apk/debug/
./gradlew :app:installDebug         # instala no aparelho conectado (adb)
```

Para um APK de release: `./gradlew :app:assembleRelease`. Sem a chave de produção ele sai assinado com a chave de debug
(só para testes; nunca é publicado). A assinatura de produção vem de variáveis de ambiente, nunca do repositório:
`KINESIS_KEYSTORE_FILE` (caminho do keystore), `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`.

**Releases:** o workflow [`release.yml`](../.github/workflows/release.yml) roda ao empurrar uma tag `vX.Y.Z` (ou manualmente em *Actions → Release*). Ele reconstrói o keystore a partir do secret `KEYSTORE_BASE64`, compila `assembleRelease`, verifica com `apksigner verify --verbose --print-certs` (recusa APK sem assinatura, assinado com a chave de debug, depurável ou com certificado diferente do keystore) e publica o APK e o `.sha256` em GitHub Releases. Secrets necessários: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Para só testar o pipeline, rode o workflow manualmente com `publish = false`.

**Build reproduzível:** versões de Gradle (wrapper), Android Gradle Plugin, Kotlin e bibliotecas estão fixadas em
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml); o APK não inclui carimbos de data nem informações de
dependências do Google Play. O CI ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) compila o APK a cada push e
o publica como artefato `kinesis-debug-apk` na aba *Actions*.

Sem Android Studio: instale o *command-line tools* do Android, rode `sdkmanager "platforms;android-35" "build-tools;35.0.0"`
e crie `local.properties` com `sdk.dir=/caminho/do/android-sdk`.

## Gerar projetos com o Claude Code (`/knsproj`)

O repositório inclui a skill **`/knsproj`** em [`.claude/skills/knsproj/SKILL.md`](../.claude/skills/knsproj/SKILL.md).
Abra o Claude Code na raiz do repositório e peça, por exemplo:

```
/knsproj Crie uma animação vertical de 10 segundos, com um cubo metálico girando, texto animado e fundo azul.
```

O Claude interpreta o pedido, cria a composição, gera camadas, keyframes, materiais e luzes, valida o projeto contra o
schema oficial com a CLI e entrega `generated/<nome>.knsproj`. Copie o arquivo para o celular e importe-o na tela
inicial do Kinesis. A skill roda localmente e não depende de nenhum serviço do Kinesis — e o app Android não contém
nenhuma integração com IA.

## CLI `knsproj`

Ferramenta em Python 3 (somente biblioteca padrão) para validar e empacotar projetos:

```bash
python3 tools/knsproj new meu-projeto --title "Teste" --width 1080 --height 1920 --duration 8
python3 tools/knsproj add-asset meu-projeto ~/Imagens/logo.png      # copia para assets/ e registra
python3 tools/knsproj validate meu-projeto                           # schema + regras semânticas
python3 tools/knsproj info meu-projeto                               # árvore de camadas e tempos
python3 tools/knsproj pack meu-projeto -o meu-projeto.knsproj        # valida e empacota (determinístico)
python3 tools/knsproj unpack arquivo.knsproj -o pasta
python3 tools/knsproj validate arquivo.knsproj --json                # saída para máquinas
```

Códigos de saída: `0` válido, `1` inválido, `2` erro de uso/E/S. O validador de schema embutido cobre todas as palavras-chave
usadas pelo schema oficial; com o pacote `jsonschema` instalado, `--engine jsonschema` usa a implementação de referência.

## Arquitetura

```
kinesis/
├── core/        Kotlin/JVM puro (sem Android): modelo, motor de animação, avaliação de cena,
│                formato .knsproj, validador, operações de timeline, undo, malhas e leitor glTF,
│                motor de expressões (pacote expr, interpretador Rhino embutido)
├── app/         Android: Jetpack Compose (UI), OpenGL ES 3.0 (render), MediaCodec (vídeo/áudio/exportação)
├── schema/      JSON Schema oficial do project.json
├── tools/       CLI knsproj (Python) e verificação de shaders
├── .claude/skills/knsproj/   skill /knsproj, exemplos (basicos, receitas, erros) e o .zip da skill
└── docs/        especificação do formato
```

- **Expressões:** o `ExpressionResolver` troca, a cada quadro, as propriedades com expressão pelo valor calculado antes do
  resto da avaliação; por isso preview, exportação e rolagem da timeline dão exatamente o mesmo resultado. O motor roda
  offline, sem acesso a Java/arquivos/rede, com limite de instruções ([detalhes](expressoes.md)).
- **Avaliação determinística:** `SceneEvaluator` (core) transforma `(projeto, tempo)` em uma descrição de quadro com
  todas as matrizes, cores, efeitos e conteúdos resolvidos. O preview e a exportação usam o mesmo `Compositor`, então
  o que se vê é o que se exporta.
- **Renderização (GLES 3.0):** camadas 2D são rasterizadas (texto/formas via Canvas, com cache por escala) e compostas
  como texturas com alfa pré-multiplicado; máscaras SDF e cortinas no fragment shader; efeitos em passes de
  framebuffer; camadas 3D consecutivas compartilham um depth buffer e usam sombreamento PBR (GGX + ambiente de estúdio
  procedural ou panorama HDRI), sombras por *shadow map* com PCF, profundidade de campo por distância e modos flat,
  toon e wireframe; motion blur por acumulação de sub-quadros.
- **Vídeo:** decodificadores `MediaCodec` compartilhados por arquivo (cortes do mesmo clipe usam um só; outro abre só
  quando dois trechos aparecem juntos), renderizando em `SurfaceTexture` (textura OES), com busca ao quadro-chave
  anterior e decodificação até o tempo exato. Vídeos pesados podem usar *proxies* leves no preview.
- **Áudio:** decodificação para cache PCM 48 kHz estéreo (mapeado em memória), mixer com volume por keyframe e fades,
  reprodução via `AudioTrack` e codificação AAC na exportação.
- **Exportação:** contexto EGL gravável sobre a *input surface* do encoder H.264, `eglPresentationTimeANDROID` para
  tempos exatos; o vídeo é codificado em blocos de 5 s guardados no cache (uma exportação interrompida continua de
  onde parou) e unido com `MediaMuxer` sem recodificar.
- **Armazenamento:** cada projeto é uma pasta com `project.json` + `assets/` em `files/projects/`; toda gravação é
  atômica (`arquivo temporário → fsync → rename`) e mantém `project.json.bak`, as últimas 10 versões em `.snapshots/` e,
  opcionalmente, uma cópia `.knsproj` em `Documentos/Kinesis`.

## Testes

```bash
./gradlew :core:test                          # motor de animação, expressões, formato, validador, operações, glTF
python3 -m unittest discover -s tools/tests   # CLI, schema e paridade com o validador Kotlin
python3 tools/check_shaders.py                # compila todos os shaders com glslangValidator
```

Os testes do core validam a saída do serializador Kotlin e os exemplos contra o schema oficial, e os mesmos fixtures
inválidos são verificados pelo validador Kotlin e pela CLI Python (os códigos de erro devem coincidir).

Testes instrumentados (aparelho ou emulador com GLES 3.0):

```bash
./gradlew :app:connectedDebugAndroidTest
```

- `RenderTest` renderiza os exemplos e um quadro com todos os efeitos, máscaras e primitivas pelo pipeline OpenGL real
  e verifica erros de GL e o conteúdo dos pixels;
- `ExportTest` exporta MP4 (H.264 + AAC) com os codificadores do aparelho, confere duração, resolução e faixas,
  reimporta o vídeo como camada (decodificação para textura OES) e exporta de novo; também cancela e retoma uma
  exportação em blocos e pré-renderiza uma camada em vídeo com alfa empacotado;
- `UiSmokeTest` percorre a interface (apresentação inicial, tela inicial, editor): cria projeto, adiciona texto, cubo e estrela, navega pelos painéis e executa
  dividir/duplicar/agrupar.

No CI eles rodam num emulador Android 14 e imprimem miniaturas dos quadros no log (linhas `KSHOT`).

## Limitações conhecidas

Funcionalidades fora desta beta, documentadas em vez de simuladas:

- **Expressões:** só as APIs da [matriz](expressoes.md) existem; `wiggle`/`random` não reproduzem os números do After Effects,
  não há `effect()`/`sourceText`/seletor de intervalo de texto, o motor (Rhino) não tem limite rígido de memória e a
  CLI Python só confere a estrutura do código (a checagem exata de sintaxe é do app).
- **3D:** uma única luz projeta sombra por quadro; o panorama de ambiente ilumina e reflete, mas não é desenhado como
  fundo; texto 3D usa fontes do sistema ou importadas, sem animadores por letra.
- **glTF:** skinning, animações embutidas, morph targets, compressão Draco/meshopt e buffers/texturas externos.
- **Pen tool completa:** arrastar as alças de tangente individualmente e animar a forma do caminho ainda não é suportado.
- **Interpolação espacial** (trajetórias curvas de posição com tangentes) — a posição interpola em linha reta com easing temporal.
- **Máscaras de forma livre** (só retângulo/elipse) e **modos de mesclagem** além de normal/adição/multiplicação/tela.
- **Rotoscopia, estabilização, velocidade variável (rampas) e reprodução reversa** de vídeo.
- **Áudio:** sem efeitos de áudio (equalização, compressão) nem ducking automático; velocidade diferente de 1× altera o tom.
- **Motion blur em camadas de vídeo** (o vídeo usa o quadro central) e **wipe** em grupos/3D (cai para fade).
- **Exportação** de GIF, PNG sequence, WebM ou MP4 com canal alfa (a pré-renderização de camada usa alfa empacotado
  internamente).
- **Pré-composições** reutilizáveis (grupos funcionam como pré-composições locais) e biblioteca de materiais compartilhada.
