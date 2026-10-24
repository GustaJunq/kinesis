# Referência de expressions do After Effects (para o motor do Kinesis)

Resultado da Tarefa 1 (pesquisa). Lista de funções, atributos e variáveis do After Effects (AE), agrupada por categoria, com
assinatura, o que faz e um exemplo curto. Serve de base para `expressions-compat.md` (auditoria) e `expressions-plan.md` (plano).

Pesquisado em 2026-10-24. Versão da referência: motor JavaScript do AE (a referência já cobre AE 26.0).

## Fontes (URLs usadas)

Referência oficial (espelho `ae-expressions.docsforadobe.dev`, mantido pela comunidade a partir da documentação da Adobe):

- https://ae-expressions.docsforadobe.dev/ (índice)
- https://ae-expressions.docsforadobe.dev/general/global/
- https://ae-expressions.docsforadobe.dev/general/time-conversion/
- https://ae-expressions.docsforadobe.dev/general/vector-math/
- https://ae-expressions.docsforadobe.dev/general/random-numbers/
- https://ae-expressions.docsforadobe.dev/general/interpolation/
- https://ae-expressions.docsforadobe.dev/general/color-conversion/
- https://ae-expressions.docsforadobe.dev/general/other-math/
- https://ae-expressions.docsforadobe.dev/objects/comp/ · `/objects/project/` · `/objects/footage/` · `/objects/camera/` · `/objects/light/`
- https://ae-expressions.docsforadobe.dev/objects/propertygroup/ · `/objects/effect/` · `/objects/mask/` · `/objects/property/`
- https://ae-expressions.docsforadobe.dev/objects/dropdown/ · `/objects/marker-property/` · `/objects/path-property/` · `/objects/key/` · `/objects/markerkey/`
- https://ae-expressions.docsforadobe.dev/layer/layer/ · `/layer/sub-objects/` · `/layer/general/` · `/layer/properties/` · `/layer/layer-space-transforms/` · `/layer/threed/`
- https://ae-expressions.docsforadobe.dev/text/text/ · `/text/sourcetext/` · `/text/style/` · `/text/variable-fonts/`
- https://ae-expressions.docsforadobe.dev/introduction/examples/ · `/introduction/resources/` · `/introduction/changelog/`

Adobe (a página principal devolveu HTTP 403 ao download direto; só o resumo de busca foi usado):

- https://helpx.adobe.com/after-effects/using/expression-controls.html (Expression Controls: Angle, Checkbox, Color, Layer, Point, Slider, Dropdown Menu)

Tutoriais e fóruns (fontes comunitárias, **não oficiais**):

- Inertial bounce: https://community.adobe.com/questions-529/inertial-bounce-expression-30984
- Inertial bounce (outras variações): https://photography.tutsplus.com/tutorials/how-to-animate-inertial-bounce-expressions-in-after-effects--cms-41469 · https://lesterbanks.com/?p=7004 (só resumo de busca)
- Expression Selector (`textIndex`, `textTotal`, `selectorValue`): https://dev.to/kocreative/after-effects-text-animation-and-the-expression-selector-a2l · https://creativecow.net/the-text-selector-expression-is-arguably-the-most-elusive-adobe-after-effects-feature-and-yet-its-its-most-powerful-text-feature/ (só resumo de busca)
- Audio reativo (Convert Audio to Keyframes): https://aejuice.com/blog/how-to-link-keyframes-to-audio-in-adobe-after-effects/ · https://community.adobe.com/t5/after-effects-discussions/make-music-reactive-elements-more-extreme/m-p/13629508 (só resumo de busca)
- Lista "6 expressions comuns" (Motion Array): https://blog.motionarray.com/learn/after-effects/6-common-after-effects-expressions-you-should-be-using/ — a página bloqueou o download (verificação anti-bot); só o resumo de busca foi usado.

## Convenção de status de confirmação

- **Confirmado** = visto na referência oficial listada acima.
- **Comunitário** = confirmado só em tutorial/fórum; a referência oficial não tem a entrada.
- **Não confirmado** = conhecido por uso geral, mas não encontrado nas fontes baixadas nesta pesquisa. Não use como verdade sem checar.

## Diferenças entre os dois motores do AE (importante para compatibilidade)

O AE tem o motor **JavaScript** (desde AE 16.0, 2018, ECMAScript moderno) e o antigo **Legacy ExtendScript** (ES3). O changelog diz que
os números de `random()` do motor JavaScript **não** coincidem com os do Legacy, e que o `posterizeTime()` e outros comportamentos
mudaram em 17.0. O alvo do Kinesis deve ser o motor JavaScript, que é o padrão atual. (Fonte: `/introduction/changelog/`.)

