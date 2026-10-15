# Expressões (compatibilidade progressiva com o After Effects)

A versão 0.1.1 do Kinesis Editor adiciona **expressões JavaScript** às propriedades animáveis e **animadores de texto por
caractere** no estilo do After Effects (AE). A meta é que expressões simples copiadas de tutoriais do AE funcionem sem
alterações. **Isto não é compatibilidade completa com o AE**: só o que está marcado como implementado na matriz abaixo
existe, e cada item tem teste automatizado.

- Formato: `formatVersion` 3 (seção 4.1 de [`knsproj.md`](knsproj.md)); schema `schema/knsproj-v3.schema.json`.
- Código: módulo `core`, pacote `dev.kinesis.core.expr` (`ExpressionEngine`, `ExpressionResolver`, `TextExpression`).
- Testes: `core/src/test/kotlin/dev/kinesis/core/expr/` (motor e cena) e a CLI Python (`tools/tests`).

## Como funcionam

- Uma propriedade com expressão vale `{"value": <valor>, "expression": "<código>"}` (ou `{"keyframes": [...], "expression": "..."}`).
  `expressionEnabled: false` desliga sem apagar o código. O código é gravado **exatamente** como digitado.
- O resultado da expressão é o **valor da última instrução avaliada**, como no AE (`[s, s]`, `value`, `a * 2`...). Pode ser
  número ou array de números (1 a 4).
- Dentro da expressão, `value` é o valor da propriedade **sem** a expressão (estático ou interpolado dos keyframes).
- Em cada quadro a cena é calculada por uma função pura (`SceneEvaluator.evaluate(projeto, tempo)`): as expressões são
  resolvidas nesse ponto, então **preview, exportação e rolagem da timeline usam exatamente o mesmo cálculo**
  (teste `theSameFrameIsIdenticalNoMatterHowItIsReached`).
- Erro de sintaxe ou de execução: a propriedade usa o valor dos keyframes (como se a expressão estivesse desligada) e o
  editor mostra a linha e a mensagem. Nenhum erro derruba o preview.

### Unidades (as do After Effects dentro da expressão)

| Propriedade | Na expressão | Guardada no arquivo |
|---|---|---|
| Posição 2D | pixels, **a partir do canto superior esquerdo** (`[w/2, h/2]` = centro) | pixels a partir do **centro** |
| Escala | porcentagem (100 = normal) | multiplicador (1 = normal) |
| Opacidade | 0 a 100 | 0 a 1 |
| Rotação | graus | graus |
| Cor | `[r, g, b, a]` de 0 a 1 | `"#RRGGBBAA"` |
| Tempo | segundos da composição (`time`); dentro de um grupo, segundos do grupo | segundos |

Âncora e posição 3D **não** são deslocadas (ficam como no arquivo).

## Animadores de texto (`text.propertyAnimators`)

Modelo do AE: um animador tem **propriedades** (`position`, `scale`, `rotation`, `opacity`) e um **seletor de expressão**
(`amount`, em %). Para cada caractere (ou palavra/linha, `unit`), com `a = amount / 100`:

| Propriedade | Efeito no caractere |
|---|---|
| `position` (px) | desloca `position · a` (por eixo) |
| `scale` (%, 100 = normal) | fator `1 + (scale/100 − 1) · a`; caracteres usam escala uniforme (média dos eixos) |
| `rotation` (graus) | gira `rotation · a` |
| `opacity` (%, 100 = normal) | `1 + (opacity/100 − 1) · a`, limitado a 0..1 |

A expressão do `amount` roda **uma vez por caractere** e por quadro. Nela, `value` e `selectorValue` (também
`textSelectorValue`) valem o `amount` base (100 por padrão; array de 2 posições se o animador tem `position` ou `scale`),
`textIndex` começa em **1** e conta caracteres (espaços incluídos, como o AE em "Characters"), `textTotal` é o total e
`index` continua sendo o índice da **camada**. O resultado pode ser número ou array (`[s, s]`).

O exemplo "Bounce Text" do tutorial funciona sem alterações: com `scale: [0, 0]` o caractere está colapsado (amount 100)
até a sua vez; então o amount oscila a partir de 50 e decai até 0, e o caractere volta ao tamanho normal. Veja
`.claude/skills/knsproj/examples/basicos/texto-bounce`.

## Matriz de compatibilidade

Legenda: **Sim** = implementado e testado com a semântica documentada do AE; **Parcial** = implementado com diferenças
descritas; **Não** = não existe (a expressão falha com "... is not defined" e o valor dos keyframes é usado).

### Contexto

| API | Estado | Observações |
|---|---|---|
| `time` | Sim | segundos da composição (do grupo, dentro de um grupo); `posterizeTime()` o quantiza |
| `value` | Sim | valor da propriedade sem a expressão |
| `inPoint`, `outPoint` | Sim | início e fim da camada |
| `index` | Sim | 1 = camada de cima do contêiner (convenção do AE) |
| `thisComp` | Parcial | `width`, `height`, `duration`, `frameDuration`, `numLayers`, `displayStartTime` (0), `name`, `layer(índice ou nome)` |
| `thisLayer` / `thisComp.layer(x)` | Parcial | `name`, `index`, `inPoint`, `outPoint`, `startTime` e `transform.position/scale/rotation/opacity` como **valores** no instante atual (sem expressões de outras camadas, sem efeitos, sem `.valueAtTime`) |
| `thisProperty` | Parcial | `value`, `valueAtTime(t)`, `numKeys`, `key(n)` (`.time`, `.value`, `.index`), `nearestKey(t)`, `velocityAtTime(t)`, `loopIn`, `loopOut`, `wiggle`; sem `propertyGroup`, `name`, `speedAtTime` |

