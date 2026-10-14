# Formato `.knsproj` — versão 1

O `.knsproj` é o formato de projeto aberto do Kinesis Editor. Ele guarda uma composição completa — camadas,
keyframes, curvas, efeitos, objetos 3D, materiais, hierarquias e as mídias usadas — em um único arquivo que
pode ser criado, lido e validado por qualquer ferramenta, sem depender do aplicativo nem de serviços online.

- **Schema oficial:** [`schema/knsproj-v1.schema.json`](../schema/knsproj-v1.schema.json) (JSON Schema draft 2020-12)
- **Validador de referência:** `python3 tools/knsproj validate <projeto>` (só biblioteca padrão do Python)
- **Implementação de referência:** módulo [`core`](../core) (Kotlin), usado pelo app Android
- **Exemplos:** [`examples/`](../examples)

## 1. Contêiner

Um `.knsproj` é um arquivo ZIP com esta estrutura:

```
meu-projeto.knsproj
├── project.json      documento do projeto (obrigatório)
├── assets/           mídias referenciadas em project.json → "assets[].path"
│   ├── logo.png
│   ├── clip.mp4
│   └── robo.glb
└── thumbnail.jpg     miniatura opcional
```

Regras:

- `project.json` é UTF-8. Recomenda-se gravá-lo como primeira entrada.
- Todo caminho de asset começa com `assets/`, é relativo e não pode conter `..`, `\`, `:` nem começar com `/`.
  Leitores **devem** rejeitar entradas inseguras (proteção contra *zip slip*).
- Mídias já comprimidas (PNG, JPEG, WebP, MP4, M4A, MP3…) são gravadas sem compressão (`STORED`);
  JSON, GLB e fontes usam `DEFLATE`.
- Os escritores de referência usam data fixa (1980-01-01) nas entradas para que o arquivo seja reproduzível
  byte a byte.
- Limites de leitura no app: 64 MB para `project.json`, 8 GB descompactados, 10 000 entradas.

A forma "desempacotada" (uma pasta com `project.json` e `assets/`) é equivalente e é como o app guarda os projetos
internamente e como a skill `/knsproj` monta projetos antes de empacotar.

## 2. Convenções

| Conceito | Convenção |
|---|---|
| Unidades espaciais | pixels da composição |
| Origem 2D | **centro** da composição; +x à direita, +y para baixo |
| Espaço 3D | mesma origem e eixos; +z se afasta do espectador |
| Rotação | graus; positivo = horário na tela (2D) e regra da mão direita nos eixos acima (3D, ordem X → Y → Z) |
| Escala | multiplicador (`1` = 100%) |
| Opacidade, volumes, proporções | `0..1` (salvo indicação) |
| Tempo | segundos (números decimais) |
| Tempo dos keyframes | **relativo ao início da camada** (`start`) que contém a propriedade |
| Cores | `"#RRGGBB"` ou `"#RRGGBBAA"` (sRGB, alfa não pré-multiplicado) |
| Ordem das camadas | ordem de pintura: o primeiro item é desenhado primeiro (fica **embaixo**) |

Com a câmera padrão, o plano `z = 0` coincide pixel a pixel com a composição: um cubo em `[0, 0, 0]` aparece no centro.

## 3. Documento raiz

```json
{
  "format": "knsproj",
  "formatVersion": 1,
  "generator": "Claude Code /knsproj",
  "meta": { "title": "Meu vídeo", "createdAt": "2026-10-14T12:00:00Z" },
  "composition": {
    "width": 1080, "height": 1920, "fps": 30, "duration": 10,
    "background": "#0A2463",
    "motionBlur": { "enabled": true, "shutterAngle": 180, "samples": 8 }
  },
  "assets": [],
  "layers": []
}
```

| Campo | Tipo | Descrição |
|---|---|---|
| `format` | `"knsproj"` | obrigatório |
| `formatVersion` | inteiro | obrigatório; esta especificação é a versão `1` |
| `generator` | string | quem gerou o arquivo |
| `meta` | objeto | `title`, `description`, `author`, `createdAt`, `modifiedAt` (ISO-8601) |
| `composition` | objeto | `width`, `height` (16–8192, pares para MP4), `fps` (1–120), `duration` (s), `background`, `motionBlur` |
| `assets` | array | mídias embutidas (seção 6) |
| `layers` | array | camadas de topo, em ordem de pintura |

## 4. Propriedades animáveis

Toda propriedade marcada como *animável* aceita duas formas:

```json
"opacity": 0.8
```

```json
"opacity": {
  "keyframes": [
    { "time": 0,   "value": 0, "easing": "easeOutCubic" },
    { "time": 0.6, "value": 1 }
  ],
  "loop": "none"
}
```

- `time` em segundos relativos ao início da camada; tempos estritamente crescentes.
- `value` tem o tipo da propriedade: número, `[x, y]`, `[x, y, z]` ou cor.
- `easing` define a curva do **segmento que começa** neste keyframe (até o próximo). Padrão: `"linear"`.
- Antes do primeiro keyframe vale o primeiro valor; depois do último, o último valor.
- `loop`: `"none"` (padrão), `"cycle"` (repete o intervalo dos keyframes) ou `"pingpong"` (vai e volta).

### Easing

Nome de preset:

`linear`, `hold` (salto no próximo keyframe), `ease`, `easeIn`, `easeOut`, `easeInOut`,
`easeInSine`, `easeOutSine`, `easeInOutSine`, `easeInQuad`, `easeOutQuad`, `easeInOutQuad`,
`easeInCubic`, `easeOutCubic`, `easeInOutCubic`, `easeInQuart`, `easeOutQuart`, `easeInOutQuart`,
`easeInQuint`, `easeOutQuint`, `easeInOutQuint`, `easeInExpo`, `easeOutExpo`, `easeInOutExpo`,
`easeInCirc`, `easeOutCirc`, `easeInOutCirc`, `easeInBack`, `easeOutBack`, `easeInOutBack`,
`easeInBounce`, `easeOutBounce`, `easeInOutBounce`, `easeInElastic`, `easeOutElastic`, `easeInOutElastic`.

Ou uma Bézier cúbica como no CSS (`x1` e `x2` entre 0 e 1; `y` pode extrapolar para criar *overshoot*):

```json
"easing": { "cubicBezier": [0.2, 0.0, 0.0, 1.0] }
```

Os presets `Back` usam Béziers com *overshoot*; `Bounce` e `Elastic` são calculados proceduralmente
(equações de easings.net).

## 5. Camadas

Campos comuns a todas as camadas:

| Campo | Padrão | Descrição |
|---|---|---|
| `id` | — | obrigatório, único em todo o projeto |
| `type` | — | `text`, `shape`, `image`, `video`, `audio`, `group`, `object3d`, `model3d`, `camera`, `light` |
| `name` | `""` | nome exibido |
| `start` | `0` | início, em segundos, no tempo do contêiner (composição ou grupo) |
| `duration` | `5` | duração em segundos (> 0) |
| `visible` | `true` | camadas ocultas não aparecem nem tocam áudio |
| `locked` | `false` | impede edição por gestos no app |
| `parent` | — | `id` de uma camada **irmã** cuja transformação é herdada (opacidade não é herdada) |
| `label` | — | cor da etiqueta na timeline |
| `blendMode` | `normal` | `normal`, `add`, `multiply`, `screen` |
| `motionBlur` | `false` | ativa motion blur nesta camada (se `composition.motionBlur.enabled`) |
| `transform` | identidade | transformação 2D (camadas 2D e grupos) |
| `transform3d` | identidade | transformação 3D (`object3d`, `model3d`) |
| `effects` | `[]` | efeitos aplicados em ordem |
| `masks` | `[]` | até 4 máscaras (camadas 2D) |
| `matte` | — | track matte |
| `transitionIn` / `transitionOut` | — | transições de entrada/saída |

Cada tipo tem um objeto de conteúdo **com o mesmo nome do tipo** (`"type": "text"` → `"text": {…}`), exceto `group`,
cujo conteúdo é `layers`.

### 5.1 Transformações

`transform` (2D):

| Campo | Animável | Padrão | |
|---|---|---|---|
| `anchor` | `[x, y]` | `[0, 0]` | pivô em pixels locais (o conteúdo é centrado na origem local) |
| `position` | `[x, y]` | `[0, 0]` | posição do pivô no espaço do pai |
| `scale` | `[x, y]` | `[1, 1]` | |
| `rotation` | número | `0` | graus, horário |
| `opacity` | número | `1` | 0..1 |

Matriz: `T(position) · R(rotation) · S(scale) · T(−anchor)`.

`transform3d`: `anchor`, `position`, `rotation`, `scale` (todos `[x, y, z]`) e `opacity`.
Matriz: `T(position) · Rz · Ry · Rx · S(scale) · T(−anchor)`.

### 5.2 `text`

```json
{
  "id": "title", "type": "text", "start": 0, "duration": 5,
  "transform": { "position": [0, -600] },
  "text": {
    "content": "KINESIS",
    "font": { "family": "sans-serif", "weight": 900, "italic": false },
    "fontSize": 140, "color": "#FFFFFF", "align": "center",
    "lineHeight": 1.2, "letterSpacing": 0.1, "maxWidth": 0,
    "stroke": { "color": "#000000", "width": 4 },
    "animators": [
      { "type": "slideUp", "unit": "character", "start": 0, "stagger": 0.05, "unitDuration": 0.5, "easing": "easeOutCubic", "amount": 80 }
    ]
  }
}
```

- `font.family`: família do sistema Android (`sans-serif`, `sans-serif-medium`, `sans-serif-light`, `sans-serif-condensed`,
  `sans-serif-black`, `serif`, `monospace`, `serif-monospace`, `casual`, `cursive`, `sans-serif-smallcaps`) ou
  `font.asset` com o id de um asset `font` (TTF/OTF).
- `fontSize`, `color`, `letterSpacing` (em *em*) e `stroke.color`/`stroke.width` são animáveis.
- `maxWidth` > 0 quebra linhas nessa largura (px).
- O bloco de texto é centrado na origem local da camada.

**Animadores de texto** (`animators`): animação por caractere, palavra ou linha. A unidade de ordem `i`
começa em `start + i·stagger` (tempo local) e anima por `unitDuration` segundos.

| Campo | Valores |
|---|---|
| `type` | `typewriter`, `fade`, `slideUp`, `slideDown`, `slideLeft`, `slideRight`, `scale`, `rotate`, `wave` |
| `mode` | `in` (aparece) ou `out` (desaparece); ignorado por `wave` |
| `unit` | `character`, `word`, `line` |
| `order` | `forward`, `backward`, `center`, `random` (aleatório determinístico) |
| `amount` | px para *slides* e amplitude do `wave`; graus para `rotate`; escala inicial em % para `scale` (0 = do nada) |

### 5.3 `shape`

```json
"shape": {
  "kind": "star",
  "size": [400, 400], "roundness": 0, "sides": 5, "innerRatio": 0.5,
  "fill": { "enabled": true, "color": "#7C5CFF", "gradient": null },
  "stroke": { "enabled": false, "color": "#FFFFFF", "width": 8, "cap": "round", "join": "round" },
  "trim": { "start": 0, "end": 1, "offset": 0 }
}
```

- `kind`: `rectangle`, `ellipse`, `polygon`, `star`, `path`.
- `size` (caixa delimitadora), `roundness` (raio dos cantos), `sides` (lados/pontas, arredondado),
  `innerRatio` (raio interno da estrela), `fill.color`, `stroke.color`, `stroke.width` e `trim.*` são animáveis.
- `fill.gradient` (estático): `{ "type": "linear" | "radial", "start": [x, y], "end": [x, y], "stops": [{ "offset": 0, "color": "#…" }, …] }`
  em pixels locais. No radial, `start` é o centro e `end` um ponto da borda.
- **Trim paths**: só a parte do contorno entre `start` e `end` (0..1, deslocada por `offset`) é desenhada — ideal para
  animações de "desenhar linha".
- `path`: `{ "closed": true, "points": [{ "point": [x, y], "in": [dx, dy], "out": [dx, dy] }] }` — Bézier cúbica com
  tangentes relativas ao ponto (estático na versão 1).

### 5.4 `image`, `video`, `audio`

```json
"image": { "asset": "logo" }
"video": { "asset": "clip", "sourceStart": 2.0, "speed": 1.0, "volume": 1, "muted": false, "fadeIn": 0, "fadeOut": 0.5 }
"audio": { "asset": "music", "sourceStart": 0, "speed": 1, "volume": 0.8, "fadeIn": 1, "fadeOut": 2 }
```

- Imagens e vídeos são exibidos no tamanho nativo (pixels), centrados; use `transform.scale` para ajustar.
- `sourceStart`: ponto de entrada na mídia (s). O instante local `t` mostra a mídia em `sourceStart + t·speed`.
- `volume` é animável (ganho linear). `speed` diferente de 1 altera também o tom do áudio.

### 5.5 `group`

```json
{ "id": "grupo", "type": "group", "start": 2, "duration": 4,
  "transform": { "scale": [1.2, 1.2] },
  "layers": [ … ] }