Lembretes de unidades do AE (relevantes para o Kinesis): posição em pixels a partir do canto superior esquerdo da comp, escala e opacidade
em %, rotação em graus, cor `[r,g,b,a]` de 0 a 1, tempo em segundos. O resultado da expression é a **última expressão avaliada**.

---

## 1. Global

| Item | Tipo / assinatura | O que faz | Exemplo curto |
|---|---|---|---|
| `time` | Number | Tempo da comp (s) em que a expression é avaliada | `time * 90` |
| `value` | mesmo tipo da propriedade | Valor da propriedade no instante atual (sem a expression) | `value + 10` |
| `colorDepth` | Number | Profundidade de cor do projeto (bits por canal) | `colorDepth` |
| `thisComp` | Comp | Comp que contém a expression | `thisComp.width` |
| `thisLayer` | Layer/Light/Camera | Camada da expression (opcional, é o objeto padrão) | `thisLayer.name` |
| `thisProject` | Project | Projeto atual | `thisProject.fullPath` |
| `thisProperty` | Property | Propriedade que contém a expression | `thisProperty.numKeys` |
| `comp(name)` | `comp(String) → Comp` | Outra composição pelo nome | `comp("Main").width` |
| `footage(name)` | `footage(String) → Footage` | Item de footage pelo nome | `footage("a.mp4").duration` |
| `posterizeTime(updatesPerSecond)` | `Number → Number` | Reduz a taxa de atualização abaixo do frame rate da comp | `posterizeTime(3); random(0,100)` |

## 2. Time Conversion

| Assinatura | Retorno | O que faz | Exemplo |
|---|---|---|---|
| `framesToTime(frames[, fps])` | Number | Frames → segundos (inverso de `timeToFrames`) | `framesToTime(12)` |
| `timeToFrames([t][, fps][, isDuration])` | Number | Segundos → frames inteiros; `t` padrão `time + thisComp.displayStartTime` | `timeToFrames(time)` |
| `timeToCurrentFormat([t][, fps][, isDuration][, ntscDropFrame])` | String | Tempo no formato de exibição do projeto | `timeToCurrentFormat()` (em Source Text) |
| `timeToTimecode([t][, timecodeBase=30][, isDuration])` | String | Timecode | `timeToTimecode(time)` |
| `timeToNTSCTimecode([t][, ntscDropFrame=false][, isDuration])` | String | Timecode NTSC | `timeToNTSCTimecode(time, true)` |
| `timeToFeetAndFrames([t][, fps][, framesPerFoot=16][, isDuration])` | String | Pés e frames de filme | `timeToFeetAndFrames(time)` |

## 3. Vector Math

Funções globais; aceitam dimensões diferentes (o resultado tem o tamanho do maior, faltantes = 0).

| Assinatura | Retorno | O que faz | Exemplo |
|---|---|---|---|
| `add(vec1, vec2)` | Array | Soma componente a componente | `add([10,20],[1,2,3])` → `[11,22,3]` |
| `sub(vec1, vec2)` | Array | Subtrai | `sub(position, [100,0])` |
| `mul(vec, amount)` | Array | Multiplica por escalar | `mul(value, 2)` |
| `div(vec, amount)` | Array | Divide por escalar | `div(value, 2)` |
| `clamp(value, limit1, limit2)` | Number/Array | Limita cada componente | `clamp(wiggle(0.5,500), 0, 500)` |
| `dot(vec1, vec2)` | Number | Produto escalar | `dot([1,0],[0,1])` |
| `cross(vec1, vec2)` | Array (2D/3D) | Produto vetorial | `cross([1,0,0],[0,1,0])` |
| `normalize(vec)` | Array | Comprimento 1 (`div(vec, length(vec))`) | `normalize([3,4])` |
| `length(vec[, point2])` | Number | Módulo, ou distância entre dois pontos | `length(position, pointOfInterest)` |
| `lookAt(fromPoint, atPoint)` | Array 3D | Orientação para o eixo z de `fromPoint` apontar a `atPoint` (luz/câmera) | `lookAt(position, thisComp.layer(1).position)` |

## 4. Random Numbers

