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
├── native/      Rust (JNI): extrusão de texto 3D e motor do Whisper; o core usa só se a biblioteca estiver presente
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

## Núcleo nativo (Rust)

`native/` é um crate Rust (`libkinesis_native.so`) chamado por JNI através de `NativeCore` (no core, Kotlin puro). A
biblioteca é **opcional**: sem ela (testes na JVM, build sem Rust) tudo continua em Kotlin. O que está nela:

| Peça | Por quê em Rust |
|---|---|
| `extrude` (extrusão de texto 3D) | Port fiel de `TextExtrusion`: entram dois vetores primitivos (pontos e tamanhos dos contornos), saem quatro (posições, normais, UVs, índices); sem objetos por ponto. Resultado idêntico ao Kotlin (teste de paridade). |
| `whisper` (feature `whisper`) | whisper.cpp via `whisper-rs`; precisa de qualquer forma de código nativo. Progresso e cancelamento por atômicos, sem chamar a JVM de dentro do Rust. |
| `stabilize` (feature `stabilize`) | vid.stab 1.1.0 (C, GPL-2.0+) vendorado em `native/third_party/vidstab` e compilado com a crate `cc` junto de um wrapper `ks_stab.c`. Duas passadas: `detect` (por quadro), `prepare` (caminho suavizado) e `apply` (por quadro), com planos YUV em `ByteBuffer` diretos. |

### Remoção de fundo e estabilização (app)

`BackgroundRemoval` usa o MediaPipe Tasks Vision (`ImageSegmenter`) com o modelo `selfie_segmenter.tflite` (Apache-2.0,
só pessoas). A máscara passa por `MaskTools` (limiar suave, suavização temporal adaptativa) e vira PNG com alfa ou, em
vídeo, um MP4 com alfa empacotado (cor à esquerda, alfa em cinza à direita; `video.alphaPacked`). `VideoStabilizer`
lê/escreve quadros YUV (`YuvVideoReader`/`YuvVideoWriter`, MediaCodec) e chama `NativeCore.Stabilizer`. `MediaSwap` (core)
põe o resultado como nova camada acima da original, que fica oculta (um passo de desfazer).

### Novidades da 0.1.6 (app)

- **TimeSlice** (`core/scene/TimeSlice.kt`, `render/SliceCache.kt`, `fx/timeSliceStrip.frag`): a conta (faixas, deslocamentos, pesos
  das bordas, qual quadro cada faixa mostra) fica no core e é testada; o compositor guarda a imagem da camada de cada quadro numa
  cache LRU limitada por memória, e uma faixa que olha para o passado lê dela. Quadro que falta é renderizado na hora (o decodificador
  procura o instante), no máximo N por quadro na prévia; sem orçamento, usa o quadro guardado mais próximo e a prévia pede outra
  passada ao parar. Em aparelho com pouca memória a prévia usa até 12 faixas e guarda só alguns quadros (`stride`); a exportação
  nunca descarta nada.
- **Vista do editor** (`core/scene/EditorCamera.kt`): câmera orbital (alvo, yaw, pitch, distância, fov) que substitui a câmera da cena só
  na prévia; a pirâmide da câmera, o quadro da composição e os eixos são desenhados no overlay. `editorView` é salvo no projeto, mas
  mudar a vista não entra no histórico (desfazer/refazer preservam a vista atual).
- **Real Camera** (`core/edit/RealCamera.kt`, `sensors/RealCameraSensor.kt`, `ui/editor/RealCameraController.kt`): a orientação do celular
  (matriz dispositivo→mundo) vira a rotação da câmera Kinesis por `C·(R0ᵀ·R)·C` com `C = diag(1,−1,−1)`; intensidade escala o ângulo
  do eixo-ângulo; travas zeram componentes do Euler relativo. A pose ao vivo só alimenta a prévia; o projeto muda uma vez, ao parar.
  Suavização gaussiana sem atraso e simplificação Ramer–Douglas–Peucker entram antes dos keyframes (`splice` troca só o trecho gravado).