### Interpolação e animação

| API | Estado | Observações |
|---|---|---|
| `wiggle(freq, amp, octaves, ampMult, t)` | Parcial | parâmetros e semântica do AE (vetores por eixo, oitavas, `t`); o ruído é próprio do Kinesis, **não reproduz a sequência do AE**. Determinístico por camada/propriedade e quadro |
| `loopOut(tipo, n)` / `loopIn(tipo, n)` | Sim | `"cycle"`, `"pingpong"`, `"offset"`, `"continue"`; vetores. `n` é interpretado como número de **segmentos** contados a partir do último (ou primeiro) keyframe (`0` = todos); essa leitura de `numKeyframes` não foi verificada contra o AE. Sem keyframes devolve `value` |
| `linear(t, tMin, tMax, v1, v2)` e `linear(t, v1, v2)` | Sim | vetores elemento a elemento, limitado a `[tMin, tMax]` |
| `ease`, `easeIn`, `easeOut` | Parcial | mesmos argumentos de `linear`; `ease` é a curva suave com tangentes zero nas duas pontas, `easeIn`/`easeOut` com tangente zero só no começo/fim e linear do outro lado. As curvas exatas do AE não foram comparadas |
| `posterizeTime(fps)` | Parcial | quantiza a variável `time` do restante da expressão; não afeta `valueAtTime` |

### Matemática e aleatoriedade

| API | Estado | Observações |
|---|---|---|
| `Math` | Sim | o `Math` do JavaScript (ES5/ES6 parcial). `Math.random()` é o `random()` determinístico |
| `random()`, `random(max)`, `random(min, max)`, versões com array | Sim (semântica) | determinístico por quadro; os números **não** coincidem com os do AE |
| `seedRandom(seed, timeless)` | Sim (semântica) | `timeless=false` muda a semente a cada quadro; `true` mantém |
| `clamp(v, min, max)` | Sim | vetores elemento a elemento |
| `length(v)`, `length(a, b)` | Sim | módulo e distância |
| `normalize(v)` | Sim | |
| `add`, `sub`, `mul`, `div`, `dot`, `cross`, `degreesToRadians`, `radiansToDegrees` | Sim | operações vetoriais do AE |
| `gaussRandom`, `noise`, `timeToFrames`, `framesToTime`, `rgbToHsl`, `hslToRgb`, `smooth`, `lookAt`, `toComp`, `fromComp`... | Não | |
| `effect()`, `mask()`, `footage()`, `comp()`, `sourceText`, `createPath`, marcadores, controles de expressão | Não | |

### Texto

| API | Estado | Observações |
|---|---|---|
| `textIndex`, `textTotal` | Sim | 1-based; caracteres (espaços incluídos), palavras ou linhas conforme `unit` |
| `textSelectorValue` / `selectorValue` | Sim | amount base do seletor |
| Animadores por caractere: posição, escala, rotação, opacidade | Sim | seletor de expressão; vários animadores se combinam |
| Seletor de intervalo (Range Selector), ondulado, aleatório | Não | só o seletor de expressão |
| Propriedades de animador: cor, contorno, tracking, blur, skew | Não | |
| `unit: line` | Parcial | linhas por quebra explícita (`\n`); quebras automáticas de largura não contam |

## O motor

- Interpretador **Rhino 1.7.15** em modo interpretado, embutido no `core` (um só código para o app e para os testes na JVM).
  O QuickJS preferido no pedido não foi usado: a única ligação Java/Android disponível (`app.cash.quickjs`) não oferece
  limite de instruções nem interrupção, e sem isso não há como parar um `while (true)`.
- Offline e isolado: nenhuma classe Java é acessível (`ClassShutter` nega tudo, sem `Packages`/`java`), a linguagem não
  tem arquivos, rede nem processos; `Date` não existe. Não há acesso a APIs Android.
- Limites: 20 000 caracteres por expressão, **400 000 instruções interpretadas** por avaliação (laço infinito = erro
  "The expression ran too long"), profundidade de pilha 200. **Não há limite rígido de memória** (o Rhino não tem); o
  limite de instruções restringe o quanto uma expressão consegue alocar.
- Cache: o código compilado é guardado por texto (até 512 scripts).
- Linguagem: JavaScript ES5 completo mais `let`/`const`, funções-seta, *template strings* e os métodos novos de `Math`,
  `Array` e `String` do Rhino. **Não** há parâmetros padrão (`function f(a, b = 2)`) nem *spread* (`[...a]`).
  Vetores são arrays comuns: `a + b` **não** soma arrays; use `add(a, b)` (igual ao AE).

## Limitações conhecidas

- Os valores de `wiggle`, `random` e `seedRandom` não são os do AE (o gerador deles não é público).
- `thisComp.layer(x)` lê só a transformação de outra camada, sem avaliar as expressões dela.
- Expressões em parâmetros de efeitos funcionam (são propriedades animáveis), mas não há `effect("nome")("param")`.
- Animadores de texto: escala uniforme por caractere; sem seletor de intervalo; `unit: line` não considera quebras
  automáticas.
- O editor do app não tem autocompletar; o destaque de sintaxe cobre palavras-chave, funções do AE, números, textos e comentários.
- A CLI Python não interpreta JavaScript: confere só parênteses/aspas (código `expression.syntax` aproximado); a checagem
  exata é do app e do core Kotlin.
- Não há proteção contra expressões que demoram (dentro do limite de instruções) em textos muito longos: o custo é por
  caractere e por quadro (medido: ~4 ms por quadro para 54 caracteres no desktop; em aparelhos mais lentos pode ser maior).