| Assinatura | Retorno | O que faz | Exemplo |
|---|---|---|---|
| `random([maxValOrArray=1])` | Number/Array | Aleatório de 0 a máx; arrays por componente | `random(100)` |
| `random(minValOrArray, maxValOrArray)` | Number/Array | Aleatório entre mín e máx | `random([100,200],[300,400])` |
| `gaussRandom([maxValOrArray=1])` | Number/Array | Distribuição gaussiana (~90% dentro do intervalo) | `gaussRandom(100)` |
| `gaussRandom(minValOrArray, maxValOrArray)` | Number/Array | Idem, entre mín e máx | `gaussRandom(0, 100)` |
| `noise(valOrArray)` | Number (-1..1) | Ruído de Perlin; entrada Number ou array de 2 ou 3 | `rotation + 360*noise(time)` |
| `seedRandom(offset[, timeless=false])` | — | Muda a semente de `random`/`gaussRandom`; `timeless=true` ignora o tempo | `seedRandom(123456, true); random()*100` |

Nota: a semente padrão depende do id da camada, da propriedade, do tempo e do offset. O AE mudou `random()` no CC/CS6 (não afeta `wiggle`).

## 5. Interpolation

Todas retornam Number ou Array. Forma longa: `(t, tMin, tMax, value1, value2)`; forma curta: `(t, value1, value2)` (`t` de 0 a 1).

| Assinatura | O que faz | Exemplo |
|---|---|---|
| `linear(t, tMin, tMax, v1, v2)` / `linear(t, v1, v2)` | Interpolação linear, limitada nas pontas | `linear(time, 0, 6, 20, 80)` |
| `ease(t, tMin, tMax, v1, v2)` / `ease(t, v1, v2)` | Suavizada nas duas pontas (velocidade 0) | `ease(time, 0, 2, 0, 100)` |
| `easeIn(...)` | Tangente 0 só no lado `tMin`/`v1`; linear do outro | `easeIn(time, 0, 2, 0, 100)` |
| `easeOut(...)` | Tangente 0 só no lado `tMax`/`v2` | `easeOut(d, 0, .1, 100, 0)` |

## 6. Color Conversion

| Assinatura | Retorno | O que faz | Exemplo |
|---|---|---|---|
| `rgbToHsl(rgbaArray)` | Array 4D | RGBA (0..1) → HSLA (0..1) | `rgbToHsl(effect("Change Color")("Color To Change"))` (a página original omite os parênteses) |
| `hslToRgb(hslaArray)` | Array 4D | HSLA → RGBA | `hslToRgb([0.5,1,0.5,1])` |
| `hexToRgb(hexString)` | Array 4D | Hex de 6 ou 8 dígitos → RGBA 0..1 (AE 16.0+); aceita `#`, `0x` | `hexToRgb("#FF00FF")` → `[1,0,1,1]` |

## 7. Other Math

| Assinatura | Retorno | O que faz | Exemplo |
|---|---|---|---|
| `degreesToRadians(degrees)` | Number | Graus → radianos | `degreesToRadians(180)` |
| `radiansToDegrees(radians)` | Number | Radianos → graus | `radiansToDegrees(Math.PI)` |

O objeto `Math` do JavaScript (`Math.sin`, `Math.PI`, `Math.floor`, `Math.exp`...) também é usado em quase todo tutorial (veja inertial bounce).

## 8. Comp (`thisComp` / `comp("x")`)

| Item | Tipo | O que faz | Exemplo |
|---|---|---|---|
| `width`, `height` | Number | Dimensão em pixels | `[thisComp.width/2, thisComp.height/2]` |
| `duration` | Number | Duração (s) | `thisComp.duration` |
| `frameDuration` | Number | Duração de um frame (s) | `time / thisComp.frameDuration` |
| `displayStartTime` | Number | Tempo inicial da comp (s) | — |
| `name` | String | Nome da comp | — |
| `numLayers` | Number | Número de camadas | — |
| `pixelAspect` | Number | Proporção do pixel | — |
| `bgColor` | Array 4D | Cor de fundo | — |
| `shutterAngle`, `shutterPhase` | Number | Obturador (graus) | — |
| `ntscDropFrame` | Boolean | Timecode drop-frame (CS5.5+) | — |
| `activeCamera` | Camera | Câmera que renderiza o frame atual | `thisComp.activeCamera.zoom` |
| `marker` | Marker Property | Marcadores da comp (acesso por nome via `marker.key(name)`) | `thisComp.marker.key(1).time` |
| `layer(index)` / `layer(name)` | Layer/Light/Camera | Camada por índice ou nome (a mais alta em caso de nome repetido) | `thisComp.layer("Solid 1")` |
| `layer(otherLayer, relIndex)` | Layer | Camada `relIndex` acima/abaixo de `otherLayer` | `thisComp.layer(thisLayer, -1)` |
| `layerByComment(comment)` | Layer | Camada cujo comentário contém o texto (distingue maiúsculas) | `thisComp.layerByComment("Control")` |

