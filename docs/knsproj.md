# Formato `.knsproj` — versão 1

O `.knsproj` é o formato de projeto aberto do Kinesis Editor. Ele guarda uma composição completa — camadas,
keyframes, curvas, efeitos, objetos 3D, materiais, hierarquias e as mídias usadas — em um único arquivo que
pode ser criado, lido e validado por qualquer ferramenta, sem depender do aplicativo nem de serviços online.

- **Schema oficial:** [`schema/knsproj-v1.schema.json`](../schema/knsproj-v1.schema.json) (JSON Schema draft 2020-12)
- **Validador de referência:** `python3 tools/knsproj validate <projeto>` (só biblioteca padrão do Python)
- **Implementação de referência:** módulo [`core`](../core) (Kotlin), usado pelo app Android
- **Exemplos:** [`.claude/skills/knsproj/examples/`](../.claude/skills/knsproj/examples)

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

Lista em `effects`, aplicada **em ordem** sobre a imagem da camada já transformada (em pixels da composição).
Todo efeito tem `type` e `enabled` (padrão `true`); os demais campos são os parâmetros abaixo, e os omitidos valem o
padrão. Todos os efeitos são procedurais e determinísticos (shaders, sem IA nem modelos de ML): o mesmo projeto
sempre gera o mesmo quadro.

- **Animáveis:** todo parâmetro numérico, de cor ou de ponto aceita keyframes (seção 4). Escolhas (`mode`, `axis`…),
  liga/desliga e `stops` são estáticos.
- **Aleatoriedade:** efeitos com sorteio têm `seed` inteiro; o sorteio depende só de `seed` e do tempo local da
  camada (ex.: o glitch troca de padrão `rate` vezes por segundo).
- **Pontos** (`center`, `position`, `start`…): `[x, y]` em pixels relativos à origem (pivô) da camada, no espaço da
  própria camada — acompanham a camada quando ela se move. Em camadas 3D, relativos ao pivô projetado.
- **Comprimentos** (`radius`, `length`, `amplitude`…): pixels da composição.
- **Faixas:** valores fora da faixa são limitados ao renderizar e o validador acusa `effect.range`.

Categorias (na ordem do app): Todos os efeitos · Cor e luz · Desfoque e nitidez · Distorção · Movimentar e
transformar · Estilizar · Glitch · Desenho e borda · Procedimental · Fosco, máscara e chave · Tempo e transição ·
Texto · Utilitário e expressão. As categorias "Fosco, máscara e chave", "Tempo e transição" e "Texto" também têm
**atalhos** no app para recursos que não ficam em `effects` — máscaras (seção 8), track mattes (seção 8), transições
(`transitionIn`/`transitionOut`, seção 9) e animadores de texto (`text.animators`, seção 5.2).

Comportamentos especiais:

- **Movimentar e transformar** (`autoShake`, `spin`, `oscillate`, `float`, `pulse`): movem a imagem já renderizada
  da camada em torno da origem dela (ou de `center`); não alteram `transform`.
- **Procedimental** (`fractalNoise`, `gradientRamp`, `checkerboard`, `grid`): ignoram as cores da camada e combinam
  o padrão gerado com ela por `mode` (`replace`, `add`, `multiply`, `screen`, `overlay`) e `mix`, mantendo o alfa da
  camada. Para cobrir o quadro inteiro, aplique em um retângulo do tamanho da composição.
- **`glass`**: renderiza a camada como vidro. A forma vem do alfa (com bisel `bevel`); opcionalmente a luminância vira
  mapa de altura (`useLuminanceAsHeight`, `heightScale`, `heightSoftness`). O vidro refrata o que está **atrás** da
  camada — o composto das camadas abaixo dentro do mesmo contêiner (dentro de um grupo, só o que está no grupo) — com
  índice `refraction`, contorno brilhante (`edgeGlow*`) e aberração cromática por dispersão: o espectro visível é
  dividido em 7 bandas, cada uma refratada com índice ligeiramente diferente (`dispersion` = 0 desliga).
  Implementação própria; o conceito foi inspirado no Omino Glass (Omino Suite, de David Van Brink).
