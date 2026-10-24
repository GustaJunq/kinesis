# Compatibilidade de expressions: Kinesis × After Effects

Resultado da Tarefa 2 (auditoria). Cada item da referência do AE (`expressions-ae-reference.md`) com o estado no Kinesis, lido no código em
**2026-10-24** (branch `ccr-dd514b35-umfesv`, formato `.knsproj` v11). Não foi rodado nenhum teste novo: o estado vem da leitura do código
e dos testes existentes (`core/src/test/kotlin/dev/kinesis/core/expr/`).

## Fontes (URLs)

As mesmas de `expressions-ae-reference.md` (referência oficial em https://ae-expressions.docsforadobe.dev/ e as páginas comunitárias listadas lá).
Documento interno consultado: `docs/expressoes.md` (matriz anterior do Kinesis); onde divergiu do código, vale o código.

## Onde o código está

| Arquivo | Papel |
|---|---|
| `core/src/main/kotlin/dev/kinesis/core/expr/ExpressionEngine.kt` | Motor: Rhino 1.7.15 interpretado, limites, cache por texto, variáveis (`time`, `value`, `index`, `inPoint`, `outPoint`, `textIndex`, `textTotal`, `selectorValue`), funções do host (`__rand`, `__noise`, `__valueAt`, `__key`, `__layerInfo`) |
| `core/src/main/kotlin/dev/kinesis/core/expr/Prelude.kt` | Biblioteca do AE escrita em JS (vetores, `linear/ease*`, `random`, `wiggle`, `loopOut/In`, `thisComp`, `thisLayer`, `thisProperty`) |
| `core/src/main/kotlin/dev/kinesis/core/expr/ExpressionResolver.kt` | Liga expressions às propriedades da camada, converte unidades AE ↔ Kinesis (`Codec`), `layerInfo`, `diagnose` |
| `core/src/main/kotlin/dev/kinesis/core/expr/TextExpression.kt` | Animadores de texto por caractere/palavra/linha (seletor de expressão) |
| `core/src/main/kotlin/dev/kinesis/core/anim/Animatable.kt` | Campos `expression` e `expressionEnabled` de toda propriedade animável |
| `core/src/main/kotlin/dev/kinesis/core/edit/Props.kt` | Lista de propriedades (`LayerProps.all`) que aceitam expression |
| `core/src/main/kotlin/dev/kinesis/core/format/Serializers.kt`, `ProjectValidator.kt` | Gravação/leitura em `.knsproj` e validação de sintaxe |
| `app/src/main/kotlin/dev/kinesis/editor/ui/editor/ExpressionUi.kt` | Editor, presets, destaque de sintaxe (sem autocompletar) |

## Legenda

- **Sim** = existe e é equivalente ao AE no que a referência descreve. **Parcial** = existe com diferença. **Não** = não existe (a expression falha com
  "... is not defined"/erro; a propriedade usa o valor sem expression e o editor mostra o aviso).
- Coluna **Texto**: `T` = específico de animador de texto (só existe dentro do seletor de expressão); `—` = geral.
- "Prelude" = `expr/Prelude.kt`; "Engine" = `expr/ExpressionEngine.kt`.

## Resumo: os 10 buracos que mais quebram "copiar e colar do AE"

1. **Operadores em arrays não funcionam** (`position + [10,0]`, `(a.position + b.position)/2`, `[w/2,h/2] + [sin*50, -cos*50]`).
   Os exemplos oficiais do AE usam isso (introdução → exemplos 2, 3, 4). No Kinesis só `add/sub/mul/div` (documentado em `docs/expressoes.md`).
2. **`position`, `scale`, `rotation`, `opacity`, `anchorPoint` soltos ou via `thisLayer.position` não existem.** Só há `thisLayer.transform.position`
   (valor, não propriedade). Isso quebra `position.wiggle(5,20)`, `thisComp.layer("x").position.valueAtTime(...)`, e "seguir com atraso".
3. **`numKeys`, `nearestKey()`, `velocity`, `speed`, `name` soltos não existem** (só em `thisProperty.`). O **inertial bounce** clássico usa `numKeys` e `nearestKey` soltos e **falha**.
4. **`effect("X")("Y")` e Expression Controls não existem** (não há sequer os efeitos de controle). Bloqueia o fluxo mais comum do AE (sliders numa camada nula).
5. **Camadas de outras camadas só devolvem a transformação como número** (sem expressions delas, sem `valueAtTime`, sem efeitos).
6. **Só propriedades numéricas/vetoriais/cor aceitam expression** (e o `amount` do seletor). Texto (`sourceText`), caminhos, booleanos, escolhas e campos estáticos (ex.: `lineHeight`, `align`, `maxWidth`) não.
7. **Não há áudio acessível** (nem `Audio Amplitude`, nem análise de bandas). Não existe análise de áudio no projeto (nenhum código de amplitude/pico).
8. **`toComp/fromComp/toWorld/fromWorld`, `sourceRectAtTime`, `lookAt`, `parent`** ausentes.
9. **`gaussRandom`, `noise`, `smooth`, `temporalWiggle`, `hexToRgb`/`rgbToHsl`/`hslToRgb`, `timeToFrames`/`framesToTime`** ausentes.
10. **Sem autocompletar** no app; erros de sintaxe só ao aplicar/validar, não em tempo real.

---

## 1. Global

| Item AE | Status | Onde / observação | Texto |
|---|---|---|---|
| `time` | Sim | Engine `install()`: segundos da comp (do grupo dentro de grupo) | — |
| `value` | Sim | Engine; valor da propriedade sem a expression (keyframes interpolados) | — |
| `colorDepth` | Não | — | — |
| `thisComp` | Parcial | Prelude. Veja seção 8 | — |
| `thisLayer` | Parcial | Prelude `__mkLayer`. Veja seção 9 | — |
| `thisProject` | Não | — | — |
| `thisProperty` | Parcial | Prelude: só `value`, `numKeys`, `valueAtTime`, `key`, `velocityAtTime`, `nearestKey`, `loopOut`, `loopIn`, `wiggle` | — |
| `comp(name)` | Não | O modelo tem uma composição por projeto; grupos fazem o papel de precomps | — |
| `footage(name)` | Não | — | — |
| `posterizeTime(n)` | Parcial | Prelude: reatribui a variável `time` do restante da expression; não afeta `valueAtTime`/`wiggle(…, t)` | — |
| Variáveis soltas `index`, `inPoint`, `outPoint` | Sim | Engine `install()`. `index` = 1 na camada do topo do contêiner | — |
| Variáveis soltas `name`, `width`, `height`, `position`, `scale`, `rotation`, `opacity`, `anchorPoint`, `parent`, `numKeys`, `velocity`, `speed`, `marker` | Não | No AE todas valem como "this layer/property". **Maior causa de erro ao colar** | — |

## 2. Time Conversion

| Item AE | Status | Observação |
|---|---|---|
| `framesToTime`, `timeToFrames`, `timeToCurrentFormat`, `timeToTimecode`, `timeToNTSCTimecode`, `timeToFeetAndFrames` | Não | Triviais em JS (`frameDuration` já existe em `thisComp`). `timeToFrames` é comum em tutoriais |

## 3. Vector Math

| Item AE | Status | Onde / observação |
|---|---|---|
| `add`, `sub` | Sim | Prelude (`__m2`: aceita vetor-vetor, vetor-escalar, tamanhos diferentes com 0) |
| `mul`, `div` | Sim | Prelude (superconjunto do AE, que só aceita escalar) |
| `clamp` | Sim | Prelude, elemento a elemento |
| `dot` | Sim | Prelude (trunca no menor tamanho) |
| `cross` | Parcial | Prelude: só 3D (2D devolve `NaN` e o motor rejeita o resultado) |
| `normalize` | Sim | Prelude (vetor nulo devolve ele mesmo) |
| `length(vec)`, `length(a, b)` | Sim | Prelude |
| `lookAt` | Não | — |
| **Operadores `+ - * /` em arrays** | Não | Limite do JS puro. Veja o plano (transformação do código-fonte) |

## 4. Random Numbers

| Item AE | Status | Onde / observação |
|---|---|---|
| `random()`, `random(max)`, `random(min,max)` com arrays | Sim (semântica) | Prelude + Engine (`SplitMix`, determinístico por camada/propriedade/frame). Os números **não** são os do AE (o gerador do AE não é público; o próprio AE mudou entre motores) |
| `seedRandom(offset, timeless)` | Sim (semântica) | Prelude `__seed` |
| `gaussRandom` (2 formas) | Não | — |
| `noise(x)` do AE (Perlin 1/2/3D) | Não | O Engine tem `__noise` 1D interno só para o `wiggle`; não exposto como `noise()` |
| `Math.random()` | Sim | Redirecionado para `random()` (determinístico) |

## 5. Interpolation

| Item AE | Status | Onde / observação |
|---|---|---|
| `linear` (2 formas) | Sim | Prelude `__interp(-1)`; vetores elemento a elemento |
| `ease` (2 formas) | Parcial | Prelude: `u²(3−2u)`. As curvas exatas do AE não foram comparadas |
| `easeIn`, `easeOut` (2 formas) | Parcial | Prelude: cúbicas com tangente 0 só em um lado. Idem |

## 6. Color Conversion

| Item AE | Status | Observação |
|---|---|---|
| `rgbToHsl`, `hslToRgb`, `hexToRgb` | Não | Cor chega à expression como `[r,g,b,a]` 0..1 (Codec `ColorKind`), então a base existe |

## 7. Other Math

| Item AE | Status | Onde |
|---|---|---|
| `degreesToRadians`, `radiansToDegrees` | Sim | Prelude |
| `Math.*` (JS) | Sim | `Math` do Rhino (ES6 parcial); `Math.random` redirecionado |
| `Date` | Removido de propósito (determinismo) | O AE tem `Date`, mas é raro em motion design |

## 8. Comp

| Item AE | Status | Onde / observação |
|---|---|---|
| `thisComp.width`, `height`, `duration`, `frameDuration`, `numLayers` | Sim | Prelude `thisComp` (+ Engine `__comp`). `numLayers` = camadas do mesmo contêiner |
| `displayStartTime` | Parcial | Fixo em `0` |
| `name` | Parcial | Fixo em `"Comp"` (valor errado se a comp tem outro nome) |
| `layer(index)`, `layer(name)` | Parcial | Resolve entre as camadas **do mesmo contêiner** (`ExpressionResolver.lookup`); devolve só `name/index/inPoint/outPoint/startTime/transform.*` |
| `layer(otherLayer, relIndex)` | Não | Usado no clássico "seguir a camada de cima com atraso" |
| `layerByComment` | Não | O modelo tem `Layer.label` mas não comentário |
| `marker`, `activeCamera`, `bgColor`, `pixelAspect`, `shutterAngle`, `shutterPhase`, `ntscDropFrame` | Não | Existem marcadores no projeto (`KnsProject.markers`, `edit/Markers.kt`), só não são expostos |

## 9. Layer

| Item AE | Status | Onde / observação |
|---|---|---|
| `name`, `index`, `inPoint`, `outPoint`, `startTime` (`thisLayer.` / `thisComp.layer(x).`) | Parcial | Prelude `__mkLayer`. `startTime` = `inPoint` (o modelo só tem `layer.start`) |
| `transform.position/scale/rotation/opacity` | Parcial | Só como **valor** no instante atual, 2D (no 3D só x,y). Sem expressions da outra camada, sem keyframes |
| `position`, `scale`, `rotation`, `opacity`, `anchorPoint` como **propriedade** (`.value`, `.valueAtTime`, `.wiggle`, `.key`) | Não | A causa nº 2 da lista de buracos |
| `width`, `height`, `hasParent`, `parent` | Não | O modelo tem `Layer.parent` (id) |
| `active`, `enabled`, `hasAudio`, `hasVideo`, `audioActive` | Não | — |
| `effect(x)` | Não | Veja seção 11 |
| `mask(x)` | Não | O modelo tem máscaras/matte (`Layer.matte`), não expostas |
| `source` | Não | — |
| `sourceRectAtTime`, `sourceTime`, `sampleImage` | Não | `sourceRectAtTime` é o mais pedido (caixa de fundo que acompanha o texto) |
| `toComp`, `fromComp`, `toWorld`, `fromWorld`, `*Vec`, `fromCompToSurface` | Não | O avaliador de cena já calcula as matrizes (câmera/3D), mas não são expostas |
| `audioLevels`, `timeRemap`, `marker` | Não | — |
| `orientation`, `rotationX/Y/Z`, material 3D (`ambient`…`metal`, `acceptsLights`…) | Não | Existem no modelo 3D (`transform3d.rotation`, material) |
| `thisLayer("ADBE …")` (match names) | Não | Raro em tutoriais |

## 10. Property

| Item AE | Status | Onde / observação |
|---|---|---|
| `value` | Sim | — |
| `valueAtTime(t)` | Parcial | Solto e `thisProperty.valueAtTime` (Prelude → `ExprInput.valueAtTime`, ignora a expression). **Não** vale em propriedades de outras camadas |
| `velocityAtTime(t)` | Parcial | Diferença finita de 1 ms (`Prelude`), não a velocidade temporal exata do AE; solto e em `thisProperty` |
| `velocity`, `speed`, `speedAtTime(t)` | Não | — |
| `numKeys` | Parcial | Só `thisProperty.numKeys` |
| `key(i)` | Sim | Prelude: `{time, value, index}`. `key("marcador")` não |
| `nearestKey(t)` | Parcial | Só `thisProperty.nearestKey` (solto não existe) |
| `nextKey`, `previousKey` | Não | AE 26.0+ |
| `wiggle(freq, amp, oct, mult, t)` | Parcial | Prelude. Assinatura/semântica do AE; ruído **próprio** (valor-ruído suave), não reproduz a sequência do AE. Solto e `thisProperty.wiggle`; **não** como `position.wiggle()` |
| `temporalWiggle` | Não | — |
| `smooth(width, samples, t)` | Não | — |
| `loopOut(type, n)`, `loopIn(type, n)` | Sim | Prelude `__loop`: `cycle`, `pingpong`, `offset`, `continue`. A leitura de `numKeyframes` não foi verificada contra o AE (documentado em `expressoes.md`) |
| `loopOutDuration`, `loopInDuration` | Não | — |
| `propertyGroup`, `propertyIndex`, `name` | Não | — |
| Key: `index`, `time`, `value` | Sim | — |
| Marker Property / MarkerKey | Não | — |
| Dropdown `items`, `text`, `textAtTime` | Não | — |

## 11. Effect e Expression Controls

| Item AE | Status | Observação |
|---|---|---|
| `effect(name\|index)`, `.param()`, `effect("X")("Y")`, `.active` | Não | Os efeitos existem (`Layer.effects`, `EffectRegistry`), e seus parâmetros numéricos/cor/ponto **aceitam** expression (`Props.effectProps`), mas não há como lê-los de outra expression |
| Slider, Checkbox, Color, Point, Angle, Dropdown, Layer Control | Não | Nenhum efeito de controle existe no registro (a categoria `UTILITY` "Utilitário e expressão" só tem Depth Map, Channel Shuffle, Alpha from Luma, Copy Background). Existem camadas `null` (`LayerType.NULL`) para hospedá-los |
| `Effect.param` por índice | Não | — |

## 12. Path e Mask

| Item AE | Status | Observação |
|---|---|---|
| `points`, `inTangents`, `outTangents`, `isClosed`, `pointOnPath`, `tangentOnPath`, `normalOnPath`, `createPath` | Não | O modelo tem `PathSpec`/`PathPoint` (formas `path`), **sem** `Animatable` (caminho não é animável nem aceita expression) |
| `Mask.invert/maskExpansion/maskFeather/maskOpacity/maskPath` | Não | — |

## 13. Footage, Project, Camera, Light

| Item AE | Status | Observação |
|---|---|---|
| `footage()` e seus atributos | Não | — |
| `thisProject.*` | Não | — |
| `thisComp.activeCamera`, `cameraOption.*` (`zoom`, `focusDistance`, `pointOfInterest`…) | Não | O modelo tem câmera (`camera.fov`, `camera.dof.*`), todas animáveis e com expression, mas não lidas por outras |
| `lightOption.*` | Não | Idem (`light.intensity`, `light.radius`, …) |
| `PropertyGroup.*` | Não | — |

## 14. Text

| Item AE | Status | Onde / observação | Texto |
|---|---|---|---|
| `textIndex` | Sim | Engine; 1-based; caractere/palavra/linha conforme `unit` (`TextExpression.units`) | T |
| `textTotal` | Sim | Engine | T |
| `selectorValue` (e alias `textSelectorValue`) | Parcial | Valor base do `amount`; **não** encadeia seletores anteriores | T |
| Animadores: posição, escala, rotação, opacidade | Parcial | `TextExpression.state`; escala uniforme (média dos eixos) | T |
| Animador: cor de preenchimento/contorno, tracking, blur, skew, anchor, baseline, font axes | Não | — | T |
| Seletores Range / Wiggly / Random | Não | Só o seletor de expressão | T |
| `text.sourceText` (ler/escrever por expression) | Não | `TextContent.content` é `String` estática, **sem** `Animatable` | — |
| `text.sourceText.style.*`, `createStyle`, `getStyleAt`, setters | Não | — | — |
| `text.animator("x").property.fontAxis*` | Não | — | T |
| `unit: line` | Parcial | Linhas por `\n`; quebras automáticas não contam | T |
| Animadores nativos do Kinesis (`TextAnimatorType`: typewriter, fade, slideUp…) | Fora do AE | Sistema paralelo; não usa expression | — |

## 15. Cobertura por tipo de propriedade

O que é uma "propriedade animável com expression" hoje (`Animatable<T>`; `LayerProps.all`; `ExpressionResolver.Codec`):

| Tipo | Aceita expression? | Observação |
|---|---|---|
| Número (`AnimFloat`: opacidade, rotação, fontSize, trim…, parâmetros NUMBER/INTEGER de efeitos) | Sim | `FloatKind` |
| Vetor 2D (posição, escala, âncora, tamanho de forma, parâmetros POINT) | Sim | `Vec2Kind`; posição convertida p/ origem no canto sup. esquerdo |
| Vetor 3D (transform3d.position/rotation/scale, câmera/luz 3D) | Sim | `Vec3Kind`; **não** desloca posição 3D nem âncora |
| Cor (`AnimColor`, parâmetro COLOR de efeitos) | Sim | `[r,g,b,a]` 0..1; 3 elementos preservam o alfa |
| Texto (`TextContent.content`) | **Não** | Não é `Animatable` |
| Caminho (`PathSpec`) | **Não** | Não é `Animatable` |
| Booleano / escolha / gradiente (parâmetros BOOLEAN, CHOICE, STOPS de efeitos) | **Não** | Estáticos por definição (`ParamType.animatable`) |
| Campos estáticos (`lineHeight`, `align`, `maxWidth`, volume por clipe com eq…) | **Não** | Só o que está em `LayerProps` |
| `amount` do seletor de texto | Sim | `TextPropertyAnimator.amount` |
| Valores de propriedades do animador de texto (`position`, `scale`, `rotation`, `opacity` do animador) | Parcial | Aceitam valor/keyframes, sem expression própria |

## 16. Receitas dos tutoriais: o que funciona hoje

| Receita (veja §16 da referência) | Funciona? | Por quê |
|---|---|---|
| `wiggle(5, 20)` na propriedade atual | Sim | Prelude |
| `[value[0], wiggle(3,50)[1]]` | Sim | — |
| `loopOut("cycle" \| "pingpong" \| "offset")` | Sim | Prelude |
| `time * 90` | Sim | — |
| Inertial bounce clássico (`numKeys`, `nearestKey`) | **Não** | `numKeys`/`nearestKey` soltos não existem; só `thisProperty.` (o exemplo passa se trocar por `thisProperty.numKeys` e `thisProperty.nearestKey`) |
| Seguir com atraso (`thisComp.layer(thisLayer,-1).position.valueAtTime(time-.5)`) | **Não** | `layer(layer, rel)`, `.position` e `.valueAtTime` de outra camada ausentes |
| Entre duas camadas (`(a.position + b.position)/2`) | **Não** | Operadores em arrays |
| Círculo (`[w/2,h/2] + [sin*50, -cos*50]`) | **Não** | Operador `+` em arrays |
| Wiggle com janela de tempo (`if … wiggle(3,25) else value`) | Sim | — |
| Slider de controle / audio reativo (`effect("…")("Slider")`) | **Não** | Sem `effect()` nem controles nem áudio |
| Marcadores (`marker.key("Start").time`) | **Não** | — |
| Flash por keyframe (`easeOut(Math.abs(time - nearestKey(time).time), …)`) | **Não** | `nearestKey` solto |
| Texto: cascata com `textIndex`/`textTotal` + `ease`/`linear` | Sim | Veja `.claude/skills/knsproj/examples/basicos/texto-bounce` e os presets em `ExpressionUi.kt` |
| Auto-tamanho de fundo (`sourceRectAtTime`) | **Não** | — |
| `lookAt` / distância à câmera | **Não** | — |

## 17. Infraestrutura (não é API do AE, mas pesa na compatibilidade)

| Tema | Estado hoje |
|---|---|
| Motor | Rhino 1.7.15, interpretado (`optimizationLevel = -1`). Um só código para app e testes |
| Cache | Scripts compilados por **texto** (até 512), em `ConcurrentHashMap` |
| Escopo por avaliação | Um escopo novo por avaliação; o **Prelude é re-executado a cada chamada** (`prelude!!.exec`) — principal custo (~37 µs por expression, `docs/desenvolvimento.md`) |
| Segurança | `ClassShutter` nega Java; sem arquivos/rede; 400 000 instruções por avaliação; pilha 200; 20 000 caracteres. Sem limite de memória |
| Timeout por tempo | Não (só contagem de instruções) |
| Erro | `ExpressionError(linha, motivo)`; a propriedade usa o valor dos keyframes; `ExpressionResolver.onError` avisa o editor |
| Resultado | Número ou array de 1 a 4 números. Texto/booleano/caminho como resultado → erro |
| Validação | `ProjectValidator` (`expression.length`, `expression.syntax`); app usa `diagnose` |
| Autocompletar / snippets | Não há. Há 3 presets de texto (`TEXT_PRESETS`) e destaque de sintaxe com lista de palavras (`BUILTINS` em `ExpressionUi.kt`) |
| Áudio | Não há análise: `scene/AudioPlan.kt` só planeja clipes de reprodução; nenhum código calcula amplitude/bandas |
| Formato | `Animatable` grava `"expression"` e `"expressionEnabled"` (`Serializers.kt`); só propriedades `Animatable` |