## 9. Layer

### 9.1 Gerais (`/layer/general/`)

| Item | Tipo | O que faz | Exemplo |
|---|---|---|---|
| `active` | Boolean | Vídeo ligado e tempo entre In e Out | `thisComp.layer(thisLayer,1).active` |
| `audioActive` | Boolean | Áudio ligado e tempo entre In e Out | — |
| `enabled` | Boolean | Chave de vídeo ligada | — |
| `hasAudio`, `hasVideo` | Boolean | Tem áudio / vídeo | — |
| `hasParent` | Boolean | Tem pai | `if (hasParent) parent.index` |
| `parent` | Layer | Camada pai | `position[0] + parent.width` |
| `index` | Number | Índice na comp | `index` |
| `inPoint`, `outPoint` | Number | In/Out (s) | `time - inPoint` |
| `startTime` | Number | Início da camada (s) | — |
| `width`, `height` | Number | Dimensão da camada (px) | — |
| `sampleImage(point[, radius=[0.5,0.5]][, postEffect=true][, t=time])` | Array 4D | Cor RGBA média ponderada por alfa numa região | `thisComp.layer(1).sampleImage([100,100],[2,1.5])` |

### 9.2 Sub-objetos (`/layer/sub-objects/`)

| Item | Retorno | O que faz | Exemplo |
|---|---|---|---|
| `source` | Comp/Footage | Fonte da camada (tempo ajustado ao da fonte) | `source.layer(1).position` |
| `effect(name)` / `effect(index)` | Effect | Efeito aplicado à camada | `thisLayer.effect("Fast Blur")` |
| `mask(name)` / `mask(index)` | Mask | Máscara | `thisLayer.mask("Mask 1")` |
| `sourceRectAtTime([t=time][, includeExtents=false])` | `{top,left,width,height}` | Caixa delimitadora da camada/fonte (AE 13.2+; texto de parágrafo 15.1+) | `myTextLayer.sourceRectAtTime().width` |
| `sourceTime([t=time])` | Number | Tempo da fonte correspondente ao tempo da comp (CS5.5+) | `sourceTime(time)` |

### 9.3 Propriedades (`/layer/properties/`)

| Item | Tipo | O que faz |
|---|---|---|
| `anchorPoint` | Array 2D/3D | Ponto de ancoragem (espaço da camada) |
| `position` | Array 2D/3D | Posição (espaço mundo sem pai; espaço do pai se houver) |
| `scale` | Array | Escala em % |
| `rotation` | Number | Rotação em graus (z em 3D) |
| `opacity` | Number | Opacidade em % |
| `audioLevels` | Array 2D | Níveis em dB (a propriedade, **não** a amplitude do áudio) |
| `timeRemap` | Number | Remapeamento de tempo (s), se ativo |
| `marker` | Marker Property | Marcadores da camada |
| `name` | String | Nome da camada |

Exemplo: `thisComp.layer("A").position.valueAtTime(time - .5)`. Também há acesso pelo grupo `transform` (ex.: `transform.position`) — **não confirmado** na referência baixada.

### 9.4 Transformações de espaço (`/layer/layer-space-transforms/`)

Todas têm `t` opcional (padrão `time`).

| Assinatura | O que faz | Exemplo |
|---|---|---|
| `toComp(point[, t])` | Ponto do espaço da camada → espaço da comp | `toComp(anchorPoint)` |
| `fromComp(point[, t])` | Comp → camada (em 3D, z pode ser ≠ 0) | `fromComp(thisComp.layer(2).position)` |
| `toWorld(point[, t])` | Camada → espaço mundo (independe da vista) | `toWorld.effect("Bulge")("Bulge Center")` |
| `fromWorld(point[, t])` | Mundo → camada | `fromWorld(thisComp.layer(2).position)` |
| `toCompVec(vec[, t])` | Vetor camada → comp | `toCompVec([1,0])` |
| `fromCompVec(vec[, t])` | Vetor comp → camada | `fromCompVec(sub(position, thisComp.layer(2).position))` |
| `toWorldVec(vec[, t])` | Vetor camada → mundo | `toWorldVec([0,0,1])` |
| `fromWorldVec(vec[, t])` | Vetor mundo → camada | — |
| `fromCompToSurface(point[, t])` | Projeta ponto da comp na superfície z=0 da camada 3D | — |