```

Os filhos têm tempos relativos ao início do grupo e são recortados por ele. A transformação do grupo é aplicada aos
filhos 2D; efeitos, máscaras, opacidade e modo de mesclagem do grupo são aplicados à imagem combinada dos filhos.

### 5.6 `object3d`

```json
{ "id": "cubo", "type": "object3d", "duration": 10,
  "transform3d": { "rotation": { "keyframes": [{ "time": 0, "value": [0, 0, 0] }, { "time": 10, "value": [0, 360, 0] }] } },
  "object3d": {
    "primitive": "cube", "size": 300,
    "material": { "color": "#C0C4CC", "metallic": 1, "roughness": 0.25, "emissive": "#000000", "texture": "madeira" }
  } }
```

- `primitive`: `cube`, `sphere`, `cylinder`, `cone`, `torus`, `plane`, `empty` (nó invisível, útil como pivô/pai).
- `size` (animável): a primitiva cabe em um cubo com esse lado, em pixels. O plano fica no plano XY, de frente para a
  câmera; o toro também. Cilindro e cone têm o eixo vertical.
- `material` (PBR metálico-rugoso): `color`, `metallic` (0 dielétrico → 1 metal), `roughness` (0 espelho → 1 fosco),
  `emissive` — todos animáveis — e `texture` (id de um asset `image`, mapeado nas UVs).

### 5.7 `model3d`

```json
"model3d": { "asset": "robo", "size": 500, "material": null }
```

Modelos **GLB** ou **glTF com buffers embutidos** (`data:` URIs). O modelo é centrado e escalado para que sua maior
dimensão seja `size` pixels; os eixos do glTF (+Y para cima, +Z para o espectador) são convertidos para os do Kinesis.
A hierarquia de nós do arquivo é preservada. `material`, se presente, substitui todos os materiais do modelo.
Não suportado na versão 1: skinning, animações internas do glTF, morph targets, Draco/meshopt, buffers externos.

### 5.8 `camera`

```json
"camera": { "position": [0, -200, -2600], "target": [0, 0, 0], "fov": 40 }
```

- `position` (animável). Se omitida, a câmera fica em `[0, 0, -d]` com `d = (altura/2) / tan(fov/2)`, de modo que o
  plano `z = 0` corresponde aos pixels da composição.
- `target` (animável): ponto para onde a câmera olha. `fov`: campo de visão vertical em graus.
- A câmera ativa é a camada `camera` mais acima (última na ordem) que está ativa no instante. Pode ter `parent`
  (camada 3D) — por exemplo, um `empty` girando para fazer uma órbita.
- Camadas 2D não passam pela câmera.

### 5.9 `light`

```json
"light": { "kind": "directional", "color": "#FFFFFF", "intensity": 1.2, "position": [-800, -1000, -1200], "target": [0, 0, 0], "radius": 4000 }
```

- `kind`: `ambient` (luz uniforme), `directional` (de `position` para `target`), `point` (em `position`, alcance `radius` px).
- `color`, `intensity`, `position`, `target`, `radius` são animáveis.
- Até 4 luzes direcionais/pontuais simultâneas; luzes ambientes são somadas.
- Sem nenhuma luz no projeto, é usada uma iluminação de estúdio padrão (ambiente + luz principal + preenchimento).
- Sombras projetadas não fazem parte da versão 1.

### 5.10 Composição 2D/3D

Camadas 3D (`object3d`, `model3d`) **consecutivas** na pilha compartilham o mesmo buffer de profundidade e se ocultam
corretamente entre si. Uma camada 2D entre elas "quebra" o grupo: o que está acima é desenhado por cima. Uma camada
3D com efeitos, modo de mesclagem diferente de `normal`, motion blur ou transição ativa é renderizada isoladamente
e composta como uma imagem.

## 6. Assets

```json
{ "id": "clip", "type": "video", "path": "assets/clip.mp4", "name": "praia.mp4",
  "mimeType": "video/mp4", "width": 1920, "height": 1080, "duration": 12.5, "hasAudio": true }