- **Roto Brush** (`core/video/RotoOps.kt`, `RotoPropagator.kt`, `media/RotoBrushSession.kt`, `ui/editor/RotoBrushUi.kt`): os quadros do
  trecho são decodificados uma vez (pequenos, em pé) para um arquivo, e as máscaras ficam em outro, para o app não encher a memória; o
  `RotoPropagator` (testado no core com um segmentador de mentira) pergunta ao modelo no ponto mais interno da máscara anterior,
  limita a resposta ao lugar onde o objeto pode ter ido e mantém a máscara anterior (marcando o quadro) quando a resposta não parece
  o mesmo objeto. O vídeo final usa o mesmo `PackedAlpha` da remoção de fundo.

### Escala da timeline e áudio (Kotlin, no core)

`TimelineScale` (core) é a única conversão entre segundos e pixels da trilha: régua, clipes, waveform, keyframes,
marcadores e cursor a usam. `span()` devolve o trecho visível de um clipe sem limitar seu tamanho real (a causa do bug
de zoom era a largura do clipe ser cortada pelas restrições da trilha). O equalizador (`StereoEq`, biquads RBJ) roda no
mixer, por clipe, no preview e na exportação.

### O que **não** foi migrado, e por quê (benchmark do Kotlin, `CoreBench`, JVM aquecida)

Foi medido antes de decidir (`KINESIS_BENCH=1 ./gradlew :core:test --tests '*CoreBench*' -i`):

| Carga | Kotlin (ms) | Decisão |
|---|---|---|
| Avaliar a cena: 500 camadas com keyframes × 30 quadros | ≈ 9–15 (≈ 0,6 µs por camada e quadro) | **Não migrar**: já é ~0,3 ms por quadro; JNI custaria mais que o cálculo. |
| Avaliar a cena: 100 camadas com expressões × 30 quadros | ≈ 107–116 (≈ 37 µs por expressão) | **Não migrar**: o custo é o interpretador JavaScript (Rhino). Trocar o motor quebraria a compatibilidade da API de expressões. |
| Ler glTF de 500 mil triângulos (12 MB) | ≈ 9 | **Não migrar**: leitura em bloco, já rápida. |
| Transformar malha de 66 mil vértices | ≈ 6,5 | **Não migrar**. |
| Extrusão de 24 glifos (240+120 pontos, bevel) | ≈ 54–59 | **Migrado**. |
| Equalizador de 5 bandas em 1 min de áudio estéreo 48 kHz | ≈ 89 (0,15 % do tempo real) | **Fica em Kotlin**: custa uma fração do tempo real, o JNI não ganharia nada. |

Resultado da extrusão (mesma carga, mesma máquina x86_64): Kotlin ≈ 54–59 ms; Rust chamado por JNI ≈ 20–27 ms; Rust
puro ≈ 17 ms (`cargo run --release --example bench`). Ou seja, **2 a 3 vezes mais rápido** já contando a ida e volta
pelo JNI. Esses números são do HotSpot (JIT forte); no ART do celular o Kotlin tende a ser mais lento, mas **não foi
medido em aparelho**. O CI imprime a mesma comparação no resumo do job "Rust core".

### Compilar

```bash
cd native && cargo test --release                       # testes Rust
cd native && cargo build --release                      # biblioteca para esta máquina (os testes do core a usam sozinhos)
native/build-android.sh                                 # .so para arm64-v8a, armeabi-v7a e x86_64 (precisa de NDK, cargo-ndk, cmake)
```

O CI faz o mesmo (`.github/actions/native`) antes de montar o APK; `build-android.sh` copia também `libc++_shared.so`,
que o whisper.cpp exige.

## Testes

```bash
./gradlew :core:test                          # motor de animação, expressões, formato, validador, operações, glTF, legendas
(cd native && cargo test --release)           # núcleo em Rust; com a biblioteca compilada, :core:test compara Rust e Kotlin
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