### 9.5 3D (`/layer/threed/`)

| Item | Tipo | O que faz |
|---|---|---|
| `orientation` | Array 3D | Orientação (graus) |
| `rotationX`, `rotationY`, `rotationZ` | Number | Rotações (graus) |
| `acceptsLights` | Boolean/Number | 1 se aceita luzes |
| `acceptsShadows` | Number | 1 aceita, 2 = "Only" |
| `castsShadows` | Number | 1 projeta, 2 = "Only" |
| `lightTransmission` | Number | Transmissão de luz |
| `ambient`, `diffuse`, `specular`, `shininess`, `metal` | Number | Material em % |

Exemplo (introdução): `if (toCompVec([0, 0, 1])[2] > 0 ) value else 0` (invisível quando de costas para a câmera).

## 10. Property (`/objects/property/`)

### Atributos

| Item | Tipo | O que faz | Exemplo |
|---|---|---|---|
| `value` | Number/Array/String | Valor no instante atual | `thisProperty.value` |
| `name` | String | Nome da propriedade | — |
| `numKeys` | Number | Quantidade de keyframes (ou marcadores) | `numKeys` |
| `propertyIndex` | Number | Índice entre irmãos | — |
| `speed` | Number | Velocidade espacial positiva (só propriedades espaciais) | `speed` |
| `velocity` | Number/Array | Velocidade temporal no instante atual | `velocity` |

### Métodos

| Assinatura | Retorno | O que faz | Exemplo |
|---|---|---|---|
| `valueAtTime(t)` | Number/Array | Valor em `t` | `thisComp.layer(thisLayer,-1).position.valueAtTime(time - .5)` |
| `velocityAtTime(t)` | Number/Array | Velocidade temporal em `t` | `velocityAtTime(key(1).time)` |
| `speedAtTime(t)` | Number | Velocidade espacial em `t` | — |
| `wiggle(freq, amp[, octaves=1][, amp_mult=0.5][, t=time])` | Number/Array | Tremor aleatório | `position.wiggle(5, 20, 3, 0.5)` |
| `temporalWiggle(freq, amp[, octaves=1][, amp_mult=0.5][, t=time])` | Number/Array | Amostra a propriedade em tempo deslocado aleatoriamente (precisa estar animada) | `scale.temporalWiggle(5, 0.2)` |
| `smooth([width=.2][, samples=5][, t=time])` | Number/Array | Filtro de média (box) no tempo | `position.smooth(0.1, 5)` |
| `loopOut([type="cycle"][, numKeyframes=0])` | mesmo tipo | Repete segmento medido do último keyframe para trás | `loopOut("pingpong")` |
| `loopIn([type="cycle"][, numKeyframes=0])` | mesmo tipo | Idem, antes do primeiro keyframe | `loopIn("cycle", 3)` |
| `loopOutDuration([type][, duration=0])` | mesmo tipo | Loop por duração (s) em vez de nº de keyframes | `loopOutDuration("cycle", 1)` |
| `loopInDuration([type][, duration=0])` | mesmo tipo | Idem, para o início | `loopInDuration("cycle", 1)` |
| `key(index)` / `key(markerName)` | Key/MarkerKey | Keyframe ou marcador | `key(numKeys).time` |
| `nearestKey(t)` | Key/MarkerKey | Keyframe mais próximo de `t` | `nearestKey(time).index` |
| `nextKey(time)` / `previousKey(time)` | Key/MarkerKey | Seguinte / anterior (**AE 26.0+**, só motor JS) | `marker.previousKey(time).time` |
| `propertyGroup([countUp=1])` | PropertyGroup | Sobe na hierarquia | `thisProperty.propertyGroup(1).numProperties` |

Tipos de loop: `"cycle"` (padrão), `"pingpong"`, `"offset"`, `"continue"` (sem argumento de keyframe/duração).
Exemplo útil: `loopIn("continue") + loopOut("continue") - value` (os dois incluem o valor atual).

### Key / MarkerKey / Marker Property

| Objeto | Item | O que faz |
|---|---|---|
| Key | `index`, `time`, `value` | Índice, tempo e valor do keyframe. Ex.: `position.key(3).value` |
| Marker Property | `numKeys`, `key(index\|name)`, `nearestKey(t)` | Marcadores da comp (`thisComp.marker`) ou camada (`thisLayer.marker`) |
| MarkerKey | `time`, `index`, `duration`, `comment`, `chapter`, `url`, `frameTarget`, `cuePointName`, `eventCuePoint`, `parameters["nome"]`, `protectedRegion` | Dados do marcador. Ex.: `thisComp.marker.nearestKey(time).parameters["background color"]` |