```

| `type` | Formatos aceitos pelo app |
|---|---|
| `image` | PNG, JPEG, WebP, GIF (1º quadro), HEIF (Android 9+) |
| `video` | H.264/H.265/VP9/AV1 conforme os decodificadores do aparelho (MP4, MOV, WebM, 3GP) |
| `audio` | AAC/M4A, MP3, WAV, OGG/Opus, FLAC |
| `model` | GLB; glTF com buffers `data:` |
| `font` | TTF, OTF |

`width`, `height`, `duration`, `hasAudio` e `mimeType` são metadados opcionais (o app e o `knsproj pack` os preenchem).

## 7. Efeitos

Lista em `effects`, aplicada em ordem sobre a camada já transformada. Todos têm `type` e `enabled` (padrão `true`);
os parâmetros são animáveis.

| `type` | Parâmetros (padrão) |
|---|---|
| `blur` | `radius` (12 px) |
| `colorAdjust` | `brightness` (0), `contrast` (0), `saturation` (0) em −1..1, `hue` (0°) |
| `tint` | `color` (#7D5CFF), `amount` (1) |
| `dropShadow` | `color` (#000000), `opacity` (0.6), `angle` (90° = para baixo), `distance` (16), `softness` (16) |
| `glow` | `color` (#FFFFFF), `radius` (24), `intensity` (1) |
| `invert` | `amount` (1) |
| `pixelate` | `size` (16 px) |
| `chromaticAberration` | `amount` (6 px), `angle` (0°) |
| `vignette` | `amount` (0.5), `softness` (0.5) |

## 8. Máscaras e track mattes

```json
"masks": [
  { "shape": "ellipse", "mode": "add", "position": [0, 0], "size": [600, 400], "roundness": 0, "feather": 30, "opacity": 1, "inverted": false }
]
```

Máscaras (até 4, camadas 2D) ficam no espaço local da camada. `mode`: `add`, `subtract`, `intersect`, combinadas em
ordem. `position`, `size`, `roundness`, `feather` e `opacity` são animáveis.

```json
"matte": { "layer": "retangulo-revelador", "mode": "alpha" }
```

A camada só aparece onde a camada irmã `layer` é opaca (`alpha`), transparente (`alphaInverted`), clara (`luma`) ou
escura (`lumaInverted`). A camada usada como matte é **ocultada automaticamente**. Se a fonte não estiver ativa no
instante, mattes `alpha`/`luma` escondem a camada e os invertidos a mostram inteira.

## 9. Transições

```json
"transitionIn":  { "type": "slideUp", "duration": 0.6, "easing": "easeOutCubic", "distance": 300 },
"transitionOut": { "type": "fade", "duration": 0.5 }
```

`type`: `fade`, `slideLeft`, `slideRight`, `slideUp`, `slideDown` (deslizam e esmaecem; `distance` padrão = 25% da
composição), `zoomIn`, `zoomOut`, `spin`, `blur`, `wipeLeft`, `wipeRight` (cortina; só camadas 2D de conteúdo).
A direção descreve o movimento na tela: `slideLeft` entra vindo da direita e sai pela esquerda.
`transitionIn` ocupa os primeiros `duration` segundos da camada e `transitionOut` os últimos.

## 10. Motion blur

`composition.motionBlur` define `enabled`, `shutterAngle` (180° = meio quadro) e `samples` (sub-quadros por quadro).
Cada camada opta com `"motionBlur": true`. O app renderiza `samples` sub-quadros centrados no instante do quadro e
faz a média (no preview usa no máximo 3 amostras durante a reprodução).

## 11. Validação

Um projeto é válido quando:

1. `project.json` respeita o [schema](../schema/knsproj-v1.schema.json) (o schema rejeita campos desconhecidos para
   pegar erros de digitação);
2. passa nas regras semânticas abaixo (códigos estáveis, iguais no app e na CLI):

| Código | Regra |
|---|---|
| `layer.duplicateId` / `asset.duplicateId` | ids únicos |
| `asset.path` / `asset.missingFile` | caminho seguro em `assets/` / arquivo presente no contêiner |
| `layer.missingContent` | objeto de conteúdo do tipo presente |
| `layer.parentMissing` / `layer.parentSelf` / `layer.parentCycle` / `layer.parentKind` | parent é irmão, sem ciclos, 2D↔2D e 3D↔3D, nunca câmera/luz/áudio |
| `layer.matteMissing` / `layer.matteSelf` / `layer.matteCycle` / `layer.matteKind` | matte é irmão visual, sem ciclos, aplicado a camada 2D |
| `layer.missingAsset` / `layer.assetType` | asset existe e é do tipo certo |
| `keyframe.order` | tempos estritamente crescentes |
| `layer.duration`, `composition.*`, `media.*`, `text.*`, `shape.*`, `effect.range`, `layer.transition` | faixas de valores |

Avisos (não impedem a importação): `keyframe.outOfRange` (todos os keyframes fora da duração da camada),
`composition.oddSize`, `layer.masks`, `layer.effects`, `camera.multiple`, `light.count`, `layer.start`.

## 12. Versionamento e compatibilidade

- `formatVersion` é um inteiro incrementado a **qualquer** mudança no formato (inclusive campos novos), para que o
  schema de cada versão continue estrito.
- Leitores convertem versões antigas com migrações encadeadas (`Migrations` no core) e **recusam** versões mais
  novas que conhecem, com uma mensagem pedindo atualização.
- O app ignora campos desconhecidos ao abrir (tolerância), mas ao salvar escreve apenas o que conhece.
- Valores iguais ao padrão podem ser omitidos; `format`, `formatVersion`, `meta`, `composition`, `layers` e, em cada
  camada, `id`, `type`, `name`, `start` e `duration` são sempre escritos pelo app.

## 13. Exemplo completo

Veja [`examples/metal-cube/project.json`](../examples/metal-cube/project.json): animação vertical de 10 s com um cubo
metálico girando, título com animação por caractere, halo desfocado, sombra, linha com trim path, câmera animada e
três luzes. Para gerar o arquivo importável:

```bash
python3 tools/knsproj pack examples/metal-cube -o metal-cube.knsproj
```