- **`depthMap`**: só em camadas `object3d`/`model3d`; mostra a distância à câmera entre `near` (branco) e `far`
  (preto). Substitui as cores da camada; em camadas 2D é ignorado e o validador avisa `effect.layerKind`.
- **`echo`**: desenha atrás da camada `count` cópias dela em `t − k·delay`, com opacidade `decay^k`; as cópias levam
  só os efeitos que vêm antes do `echo` na lista.
- **`posterizeTime`**: a camada inteira (transformação, conteúdo e efeitos) é avaliada no início do seu quadro em
  `fps`, criando um visual de stop-motion. A posição na lista não importa.
- No preview do celular, efeitos com muitas amostras (desfoques de lente, direcional e radial, brilho profundo)
  usam menos amostras durante a reprodução, como no motion blur; a exportação usa sempre a qualidade total.

Migração da versão 1: `colorAdjust` vira `hueShift` (`hue`), `brightnessContrast` (`brightness`, `contrast`) e
`saturation` (`saturation`), nessa ordem, preservando valores e keyframes; os demais efeitos não mudam.

Tabela completa (gerada a partir do registro de efeitos do core):

<!-- BEGIN effects-table: gerado a partir do EffectRegistry (RegistryArtifactsTest) -->
| `type` | Categoria | Parâmetro | Padrão | Faixa |
|---|---|---|---|---|
| `gradientMap` (Mapa de gradiente) | Cor e luz | `stops` | `#000000`@0 → `#FFFFFF`@1 | 2 a 8 stops `{offset, color}` (estático) |
|  |  | `amount` | `1` | 0..1 % |
| `exposure` (Exposição) | Cor e luz | `exposure` | `0` | -10..10 EV |
|  |  | `offset` | `0` | -1..1 |
|  |  | `gamma` | `1` | 0.1..5 |
| `brightnessContrast` (Brilho e contraste) | Cor e luz | `brightness` | `0` | -1..1 % |
|  |  | `contrast` | `0` | -1..1 % |
| `saturation` (Saturação) | Cor e luz | `saturation` | `0` | -1..1 % |
| `hueShift` (Matiz) | Cor e luz | `hue` | `0` | -3600..3600 ° |
| `invert` (Inverter cores) | Cor e luz | `amount` | `1` | 0..1 % |
| `fill` (Preenchimento) | Cor e luz | `color` | `#FFFFFF` | cor |
|  |  | `amount` | `1` | 0..1 % |
| `deepGlow` (Brilho profundo) | Cor e luz | `threshold` | `0.6` | 0..1 % |
|  |  | `radius` | `60` | 0..1000 px |
|  |  | `intensity` | `1.5` | 0..10 × |
|  |  | `falloff` | `0.5` | 0..1 % |
|  |  | `color` | `#FFFFFF` | cor |
| `lensFlare` (Lens flare) | Cor e luz | `position` | `[-200, -200]` | `[x, y]` px relativos à origem da camada |
|  |  | `intensity` | `1` | 0..5 × |
|  |  | `color` | `#FFD9A1` | cor |
|  |  | `rings` | `4` | inteiro 0..12 |
| `tint` (Tingir) | Cor e luz | `color` | `#7D5CFF` | cor |
|  |  | `amount` | `1` | 0..1 % |
| `glow` (Brilho simples) | Cor e luz | `color` | `#FFFFFF` | cor |
|  |  | `radius` | `24` | 0..500 px |
|  |  | `intensity` | `1` | 0..10 × |
| `colorBalance` (Equilíbrio de cor) | Cor e luz | `shadows` | `#808080` | cor |
|  |  | `midtones` | `#808080` | cor |
|  |  | `highlights` | `#808080` | cor |
|  |  | `preserveLuminosity` | `true` | `true`/`false` (estático) |
| `vibrance` (Vibração) | Cor e luz | `vibrance` | `0.5` | -1..1 % |
| `blur` (Desfoque gaussiano) | Desfoque e nitidez | `radius` | `12` | 0..500 px |
| `sharpen` (Nitidez) | Desfoque e nitidez | `amount` | `0.5` | 0..5 × |
|  |  | `radius` | `1.5` | 0.5..20 px |
| `lensBlur` (Desfoque de lente) | Desfoque e nitidez | `radius` | `12` | 0..200 px |
|  |  | `blades` | `6` | inteiro 0..12 |
|  |  | `rotation` | `0` | -3600..3600 ° |
|  |  | `brightness` | `0.5` | 0..5 × |
| `directionalBlur` (Desfoque direcional) | Desfoque e nitidez | `angle` | `0` | -3600..3600 ° |
|  |  | `length` | `30` | 0..1000 px |
| `radialBlur` (Desfoque radial) | Desfoque e nitidez | `mode` | `"zoom"` | `spin`, `zoom` (estático) |
|  |  | `amount` | `10` | 0..100 |
|  |  | `center` | `[0, 0]` | `[x, y]` px relativos à origem da camada |
| `pinchBulge` (Pinçar/Estufar) | Distorção | `amount` | `0.5` | -1..1 % |
|  |  | `center` | `[0, 0]` | `[x, y]` px relativos à origem da camada |
|  |  | `radius` | `300` | 0..8000 px |
|  |  | `falloff` | `0.5` | 0..1 % |
| `glass` (Glass) | Distorção | `refraction` | `1.5` | 1..3 |
|  |  | `dispersion` | `0.3` | 0..1 % |
|  |  | `edgeGlow` | `0.6` | 0..5 × |
|  |  | `edgeGlowWidth` | `6` | 0..200 px |
|  |  | `edgeGlowColor` | `#FFFFFF` | cor |
|  |  | `bevel` | `24` | 0..400 px |
|  |  | `useLuminanceAsHeight` | `false` | `true`/`false` (estático) |
|  |  | `heightScale` | `20` | 0..400 px |
|  |  | `heightSoftness` | `4` | 0..100 px |
| `warp` (Deformar) | Distorção | `mode` | `"wave"` | `wave`, `fractal` (estático) |
|  |  | `amplitude` | `20` | 0..1000 px — com `mode: "wave"` |
|  |  | `frequency` | `3` | 0..100 — com `mode: "wave"` |
|  |  | `direction` | `0` | -3600..3600 ° — com `mode: "wave"` |
|  |  | `phase` | `0` | -3600..3600 ° — com `mode: "wave"` |
|  |  | `amount` | `20` | 0..1000 px — com `mode: "fractal"` |
|  |  | `scale` | `200` | 1..4000 px — com `mode: "fractal"` |
|  |  | `complexity` | `3` | inteiro 1..8 — com `mode: "fractal"` |
|  |  | `evolution` | `0` | -3600..3600 ° — com `mode: "fractal"` |
|  |  | `seed` | `0` | inteiro 0..99999 |
| `mirror` (Espelhar) | Distorção | `axis` | `"horizontal"` | `horizontal`, `vertical`, `both` (estático) |
|  |  | `center` | `[0, 0]` | `[x, y]` px relativos à origem da camada |
|  |  | `angle` | `0` | -3600..3600 ° |
| `twirl` (Redemoinho) | Distorção | `angle` | `180` | -3600..3600 ° |
|  |  | `radius` | `300` | 0..8000 px |
|  |  | `center` | `[0, 0]` | `[x, y]` px relativos à origem da camada |
| `ripple` (Ondulação) | Distorção | `amplitude` | `10` | 0..500 px |
|  |  | `wavelength` | `60` | 1..4000 px |
|  |  | `phase` | `0` | -3600..3600 ° |
|  |  | `radius` | `600` | 0..8000 px |
|  |  | `center` | `[0, 0]` | `[x, y]` px relativos à origem da camada |
| `autoShake` (Tremor automático) | Movimentar e transformar | `amplitude` | `10` | 0..1000 px |
|  |  | `frequency` | `8` | 0..60 Hz |
|  |  | `axes` | `"xy"` | `xy`, `x`, `y` (estático) |
|  |  | `rotation` | `2` | 0..180 ° |
|  |  | `seed` | `0` | inteiro 0..99999 |
| `spin` (Girar) | Movimentar e transformar | `speed` | `90` | -36000..36000 °/s |
|  |  | `center` | `[0, 0]` | `[x, y]` px relativos à origem da camada |
| `oscillate` (Oscilante) | Movimentar e transformar | `frequency` | `1` | 0..60 Hz |
|  |  | `amount` | `20` | 0..2000 px |
|  |  | `scaleAmount` | `0.05` | 0..2 % |
|  |  | `rotationAmount` | `5` | 0..360 ° |
|  |  | `phase` | `0` | -3600..3600 ° |
| `float` (Flutuar) | Movimentar e transformar | `amplitude` | `12` | 0..1000 px |
|  |  | `frequency` | `0.5` | 0..20 Hz |
|  |  | `phase` | `0` | -3600..3600 ° |
| `pulse` (Pulsar) | Movimentar e transformar | `amount` | `0.08` | 0..2 % |
|  |  | `frequency` | `1` | 0..60 Hz |
|  |  | `phase` | `0` | -3600..3600 ° |
| `pixelate` (Pixelizar) | Estilizar | `size` | `16` | 1..1000 px |
| `dropShadow` (Sombra) | Estilizar | `color` | `#000000` | cor |
|  |  | `opacity` | `0.6` | 0..1 % |
|  |  | `angle` | `90` | -3600..3600 ° |
|  |  | `distance` | `16` | 0..2000 px |
|  |  | `softness` | `16` | 0..500 px |
| `vignette` (Vinheta) | Estilizar | `amount` | `0.5` | 0..1 % |
|  |  | `softness` | `0.5` | 0.01..1 % |
| `halftone` (Retícula) | Estilizar | `dotSize` | `8` | 2..400 px |
|  |  | `angle` | `45` | -3600..3600 ° |
|  |  | `monochrome` | `false` | `true`/`false` (estático) |
| `posterize` (Posterizar) | Estilizar | `levels` | `6` | inteiro 2..64 |
| `filmGrain` (Granulação) | Estilizar | `amount` | `0.15` | 0..1 % |
|  |  | `size` | `1.5` | 0.5..20 px |
|  |  | `seed` | `0` | inteiro 0..99999 |
| `scanlines` (Scanlines/CRT) | Estilizar | `spacing` | `4` | 2..200 px |
|  |  | `intensity` | `0.4` | 0..1 % |
|  |  | `curvature` | `0` | 0..1 % |
| `blockGlitch` (Glitch em blocos) | Glitch | `blockSize` | `48` | 4..2000 px |
|  |  | `intensity` | `0.5` | 0..1 % |
|  |  | `rate` | `8` | 0..60 Hz |
|  |  | `seed` | `0` | inteiro 0..99999 |
| `lineGlitch` (Glitch em linhas) | Glitch | `lineHeight` | `6` | 1..500 px |
|  |  | `offset` | `40` | 0..2000 px |
|  |  | `density` | `0.25` | 0..1 % |
|  |  | `rate` | `12` | 0..60 Hz |
|  |  | `seed` | `0` | inteiro 0..99999 |
| `vhs` (Fita VHS) | Glitch | `tracking` | `0.3` | 0..1 % |
|  |  | `jitter` | `0.3` | 0..1 % |
|  |  | `colorBleed` | `4` | 0..100 px |
|  |  | `noise` | `0.2` | 0..1 % |
|  |  | `scanlines` | `0.3` | 0..1 % |
|  |  | `seed` | `0` | inteiro 0..99999 |
| `chromaticAberration` (Aberração cromática) | Glitch | `amount` | `6` | 0..500 px |
|  |  | `angle` | `0` | -3600..3600 ° |
| `rgbSplit` (Separação RGB) | Glitch | `amount` | `8` | 0..500 px |
|  |  | `angle` | `0` | -3600..3600 ° |
|  |  | `rate` | `0` | 0..60 Hz |
|  |  | `seed` | `0` | inteiro 0..99999 |
| `staticNoise` (Chuvisco) | Glitch | `amount` | `0.5` | 0..1 % |
|  |  | `size` | `1` | 0.5..40 px |
|  |  | `rate` | `24` | 0..120 Hz |
|  |  | `seed` | `0` | inteiro 0..99999 |
| `findEdges` (Encontrar bordas) | Desenho e borda | `strength` | `1` | 0..5 × |
|  |  | `invert` | `false` | `true`/`false` (estático) |
| `outline` (Contorno) | Desenho e borda | `color` | `#FFFFFF` | cor |
|  |  | `width` | `4` | 0..200 px |
|  |  | `opacity` | `1` | 0..1 % |
|  |  | `position` | `"outside"` | `outside`, `inside`, `center` (estático) |
| `emboss` (Relevo) | Desenho e borda | `angle` | `135` | -3600..3600 ° |
|  |  | `strength` | `1` | 0..5 × |
|  |  | `distance` | `2` | 0.5..50 px |
| `sketch` (Esboço) | Desenho e borda | `strength` | `1` | 0..5 × |
|  |  | `colored` | `false` | `true`/`false` (estático) |
| `fractalNoise` (Ruído fractal) | Procedimental | `scale` | `200` | 1..4000 px |
|  |  | `complexity` | `4` | inteiro 1..8 |
|  |  | `evolution` | `0` | -3600..3600 ° |
|  |  | `contrast` | `1` | 0..5 × |
|  |  | `brightness` | `0` | -1..1 % |
|  |  | `colorA` | `#000000` | cor |
|  |  | `colorB` | `#FFFFFF` | cor |
|  |  | `seed` | `0` | inteiro 0..99999 |
|  |  | `mode` | `"replace"` | `replace`, `add`, `multiply`, `screen`, `overlay` (estático) |
|  |  | `mix` | `1` | 0..1 % |
| `gradientRamp` (Rampa de gradiente) | Procedimental | `shape` | `"linear"` | `linear`, `radial` (estático) |
|  |  | `start` | `[0, -300]` | `[x, y]` px relativos à origem da camada |
|  |  | `end` | `[0, 300]` | `[x, y]` px relativos à origem da camada |
|  |  | `startColor` | `#000000` | cor |
|  |  | `endColor` | `#FFFFFF` | cor |
|  |  | `mode` | `"replace"` | `replace`, `add`, `multiply`, `screen`, `overlay` (estático) |
|  |  | `mix` | `1` | 0..1 % |
| `checkerboard` (Xadrez) | Procedimental | `size` | `64` | 1..4000 px |
|  |  | `angle` | `0` | -3600..3600 ° |
|  |  | `colorA` | `#FFFFFF` | cor |
|  |  | `colorB` | `#000000` | cor |
|  |  | `offset` | `[0, 0]` | `[x, y]` px relativos à origem da camada |
|  |  | `mode` | `"replace"` | `replace`, `add`, `multiply`, `screen`, `overlay` (estático) |
|  |  | `mix` | `1` | 0..1 % |
| `grid` (Grade) | Procedimental | `spacing` | `64` | 2..4000 px |
|  |  | `width` | `2` | 0..200 px |
|  |  | `color` | `#FFFFFF` | cor |
|  |  | `angle` | `0` | -3600..3600 ° |
|  |  | `offset` | `[0, 0]` | `[x, y]` px relativos à origem da camada |
|  |  | `mode` | `"replace"` | `replace`, `add`, `multiply`, `screen`, `overlay` (estático) |
|  |  | `mix` | `1` | 0..1 % |
| `chromaKey` (Chroma key) | Fosco, máscara e chave | `color` | `#00FF00` | cor |
|  |  | `tolerance` | `0.3` | 0..1 % |
|  |  | `softness` | `0.1` | 0..1 % |
|  |  | `spill` | `0.5` | 0..1 % |
|  |  | `choke` | `0` | -50..50 px |
| `lumaKey` (Luma key) | Fosco, máscara e chave | `threshold` | `0.1` | 0..1 % |
|  |  | `tolerance` | `0.1` | 0..1 % |
|  |  | `softness` | `0.05` | 0..1 % |
|  |  | `invert` | `false` | `true`/`false` (estático) |
| `spillSuppressor` (Supressor de spill) | Fosco, máscara e chave | `color` | `#00FF00` | cor |
|  |  | `amount` | `1` | 0..1 % |
| `matteChoker` (Ajuste de fosco) | Fosco, máscara e chave | `choke` | `2` | -100..100 px |
|  |  | `softness` | `1` | 0..100 px |
| `echo` (Eco) | Tempo e transição | `delay` | `0.1` | 0.01..5 s |
|  |  | `count` | `4` | inteiro 1..16 |
|  |  | `decay` | `0.6` | 0..1 % |
| `posterizeTime` (Posterizar tempo) | Tempo e transição | `fps` | `8` | 1..120 fps |
| `strobe` (Estroboscópio) | Tempo e transição | `frequency` | `4` | 0..60 Hz |
|  |  | `duty` | `0.5` | 0..1 % |
|  |  | `mode` | `"flash"` | `flash`, `hide` (estático) |
|  |  | `color` | `#FFFFFF` | cor — com `mode: "flash"` |
| `depthMap` (Mapa de profundidade) | Utilitário e expressão | `near` | `500` | 0..200000 px |
|  |  | `far` | `5000` | 0..200000 px |
|  |  | `gamma` | `1` | 0.1..5 |
|  |  | `invert` | `false` | `true`/`false` (estático) |
| `channelShuffle` (Trocar canais) | Utilitário e expressão | `red` | `"red"` | `red`, `green`, `blue`, `alpha`, `luma`, `zero`, `one` (estático) |
|  |  | `green` | `"green"` | `red`, `green`, `blue`, `alpha`, `luma`, `zero`, `one` (estático) |
|  |  | `blue` | `"blue"` | `red`, `green`, `blue`, `alpha`, `luma`, `zero`, `one` (estático) |
|  |  | `alpha` | `"alpha"` | `red`, `green`, `blue`, `alpha`, `luma`, `zero`, `one` (estático) |
| `alphaFromLuma` (Alfa pela luminância) | Utilitário e expressão | `invert` | `false` | `true`/`false` (estático) |
<!-- END effects-table -->

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
composição), `zoomIn`, `zoomOut`, `spin`, `blur`, `wipeLeft`, `wipeRight` (cortina sobre o conteúdo de camadas 2D; em grupos e camadas 3D viram um fade).
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

## 13. Exemplos

Os exemplos ficam em [`.claude/skills/knsproj/examples/`](../.claude/skills/knsproj/examples), junto da skill `/knsproj`:

- `basicos/` — um recurso por pasta (animadores de texto, trim path, máscaras, mattes, transições, efeitos, vídeo e
  áudio, grupos, câmera 3D, modelo GLB…), cada um com `project.json` curto e um `README.md`;
- `receitas/` — composições completas, como
  [`intro-logo-glow`](../.claude/skills/knsproj/examples/receitas/intro-logo-glow/project.json) (intro com halo,
  anel de loading e título) e `video-vertical-legenda-por-palavra` (1080×1920);
- `erros/` — projetos inválidos de propósito, com o código de erro esperado e o conserto.

Para gerar um arquivo importável a partir de qualquer exemplo:

```bash
python3 tools/knsproj validate .claude/skills/knsproj/examples/receitas/intro-logo-glow
python3 tools/knsproj pack .claude/skills/knsproj/examples/receitas/intro-logo-glow -o intro-logo-glow.knsproj
```