### Dropdown Menu (`/objects/dropdown/`, AE 26.0+, só motor JS)

`items` (Array de String), `text` (String ativa), `textAtTime(time)`. Para lógica, a Adobe recomenda usar o **índice** (`.value`), não o texto.

## 11. Effect e Expression Controls

| Item | O que faz | Exemplo |
|---|---|---|
| `effect(name)` / `effect(index)` | Efeito da camada | `effect("Bulge")` |
| `Effect.active` | Boolean: efeito ligado | `effect("Bulge").active` |
| `Effect.param(name\|index)` | Propriedade do efeito. Atalho `effect("X")("Param")` | `effect("Bulge").param("Bulge Height")` |

Expression Controls (efeitos da categoria "Expression Controls": **Angle, Checkbox, Color, Layer, Point, Slider, Dropdown Menu** — Adobe;
o "3D Point" **não confirmado** nas fontes). Nome da propriedade interna usada em `effect("Nome")("Propriedade")`:

| Controle | Propriedade | Status |
|---|---|---|
| Slider Control | `"Slider"` | Confirmado (resultado de busca da Adobe e tutoriais: `effect("Slider Control")("Slider")`) |
| Dropdown Menu Control | `"Menu"` | Parcial (página de Dropdown cita um controle chamado "Menu"; o nome da propriedade em si **não confirmado**) |
| Checkbox Control | `"Checkbox"` | Não confirmado |
| Color Control | `"Color"` | Não confirmado |
| Point Control | `"Point"` | Não confirmado |
| Angle Control | `"Angle"` | Não confirmado |
| Layer Control | `"Layer"` | Não confirmado |

Uso típico (confirmado como padrão): `thisComp.layer("Null 1").effect("Slider Control")("Slider")`; o controle fica numa camada nula e
vários layers leem dele.

## 12. Path Property (`/objects/path-property/`) e Mask

| Assinatura | Retorno | O que faz |
|---|---|---|
| `points([t=time])` | Array de `[x,y]` | Vértices do caminho (coordenadas dependem do tipo: máscara = canto sup. esq. da camada; shape = âncora do grupo) |
| `inTangents([t=time])` / `outTangents([t=time])` | Array de `[x,y]` | Tangentes, como **deslocamentos** do vértice |
| `isClosed()` | Boolean | Caminho fechado |
| `pointOnPath([percentage=0.5][, t=time])` | `[x,y]` | Ponto por % do comprimento de arco |
| `tangentOnPath([percentage=0.5][, t=time])` | `[x,y]` | Tangente unitária nesse ponto |
| `normalOnPath([percentage=0.5][, t=time])` | `[x,y]` | Normal unitária |
| `createPath(points=[[0,0],[100,0],[100,100],[0,100]], inTangents=[], outTangents=[], is_closed=true)` | Path | Cria um caminho (a saída de `points()` etc. pode ser reaproveitada) |

Exemplo: `createPath(points(), inTangents(), outTangents(), false)` abre o caminho.

Mask (`thisLayer.mask("Mask 1")`): `invert` (Boolean), `maskExpansion` (px), `maskFeather` (px), `maskOpacity` (%), `maskPath` (Path; pode ser
ligado a outros caminhos, **não** manipulado numericamente).

## 13. Footage (`footage("x")` ou `thisLayer.source`)

Atributos: `width`, `height`, `duration`, `frameDuration`, `pixelAspect`, `name`, `ntscDropFrame` (CS5.5+), `sourceData`, `sourceText` (JSON).
Métodos (MGJSON/JSON): `dataValue(dataPath)`, `dataKeyCount(dataPath)`, `dataKeyTimes(dataPath[, t0][, t1])`, `dataKeyValues(dataPath[, t0][, t1])`.
Exemplo: `footage("sample.json").sourceData[0].Color`.

## 14. Project, Camera, Light

- **Project**: `thisProject.bitsPerChannel`, `thisProject.fullPath`, `thisProject.linearBlending`.
- **Camera** (`thisLayer.cameraOption.*`): `active`, `aperture`, `blurLevel`, `depthOfField`, `focusDistance`, `highlightGain`,
  `highlightSaturation`, `highlightThreshold`, `irisAspectRatio`, `irisDiffractionFringe`, `irisRotation`, `irisRoundness`, `irisShape`,
  `pointOfInterest`, `zoom`. Exemplo: `cam = thisComp.activeCamera; distance = length(sub(position, cam.position)); scale * distance / cam.zoom;`
