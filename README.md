# Kinesis Editor

**Editor de motion design open-source para Android** — keyframes com curvas Bézier, texto animado, formas,
efeitos, máscaras, edição de vídeo e áudio e **objetos 3D simplificados** na mesma timeline. Uma alternativa
gratuita ao Alight Motion, sem marca-d'água, sem paywall, sem nuvem e sem telemetria.

> Kinesis **não** é um Blender mobile: é um editor de motion graphics 2D com 3D integrado. Objetos 3D são camadas
> como qualquer outra — sem modelagem, sculpting, UV ou rigging.

Os projetos usam o formato aberto e documentado **`.knsproj`**, que também pode ser gerado fora do app — por exemplo,
pelo **Claude Code** com a skill [`/knsproj`](.claude/skills/knsproj/SKILL.md) incluída neste repositório.

---

## Sumário

- [Recursos da beta](#recursos-da-beta)
- [Compilar o app](#compilar-o-app)
- [Usando o editor](#usando-o-editor)
- [Formato .knsproj](#formato-knsproj)
- [Gerar projetos com o Claude Code (`/knsproj`)](#gerar-projetos-com-o-claude-code-knsproj)
- [CLI `knsproj`](#cli-knsproj)
- [Arquitetura](#arquitetura)
- [Testes](#testes)
- [Pendências e limitações conhecidas](#pendências-e-limitações-conhecidas)
- [Privacidade](#privacidade)
- [Licença](#licença)

## Recursos da beta

> Versão **0.1.2**: camadas **nulas** 2D/3D, parenting que não move a camada, câmera com posição e rotação independentes e a
> **curva cíclica** (onda entre dois keyframes), formato `.knsproj` v5. A 0.1.1 trouxe expressões estilo After Effects,
> animadores de texto por caractere, tema claro/escuro e idioma português/inglês; veja [docs/expressoes.md](docs/expressoes.md).

| Área | O que já funciona |
|---|---|
| **Projetos** | criar (9:16, 16:9, 1:1, 4:5, 4K; 24–60 fps), abrir, duplicar, excluir, miniaturas; salvamento automático atômico com backup |
| **.knsproj** | importar e exportar pelo seletor de arquivos do Android, com validação completa e proteção contra arquivos maliciosos |
| **Timeline** | múltiplas camadas, mover, aparar início/fim (não destrutivo), dividir no playhead, duplicar, excluir, agrupar/desagrupar, reordenar (arrastar com toque longo ou setas), zoom por pinça, snap a quadros e bordas, keyframes visíveis e arrastáveis |
| **Undo/redo** | histórico completo (gestos contínuos viram um único passo) |
| **Preview** | tempo real via OpenGL ES 3.0, play/pause, loop, quadro a quadro, navegação entre keyframes, áudio sincronizado |
| **Texto** | fontes do sistema ou importadas (TTF/OTF), peso, itálico, alinhamento, entrelinha, quebra, contorno; animadores por caractere/palavra/linha (máquina de escrever, fade, slide, escala, rotação, onda) |
| **Formas** | retângulo (cantos arredondados), elipse, polígono, estrela, linha/caminho Bézier (vértices arrastáveis no viewport, pontos suaves ou de canto); preenchimento sólido ou gradiente linear/radial; traço com ponta e junção; **trim paths** |
| **Mídia** | imagens (com orientação EXIF), vídeo (decodificação por hardware com seek preciso, velocidade, ponto de entrada), áudio (volume animável, fades, mudo, forma de onda na timeline) |
| **Animação** | keyframes em todas as propriedades numéricas, de vetor e de cor; 36 presets de easing + **editor de curvas Bézier**; loops `cycle`/`pingpong` |
| **Expressões (v0.1.1)** | JavaScript no estilo do After Effects em qualquer propriedade animável (`wiggle`, `loopOut`/`loopIn`, `linear`, `ease*`, `random`, `posterizeTime`, `thisComp`…) e **animadores de texto por caractere** com seletor de expressão (`textIndex`, `textTotal`, `selectorValue`); editor com destaque de sintaxe e erros com linha. Veja a [matriz de compatibilidade](docs/expressoes.md) — **não** é compatibilidade completa com o After Effects |
| **Composição** | parenting (com compensação para a camada não "pular"), grupos, modos de mesclagem (normal, adição, multiplicação, tela), opacidade |
| **Efeitos** | 60 efeitos procedurais e determinísticos em 13 categorias (Cor e luz, Desfoque e nitidez, Distorção — incluindo **Glass** com refração e dispersão espectral —, Movimentar e transformar, Estilizar, Glitch, Desenho e borda, Procedimental, Fosco/máscara/chave, Tempo e transição, Texto, Utilitário); tela "Adicionar efeito" com busca e prévias renderizadas; todos os parâmetros numéricos animáveis |
| **Máscaras** | até 4 por camada (retângulo arredondado/elipse; somar, subtrair, interseção; suavização, inversão) e **track mattes** (alfa/luma, normais e invertidos) |
| **Transições** | fade, slides, zoom, giro, desfoque e cortina — de entrada e de saída |
| **Motion blur** | por camada, com ângulo de obturador e número de amostras |
| **3D** | cubo, esfera, cilindro, cone, toro, plano e camadas **nulas** 2D/3D (pivôs e rigs); modelos **GLB/glTF**; posição/rotação/escala XYZ animáveis; materiais PBR (cor, textura, metalicidade, rugosidade, emissão); luzes ambiente, direcional e pontual; câmera com perspectiva e FOV animáveis, aponta por alvo ou por rotação X/Y/Z (independente da posição); hierarquia 2D/3D por parenting sem saltos; gizmo de eixos X/Y/Z por toque, rotação por arraste e órbita de câmera |
| **Exportação** | MP4 H.264 + AAC (resolução original, 720p, 1080p, 4K; três níveis de qualidade), com motion blur completo, salvo na galeria (Filmes/Kinesis) e compartilhável |
| **Aparência e idioma (v0.1.1)** | tema **escuro/claro/do sistema**, idioma **português/inglês/do sistema** (Ajustes) e apresentação inicial (*onboarding*) |
| **Curva cíclica (v0.1.2)** | no editor de curvas, o modo **Cíclico** faz o valor oscilar entre dois keyframes como uma onda; a alça amarela superior ajusta o *offset* e a inferior a quantidade de ondas |

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

**Releases:** o workflow [`release.yml`](.github/workflows/release.yml) roda ao empurrar uma tag `vX.Y.Z` (ou manualmente em *Actions → Release*). Ele reconstrói o keystore a partir do secret `KEYSTORE_BASE64`, compila `assembleRelease`, verifica com `apksigner verify --verbose --print-certs` (recusa APK sem assinatura, assinado com a chave de debug, depurável ou com certificado diferente do keystore) e publica o APK e o `.sha256` em GitHub Releases. Secrets necessários: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Para só testar o pipeline, rode o workflow manualmente com `publish = false`.

**Build reproduzível:** versões de Gradle (wrapper), Android Gradle Plugin, Kotlin e bibliotecas estão fixadas em
[`gradle/libs.versions.toml`](gradle/libs.versions.toml); o APK não inclui carimbos de data nem informações de
dependências do Google Play. O CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) compila o APK a cada push e
o publica como artefato `kinesis-debug-apk` na aba *Actions*.

Sem Android Studio: instale o *command-line tools* do Android, rode `sdkmanager "platforms;android-35" "build-tools;35.0.0"`
e crie `local.properties` com `sdk.dir=/caminho/do/android-sdk`.

## Usando o editor

0. **Primeira abertura:** uma apresentação rápida (pode ser pulada) com a escolha de idioma e tema; reveja em *Ajustes*.
1. **Tela inicial:** *Novo projeto* (formato, fps, duração, cor de fundo) ou *Abrir arquivo .knsproj*.
   O menu de cada projeto permite duplicar, exportar `.knsproj` e excluir. Em *Ajustes*: idioma e tema.
2. **Viewport:** toque para selecionar (toques repetidos entram em grupos). Arraste para mover, use pinça para escalar e
   gire com dois dedos. Em objetos 3D, arraste as pontas dos eixos **X (vermelho), Y (verde), Z (azul)** para mover em um
   eixo, ou alterne para a ferramenta *girar* na barra de reprodução. Com a câmera selecionada, arrastar orbita em torno do alvo (câmera por alvo) ou gira a câmera sem mover
   a posição (câmera por rotação); a pinça aproxima.
3. **Timeline:** arraste clipes para mover, as bordas para aparar; pinça para zoom; toque longo no nome para
   multisseleção, ou mantenha pressionado e arraste para reordenar. Os losangos amarelos são keyframes: toque para ir até
   eles, arraste para retemporizar.
4. **Propriedades:** cada propriedade animável tem um losango — toque para criar/remover um keyframe no playhead
   (pressione e segure para remover toda a animação). Com a propriedade animada, alterar o valor grava um keyframe
   automaticamente. O ícone de gráfico abre o **editor de curvas** do segmento atual.
   O ícone `</>` abre o **editor de expressões** da propriedade: código JavaScript com destaque de sintaxe, erro com número
   de linha, interruptor liga/desliga, presets opcionais e *Voltar aos keyframes*. Na aba *Texto*, *Animadores de texto
   (expressões)* cria animadores por caractere (por exemplo o *Bounce Text* do After Effects, sem alterações no código).
5. **Camadas:** visão hierárquica com grupos, pais (↳), visibilidade, trava e ordem. Trocar o pai **não move** a camada na
   tela: a transformação dela é recalculada para ficar no mesmo lugar.
6. **Adicionar (+):** texto, formas, linha, imagem, vídeo, áudio, primitivas 3D, nulo 2D/3D, modelo GLB, câmera e luzes.
7. **Exportar:** escolha resolução e qualidade; o vídeo vai para a galeria e pode ser compartilhado.

Sem nada selecionado, o painel de propriedades mostra as configurações da composição (formato, fps, duração, fundo e
motion blur).

## Formato .knsproj

Um `.knsproj` é um ZIP com `project.json` (JSON versionado) e uma pasta `assets/` com as mídias. A especificação
completa está em **[docs/knsproj.md](docs/knsproj.md)** e o schema oficial em
**[schema/knsproj-v5.schema.json](schema/knsproj-v5.schema.json)**. Resumo:

```json
{
  "format": "knsproj",
  "formatVersion": 5,
  "composition": { "width": 1080, "height": 1920, "fps": 30, "duration": 10, "background": "#0A2463" },
  "assets": [],
  "layers": [
    { "id": "cubo", "type": "object3d", "start": 0, "duration": 10,
      "transform3d": { "rotation": { "keyframes": [
        { "time": 0, "value": [-20, 0, 0] }, { "time": 10, "value": [-20, 360, 0] } ] } },
      "object3d": { "primitive": "cube", "size": 420, "material": { "color": "#DDE3EA", "metallic": 1, "roughness": 0.2 } } },
    { "id": "titulo", "type": "text", "start": 0.5, "duration": 9.5,
      "transform": { "position": [0, -620] },
      "text": { "content": "KINESIS", "fontSize": 150, "color": "#FFFFFF",
                "animators": [{ "type": "slideUp", "unit": "character", "stagger": 0.05 }] } }
  ]
}
```

Exemplos prontos em [`.claude/skills/knsproj/examples/`](.claude/skills/knsproj/examples): `basicos/` (um recurso por
pasta, com README), `receitas/` (composições completas como lower third e intro de logo) e `erros/` (projetos
inválidos de propósito, com o código de erro esperado e o conserto). Para instalar a skill fora deste repositório,
use [`knsproj-skill.zip`](.claude/skills/knsproj/knsproj-skill.zip) (traz a skill, os exemplos, a CLI e o schema; regere com
`python3 tools/pack_skill.py`).

## Gerar projetos com o Claude Code (`/knsproj`)

O repositório inclui a skill **`/knsproj`** em [`.claude/skills/knsproj/SKILL.md`](.claude/skills/knsproj/SKILL.md).
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
  offline, sem acesso a Java/arquivos/rede, com limite de instruções ([detalhes](docs/expressoes.md)).
- **Avaliação determinística:** `SceneEvaluator` (core) transforma `(projeto, tempo)` em uma descrição de quadro com
  todas as matrizes, cores, efeitos e conteúdos resolvidos. O preview e a exportação usam o mesmo `Compositor`, então
  o que se vê é o que se exporta.
- **Renderização (GLES 3.0):** camadas 2D são rasterizadas (texto/formas via Canvas, com cache por escala) e compostas
  como texturas com alfa pré-multiplicado; máscaras SDF e cortinas no fragment shader; efeitos em passes de
  framebuffer; camadas 3D consecutivas compartilham um depth buffer e usam sombreamento PBR (GGX + ambiente de estúdio
  procedural); motion blur por acumulação de sub-quadros.
- **Vídeo:** um decodificador `MediaCodec` por camada, renderizando em `SurfaceTexture` (textura OES), com busca ao
  quadro-chave anterior e decodificação até o tempo exato.
- **Áudio:** decodificação para cache PCM 48 kHz estéreo (mapeado em memória), mixer com volume por keyframe e fades,
  reprodução via `AudioTrack` e codificação AAC na exportação.
- **Exportação:** contexto EGL gravável sobre a *input surface* do encoder H.264, `eglPresentationTimeANDROID` para
  tempos exatos e `MediaMuxer` para o MP4.
- **Armazenamento:** cada projeto é uma pasta com `project.json` + `assets/` em `files/projects/`; toda gravação é
  atômica (`arquivo temporário → fsync → rename`) e mantém `project.json.bak`.

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
  reimporta o vídeo como camada (decodificação para textura OES) e exporta de novo;
- `UiSmokeTest` percorre a interface (apresentação inicial, tela inicial, editor): cria projeto, adiciona texto, cubo e estrela, navega pelos painéis e executa
  dividir/duplicar/agrupar.

No CI eles rodam num emulador Android 14 e imprimem miniaturas dos quadros no log (linhas `KSHOT`).

## Pendências e limitações conhecidas

Funcionalidades fora desta beta, documentadas em vez de simuladas:

- **Expressões:** só as APIs da [matriz](docs/expressoes.md) existem; `wiggle`/`random` não reproduzem os números do After Effects,
  não há `effect()`/`sourceText`/seletor de intervalo de texto, o motor (Rhino) não tem limite rígido de memória e a
  CLI Python só confere a estrutura do código (a checagem exata de sintaxe é do app).
- **Sombras projetadas** em 3D e iluminação por mapas de ambiente (IBL) reais — hoje há um ambiente de estúdio procedural.
- **glTF:** skinning, animações embutidas, morph targets, compressão Draco/meshopt e buffers/texturas externos.
- **Pen tool completa:** no app é possível arrastar os vértices de um caminho no viewport, adicionar/remover pontos,
  fechar o caminho e alternar cada ponto entre suave e canto; arrastar as alças de tangente individualmente e
  animar a forma do caminho ainda não é suportado.
- **Interpolação espacial** (trajetórias curvas de posição com tangentes) — a posição interpola em linha reta com easing temporal.
- **Máscaras de forma livre** (só retângulo/elipse) e **modos de mesclagem** além de normal/adição/multiplicação/tela.
- **Rotoscopia, chroma key, estabilização, velocidade variável (rampas) e reprodução reversa** de vídeo.
- **Áudio:** sem efeitos de áudio (equalização, compressão) nem ducking automático; velocidade diferente de 1× altera o tom.
- **Motion blur em camadas de vídeo** (o vídeo usa o quadro central) e **wipe** em grupos/3D (cai para fade).
- **Exportação** de GIF, PNG sequence, WebM ou com canal alfa.
- **Pré-composições** reutilizáveis (grupos funcionam como pré-composições locais) e biblioteca de materiais compartilhada.
- Preview muito pesado (muitas camadas com efeitos grandes em 4K) pode ficar abaixo de 30 fps em aparelhos modestos; o
  preview reduz a resolução durante a reprodução, mas não há cache de quadros renderizados.

## Privacidade

O Kinesis funciona 100% offline: não há contas, anúncios, telemetria, analytics nem chamadas de rede. As únicas
permissões são as concedidas pelo seletor de arquivos do sistema para os arquivos que você escolhe.

## Licença

[Apache License 2.0](LICENSE).