- **Light** (`thisLayer.lightOption.*`): `castsShadows`, `color`, `coneAngle`, `coneFeather`, `falloff`, `falloffDistance`, `intensity`,
  `pointOfInterest`, `radius`, `shadowDarkness`, `shadowDiffusion`.
- **PropertyGroup**: `name`, `numProperties`, `propertyIndex`, `propertyGroup([countUp])`. Ex.: `thisLayer("ADBE Effect Parade").numProperties`.

## 15. Text

### 15.1 Source Text (`text.sourceText`, objeto desde AE 17.0)

| Item | Tipo | O que faz |
|---|---|---|
| `text.sourceText` | String / SourceText | Texto da camada |
| `.style` | TextStyle | Estilo (equivale a `getStyleAt(0,0)`) |
| `.createStyle()` | TextStyle | Estilo vazio para preencher. Ex.: `text.sourceText.createStyle().setFontSize(300).setFont("Impact")` |
| `.getStyleAt(charIndex[, time])` | TextStyle | Estilo de um caractere |
| `.isPointText`, `.isParagraphText`, `.isHorizontalText`, `.isVerticalText` | Boolean | Tipo de texto (AE 25.0+) |

### 15.2 TextStyle (atributos de leitura)

`applyFill`, `applyStroke`, `baselineShift`, `fillColor` (RGB 0..1), `font`, `fontSize`, `isAllCaps`, `isAutoLeading`, `isFauxBold`,
`isFauxItalic`, `isSmallCaps`, `leading`, `strokeColor`, `strokeWidth`, `tracking`. Novos em AE 25.0: `baselineDirection`, `baselineOption`,
`digitSet`, `direction`, `firstLineIndent`, `horizontalScaling`, `isEveryLineComposer`, `isHangingRoman`, `isLigature`, `justification`, `kerning`,
`kerningType`, `leadingType`, `leftMargin`, `lineJoin`, `rightMargin`, `spaceAfter`, `spaceBefore`, `tsume`, `verticalScaling`.

Setters encadeáveis (retornam o TextStyle): `setFont`, `setFontSize`, `setFillColor`, `setStrokeColor`, `setStrokeWidth`, `setApplyFill`,
`setApplyStroke`, `setTracking`, `setLeading`, `setAutoLeading`, `setBaselineShift`, `setAllCaps`, `setSmallCaps`, `setFauxBold`, `setFauxItalic`,
`setText(value)`, e (25.0) `replaceText(value[, start, n])`, `setHorizontalScaling`, `setVerticalScaling`, `setLigature`, `setLineJoin`,
`setKerning`, `setKerningType`, `setTsume`, `setJustification`, `setDirection`, `setLeadingType`, `setLeftMargin`, `setRightMargin`,
`setSpaceBefore`, `setSpaceAfter`, `setFirstLineIndent`, `setEveryLineComposer`, `setHangingRoman`, `setBaselineDirection`, `setBaselineOption`, `setDigitSet`.
Exemplo: `text.sourceText.style.setFont("Times New Roman").setFontSize(42).setText("Novo texto");`

### 15.3 Variable fonts (AE 26.0+)

`text.animator("Animator 1").property.fontAxisWght` (e `Wdth`, `Slnt`, `Ital`, `Opsz`; forma geral `fontAxis[Tag]`). Exemplo: `weight = text.animator("Animator 1").property.fontAxisWght; weight / 10;`

### 15.4 Variáveis do Expression Selector (animador de texto) — **comunitário**

A referência oficial baixada **não** documenta estas variáveis; elas aparecem em tutoriais e fóruns (dev.to/kocreative, Creative COW):

| Variável | O que é | Exemplo |
|---|---|---|
| `textIndex` | Índice (a partir de **1**) do caractere/palavra/linha em avaliação | `seedRandom(textIndex, true)` |
| `textTotal` | Total de unidades | `textIndex / textTotal` |
| `selectorValue` | Valor do Amount do seletor (100 se não há seletor anterior). Se a expression não o usar, seletores anteriores do mesmo animador não têm efeito | expression padrão: `selectorValue * textIndex/textTotal` |

A expression roda **uma vez por unidade, por frame**. `textIndex` e `textTotal` dependem da opção "Based On" (caracteres, palavras, linhas).

## 16. Padrões dos tutoriais mais usados (receitas)

Cada receita usa apenas elementos listados acima. Marca "(oficial)" = vem da referência/exemplos oficiais; "(comunitário)" = tutorial.

| Receita | Expression | Depende de |
|---|---|---|
| Wiggle (oficial) | `wiggle(5, 20)` ; um eixo: `w = wiggle(3,50); [value[0], w[1]]` | `wiggle`, `value` |
| Loop (oficial) | `loopOut("cycle")`, `loopOut("pingpong")`, `loopOut("offset")` | keyframes |
| Tempo (comunitário) | `time * 90` (rotação contínua) | `time` |
| Inertial bounce (comunitário, Adobe Community) | veja abaixo | `numKeys`, `nearestKey`, `key`, `velocityAtTime`, `Math.sin/exp` |
| Seguir com atraso (oficial) | `thisComp.layer(thisLayer, -1).position.valueAtTime(time - .5)` | `thisComp.layer(layer, rel)`, `.position`, `valueAtTime` |
| Entre duas camadas (oficial) | `(thisComp.layer(1).position + thisComp.layer(2).position)/2` | operadores sobre arrays, `.position` |
| Círculo (oficial) | `[(thisComp.width/2), (thisComp.height/2)] + [Math.sin(time)*50, -Math.cos(time)*50]` | `Math` |
| Wiggle com janela (oficial) | `timeToStart=2; timeToStop=4; if ((time>timeToStart)&&(time<timeToStop)) { wiggle(3,25) } else { value }` | `if`, `wiggle` |
| Slider de controle (oficial/Adobe) | `thisComp.layer("Null 1").effect("Slider Control")("Slider")` | `effect()`, controles |
| Áudio reativo (comunitário) | `t = thisComp.layer("Audio Amplitude").effect("Both Channels")("Slider"); v = linear(t, 2, 18, 20, 200); [v, v]` | `effect()`, `linear` |
| Marcadores (oficial) | `m1 = thisLayer.marker.key("Start").time; m2 = thisLayer.marker.key("End").time; linear(time, m1, m2, 0, 100)` | `marker.key(nome)` |
| Flash por keyframe (oficial) | `d = Math.abs(time - nearestKey(time).time); easeOut(d, 0, .1, 100, 0)` | `nearestKey` bare |
| Texto: cascata (comunitário) | Amount: `selectorValue * textIndex/textTotal` ; atraso com `ease()`/`linear()` por `textIndex` | `textIndex`, `textTotal`, `selectorValue` |
| Distância da câmera (oficial) | `cam = thisComp.activeCamera; length(sub(position, cam.position))` | `activeCamera`, `length` |
| Mirar (oficial) | `lookAt(position, thisComp.layer(1).position)` | `lookAt` |
| Auto-tamanho de fundo de texto (comunitário, uso geral) | `r = thisComp.layer("Texto").sourceRectAtTime(); [r.width + 40, r.height + 40]` | `sourceRectAtTime` |

Inertial bounce (comunitário, <https://community.adobe.com/questions-529/inertial-bounce-expression-30984>), citado como está na fonte:

```javascript
n = 0;
if (numKeys > 0) {
  n = nearestKey(time).index;
  if (key(n).time > time){
    n--;
  }
}
if (n == 0) {
  t = 0;
} else {
  t = time - key(n).time;
}
if (n > 0){
  v = velocityAtTime(key(n).time - thisComp.frameDuration/10);
  amp = 5;
  freq = 1.0;
  decay = 5.0;
  value + (v/100)*amp*Math.sin(freq*t*2*Math.PI)/Math.exp(decay*t);
}else{
  value;
}
```

Nota da própria thread: `/ Math.exp(decay*t)` pode estourar com `t` grande; a variante estável é multiplicar por `Math.exp(-decay*t)`.

## 17. Itens não confirmados / lacunas da pesquisa

- Nomes internos das propriedades dos controles Checkbox, Color, Point, Angle, Layer (veja a seção 11) — a página da Adobe devolveu 403.
- Se o acesso `thisLayer.transform.position` existe como atalho documentado — **não confirmado** (a referência lista `position` direto na camada).
- "Convert Audio to Keyframes": a camada nula é chamada "Audio Amplitude" com os efeitos "Left Channel", "Right Channel" e "Both Channels", cada
  um com a propriedade "Slider" (fonte comunitária AEJuice). A referência oficial das expressions não descreve isso.
- Lista da Motion Array: não foi possível ler (anti-bot); nenhuma expression foi tirada dela.
- `Footage.sourceData`/MGJSON, `Layer.sampleImage`, `thisProject.*`, `timeTo*` etc. foram listados por completeza, mas têm pouco uso em tutoriais de motion design.
