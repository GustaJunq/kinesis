# Plano de expansão do motor de expressions do Kinesis

Resultado da Tarefa 3. Baseado em `expressions-ae-reference.md` (o que o AE tem) e `expressions-compat.md` (o que o Kinesis tem hoje).
**Nada foi implementado.** Este plano espera aprovação (Tarefa 4).

## Fontes (URLs)

Referência e tutoriais: as listadas em `expressions-ae-reference.md` (https://ae-expressions.docsforadobe.dev/ e as páginas comunitárias).
Adicionais para a avaliação de motores:

- QuickJS Kotlin: https://github.com/dokar3/quickjs-kt (a busca não confirmou se há interrupção/limite de memória; **não confirmado**)
- Rhino, tabela de compatibilidade ES: https://mozilla.github.io/rhino/compat/engines.html (a busca mostrou 1.7.15 com menos cobertura ES2015 que 1.9.0; a linha do `Proxy` **não foi lida**)
- Código e docs internos: `docs/expressoes.md`, `docs/desenvolvimento.md` (decisão anterior de não usar QuickJS, benchmark), `docs/knsproj.md`.

---

## Decisões aprovadas (2026-10-24)

Respostas do responsável do projeto ao plano; elas **prevalecem** sobre o resto do documento quando houver diferença.

1. **Uma versão só do formato**: tudo o que muda o `.knsproj` (efeitos de Expression Controls, nome de efeito, análise de áudio, e depois texto/booleano/caminho com expression) vai numa única versão nova, **v12**. O texto abaixo que fala em "v13" passa a valer como "v12".
2. **Áudio primeiro**: a ordem de execução é Fase 0 → **Áudio (com os Expression Controls mínimos que ele exige)** → Fase 1 ("colar e funcionar": operadores, objetos Property, variáveis soltas) → controles e espaço restantes → texto → caminhos. Veja a §10 atualizada.
3. **Rhino**: a avaliação de subir para 1.8/1.9 (com verificação de compatibilidade com Android) entra na Fase 0.
4. **`wiggle`/`random`**: manter o gerador próprio do Kinesis e documentar que os números não coincidem com os do AE (determinístico, mesma frequência/amplitude/distribuição, sequência diferente).

## 0. Resumo executivo

1. **O Kinesis já embute um engine JS (Rhino), não um parser próprio.** A pergunta "QuickJS vs parser próprio" portanto é na verdade
   "continuar com Rhino ou trocar por QuickJS". **Recomendação: manter o Rhino**, atrás de uma interface (`ExpressionBackend`) para
   poder testar o QuickJS num spike depois. Motivo: o que impede a compatibilidade com o AE **não é o motor**, é (a) a API injetada
   (objetos Property/Layer/Effect) e (b) operadores em arrays (`a.position + b.position`), que **nenhum** motor JS faz sozinho.
2. Os 3 maiores buracos (compat §"10 buracos") se resolvem sem trocar o motor:
   - **Reescrever os operadores** `+ - * /` (e `+=`…) em tempo de compilação para helpers que entendem arrays (usando o parser AST do Rhino; feito uma vez por texto, cacheado).
   - **Objetos Property de verdade** (`position`, `thisLayer.position`, `thisComp.layer(x).position.valueAtTime(t)`, `effect("X")("Y")`) e **variáveis soltas** (`numKeys`, `nearestKey`, `velocity`…).
   - **Expression Controls + `effect()`**, que destravam o fluxo mais comum do AE (controles numa camada nula) e o áudio reativo.
3. **Cache/performance**: hoje o Prelude é re-executado a cada avaliação (~37 µs por expression). Dá para pré-executar uma vez e reutilizar.
4. **Segurança**: acrescentar limite por tempo (hoje só por instruções), proteção contra ciclos entre camadas e "disjuntor" por expression que falha.
5. **Áudio reativo**: analisar uma vez (amplitude + 3 bandas), guardar no projeto, e expor **no formato do AE** (`thisComp.layer("Audio Amplitude").effect("Both Channels")("Slider")`) via botão "converter áudio em keyframes". Assim expressions copiadas do AE funcionam.
6. **Toda propriedade com expression**, em fases: número/vetor/cor (já), parâmetros de efeito numéricos (já), depois texto (`sourceText`), booleanos/escolhas, caminhos. Exige mudança de schema (v12), listada na §7.
7. **UX móvel**: um único registro da API (`expressions-api.json`) alimenta autocomplete, destaque, snippets e documentação.

---

## 1. Motor: Rhino (atual) × QuickJS × parser próprio

### 1.1 Alternativas

| Critério | Rhino 1.7.15 (hoje) | QuickJS (ex.: `quickjs-kt`) | Parser/avaliador próprio |
|---|---|---|---|
| Linguagem | ES5 + parte de ES6 (`let/const`, arrow, template). **Sem** parâmetros padrão, spread. 1.7.15 tem menos ES2015 que o 1.9 (tabela do Rhino) | ES2020+ completo (maioria dos recursos que tutoriais atuais usam) | Só o que se escrever; qualquer JS "de verdade" copiado do AE quebra |
| Embutir no Android | Java puro, sem JNI. Funciona em modo interpretado (necessário no Android) | JNI/NDK por ABI; mais um `.so` para manter | Kotlin puro |
| Desempenho | Interpretado: ~37 µs/expression medido (com o Prelude re-executado a cada vez, `docs/desenvolvimento.md`). Tem espaço para melhorar sem trocar o motor | Em geral mais rápido, mas não há medição no Kinesis | Rápido, mas limitado |
| Interromper laço infinito | **Sim**: `observeInstructionCount` (já usado, 400 000 instruções) | Depende da biblioteca. O C do QuickJS tem *interrupt handler*; as ligações Kotlin/Android **não confirmadas**. A nota em `docs/expressoes.md` diz que a `app.cash.quickjs` não oferece | Sim, se feito |
| Limite de memória | Não tem | O C tem `JS_SetMemoryLimit`; ligação Kotlin **não confirmada** | Controlável |
| Sandbox | `ClassShutter` nega Java; já testado | Nenhum Java acessível por padrão | Total |
| Determinismo preview/exportação | Já testado (`theSameFrameIsIdenticalNoMatterHowItIsReached`) | Mesma exigência, a testar | — |
| Reuso no core (JVM, testes) | Um só código para app e testes (Rhino roda na JVM) | Precisa de binário nativo também para testes JVM (por plataforma) | — |
| Custo de migrar | 0 | Reescrever o Prelude/host, 2 backends nativos, novo CI de testes | Altíssimo |
| Compatibilidade de sintaxe do AE | Igual nos dois: **operadores em arrays e objetos Property precisam da nossa camada** | Idem (o `OperatorSet` do QuickJS só existe no build "bignum", **não** nos binds Android usuais — **não confirmado**) | Idem |

### 1.2 Recomendação

1. **Manter Rhino agora** (mudar de motor não resolve nenhum dos 10 buracos e exige duplicar testes/CI).
2. Introduzir `interface ExpressionBackend { compile(src): Compiled; run(Compiled, Frame): Result }`, com Rhino como única implementação, para não
   amarrar o resto do código e permitir um spike do QuickJS **depois da Fase 3**, só se os benchmarks mostrarem que a avaliação ainda é gargalo
   (critério: > 1 ms por quadro para uma cena típica de 50 expressions no aparelho-alvo), ou se tutoriais com JS moderno quebrarem.
3. Avaliar **subir o Rhino para 1.8/1.9** (mais ES2015: possivelmente parâmetros padrão, spread). **Verificar** compatibilidade com Android (versão do Java/desugaring) antes de decidir.
4. **Não** escrever parser próprio: custo alto, e a Adobe usa JS completo.

### 1.3 Reescrita de operadores (a peça-chave de compatibilidade)

O AE sobrecarrega `+ - * /` para arrays e Property (`(thisComp.layer(1).position + thisComp.layer(2).position)/2`, exemplo **oficial**).
JavaScript puro não permite isso. Plano:

- Ao compilar, parsear com o `Parser` do Rhino (AST), trocar cada `BinaryExpression` `+ - * /` e atribuições compostas por chamadas
  `__op_add(a,b)`, `__op_sub`, `__op_mul`, `__op_div` (e `-x` por `__op_neg`).
- Os helpers têm **caminho rápido** para dois números (`typeof a === 'number' && typeof b === 'number'`), então o custo em expressions escalares é pequeno.
- Para arrays: elemento a elemento (como `add/sub/mul/div` já fazem); para Property: usar o valor.
- Cuidado: `+` com strings (concatenação, comum em Source Text) tem que continuar funcionando; `__op_add` decide em tempo de execução.
- Risco: número do AST/linhas de erro → manter o mapeamento de linhas (reescrever sem mudar quebras de linha).
- Custo: **M**. Valor: **o maior** em "copiar e colar" (veja §3, item P1).

---

## 2. Compilar uma vez, avaliar por frame

### 2.1 Situação atual

- O texto é compilado uma vez e cacheado (`ConcurrentHashMap<String, Script>`, 512).
- A cada avaliação é criado um escopo novo e o **Prelude inteiro é executado de novo** (`ExpressionEngine.evaluate`), o que é o custo dominante.

### 2.2 Plano

1. **Prelude congelado**: executar o Prelude **uma vez** em um escopo base e selado; cada avaliação cria só um escopo filho leve com as variáveis do quadro
   (`time`, `value`…) e *getters* para o que for caro. Meta: reduzir o custo por avaliação (medir antes/depois com `CoreBench`).
2. **Compilar uma vez, por texto transformado**: a chave do cache passa a ser o texto original; o valor é o script já reescrito (operadores) e compilado. Limite do cache por LRU em vez de `clear()` total (hoje zera tudo ao passar de 512).
3. **Variáveis preguiçosas**: `thisComp.layer(x)` e `effect(…)` só montam dados quando acessados (objetos Property criados sob demanda, não por avaliação).
4. **Memoização por quadro**: dentro de uma avaliação de cena, guardar o valor já resolvido de `(camada, propriedade, tempo)`; várias expressions que leem o mesmo
   slider ou a mesma camada pagam uma vez. Esta memória também é a base da detecção de ciclos (§3).
5. **Dependências**: registrar quais propriedades cada expression leu (útil para invalidar caches e para avisar "esta expression depende de X").
6. **Constantes**: expressions sem `time` nem leitura de outras propriedades (detectável no AST) podem ser avaliadas uma vez (o AE faz algo equivalente desde 17.0).
7. Manter `SceneEvaluator.evaluate(projeto, tempo)` como função pura; preview e exportação continuam idênticos.

---

## 3. Segurança e robustez

| Risco | Hoje | Plano |
|---|---|---|
| Laço infinito | 400 000 instruções por avaliação | Manter + **limite de tempo** (relógio) checado no mesmo observador, p.ex. 5 ms por expression em preview (configurável; exportação usa só instruções, para ser determinístico) |
| Orçamento por quadro | Não há | Orçamento **total** por quadro (soma de todas as expressions); estourou, as restantes usam o valor sem expression e o quadro é marcado |
| Erro de sintaxe/execução | `ExpressionError(linha, motivo)`, usa o valor dos keyframes | Manter. Acrescentar: **aviso visível** na camada/propriedade (selo), com a mensagem e a linha, e sem interromper o playback |
| Falha repetida | Reavalia (e falha) a cada quadro | **Disjuntor**: depois de N falhas seguidas (p.ex. 3) a expression é suspensa na sessão (valor sem expression) até o texto mudar; o selo continua mostrando o erro |
| Ciclos entre camadas (A lê B, B lê A) | Não existe (só lê transformação crua) | Quando houver leitura de outras expressions: pilha de avaliação; ciclo → erro "Circular reference" na linha e fallback ao valor sem expression, sem travar |
| Recursão profunda / pilha | `maximumInterpreterStackDepth = 200`; `StackOverflowError` tratado | Manter |
| Memória | Sem limite (Rhino não tem); mitigado pelo limite de instruções | Limitar tamanho de strings/arrays criados nos helpers do host (p.ex. `createPath`, `footage`) e o resultado; documentar |
| Java acessível | `ClassShutter` nega tudo | Manter; teste de regressão |
| `Date`/`Math.random` não determinísticos | Removido/redirecionado | Manter |
| Texto de erro | Em inglês na engine | Tabela de mensagens traduzidas (pt/en) pelo app; manter o texto original do AE quando existir |

Mapa de comportamento em erro (igual ao AE em espírito): a propriedade **usa o valor sem expression** e a UI mostra aviso; nunca derruba o preview nem a exportação
(a exportação registra os avisos num relatório e conclui).

---

## 4. Modelo de objetos (a base de `layer()`, `effect()`, `position.wiggle()`)

Hoje `thisComp.layer(x)` devolve um objeto de dados achatado. Plano: objetos **host** com getters preguiçosos.

| Objeto | Detalhe |
|---|---|
| Property | Escalar → `Number` com propriedades extras (`valueAtTime`, `wiggle`, `key`…); vetor → `Array` com as mesmas extras. Assim `position[0]`, `position.length`, e a reescrita de operadores funcionam sem Proxy. (Se o Rhino da versão escolhida tiver `Proxy`, avaliar; **não confirmado**.) |
| Layer | `name`, `index`, `inPoint`, `outPoint`, `startTime`, `width`, `height`, `hasParent`/`parent`, `active`, `enabled`, transform (`position`, `scale`, `rotation`, `opacity`, `anchorPoint`), `effect()`, `marker`, `toComp`/`fromComp`/`toWorld`/`fromWorld`, `sourceRectAtTime`, 3D (`orientation`, `rotationX/Y/Z`) |
| Comp | os já existentes + `marker`, `activeCamera`, `bgColor`, `pixelAspect`, `layerByComment`, `layer(layer, rel)`, `name` real |
| Variáveis soltas | Como no AE, o objeto implícito é a propriedade/camada atual: `position`, `scale`, `opacity`, `rotation`, `anchorPoint`, `name`, `width`, `height`, `parent`, `numKeys`, `nearestKey`, `velocity`, `speed`, `marker`, `thisLayer`… |
| Unidades | Já resolvidas pelo `Codec` (posição a partir do canto sup. esq., escala/opacidade em %). Manter e **documentar** onde `toWorld` etc. divergem do espaço do Kinesis (origem no centro) |

Propriedades de **outras** camadas passam a ser avaliadas **com a expression delas** (como o AE), com detecção de ciclo (§3) e memoização (§2).

---

## 5. Expression Controls

Critério: destravam `effect("X")("Y")`, são usados em quase todo projeto de template e são a ponte para o áudio reativo.

Plano:

1. Criar os efeitos em `EffectRegistry` (categoria "Utilitário e expressão"), todos sem efeito visual:
   `sliderControl` (param `Slider`), `checkboxControl` (`Checkbox`), `colorControl` (`Color`), `pointControl` (`Point`), `angleControl` (`Angle`),
   `dropdownControl` (`Menu` + itens), `layerControl` (`Layer`). 3D Point: **não confirmado** no AE; adiar.
2. `effect("Nome")("Param")` → resolve efeito pelo **nome definido pelo usuário** (hoje o `Effect` tem `type`; precisa de `name` opcional — mudança de schema, §7) ou pelo índice. Também aceita `effect(i)` e `.param()`.
3. Atalho `thisLayer.effect("X")("Y")` e `thisComp.layer("Null 1").effect("X")("Y")`.
4. Nomes das propriedades internas: Slider = `"Slider"` (**confirmado**); Dropdown `"Menu"`; Checkbox/Color/Point/Angle/Layer = nomes **não confirmados** (usar os do AE em inglês e o nome visível no idioma do app, aceitar os dois).
5. Checkbox e Dropdown são estáticos hoje (`ParamType.BOOLEAN/CHOICE`); no AE são animáveis. Fase posterior: tornar animáveis (§6).
6. Dropdown: `.value` devolve o **índice** (recomendação da Adobe); `items`/`text`/`textAtTime` (AE 26.0+) como extensão.

---

## 6. "Toda propriedade aceita expression", em fases

| Fase | O que | Estado | Mudança de modelo |
|---|---|---|---|
| A | Número, vetor 2D/3D, cor (transform, texto, forma, câmera, luz, 3D) | **Feito** (`LayerProps`) | — |
| B | Parâmetros NUMBER/INTEGER/POINT/COLOR de efeitos | **Feito** (`Props.effectProps`) | — |
| C | `text.content` (Source Text) | Não | `TextContent.content: String` → `Animatable<String>` (valor + expressão; sem keyframes ou com keyframes de "hold") |
| D | Booleanos e escolhas de efeitos (Checkbox, Dropdown, etc.) | Não | `ParamType.BOOLEAN/CHOICE` animáveis (valor + expressão; resultado 0/1 ou índice) |
| E | Campos estáticos hoje fora de `LayerProps`: `lineHeight`, `maxWidth`, `align`, volumes de clipe, etc. | Não | Transformar os úteis em `Animatable` ou restringir (decidir caso a caso; `align` como índice) |
| F | Caminhos (`PathSpec`) | Não | `Animatable<PathSpec>` (keyframes de caminho têm de ter o mesmo número de pontos) + resultado da expression como objeto Path (`createPath`) |
| G | Valores do animador de texto (cor, tracking, etc.) | Parcial | Novas propriedades do animador + expression própria |

Regras de resultado (por tipo): número → 1 valor; vetor → 2 a 4; cor → `[r,g,b,a]` ou `hexToRgb(...)`; texto → `String`; booleano → número 0/1 (como o AE); caminho → `createPath(...)`.
O `ExpressionEngine.convert` hoje só aceita número/array: precisa ser generalizado (`ExprResult` com `kind`).

---

## 7. Mudanças propostas no schema `.knsproj` (retrocompatibilidade)

Regra do projeto (`docs/knsproj.md` §12): **qualquer** mudança incrementa `formatVersion`; leitores **recusam** versões mais novas; versões antigas migram em cadeia.
Por isso: **agrupar** tudo numa única versão por lançamento para não gerar um formato novo por recurso. **Aprovado: uma única versão, v12**, com tudo da §7.1 e da §7.2 (a §7.2 chega depois, mas já na v12 se a v12 ainda não tiver sido publicada; se a v12 sair antes, os campos da §7.2 entram numa versão seguinte, e não antes de ela estar fechada).

### 7.1 v12 (só campos novos, v11 abre sem mudança → `Migrations.v11ToV12` é a identidade)

| Campo | Onde | Motivo |
|---|---|---|
| Efeitos `sliderControl`, `checkboxControl`, `colorControl`, `pointControl`, `angleControl`, `dropdownControl`, `layerControl` | `effects[]` (registro de efeitos) | Expression Controls |
| `effects[].name` (string, opcional) | Cada efeito | `effect("Meu slider")` precisa de nome do usuário. Sem o campo, o nome padrão é o do tipo |
| `assets[].analysis` (objeto opcional) | Asset de áudio/vídeo com áudio | Resultado da análise de áudio (veja §8): `{ "version": 1, "sampleRate": 60, "channels": ["left","right","both"], "bands": ["low","mid","high"], "peak": 1.0, "data": "<base64 de float16/uint8>" }` — ou, melhor para arquivos grandes, `"path": "assets/analysis/<id>.bin"` (sidecar dentro do ZIP) |
| `layers[].expressionControls` | Nenhum — o controle é um efeito normal | — |
| `composition.expressionEngine` (inteiro, opcional, padrão 1) | Raiz | Versão semântica da API de expressions. Permite mudar `ease`/`wiggle` no futuro sem alterar projetos antigos (v1 = comportamento atual) |

Como converter áudio em keyframes sem campo novo: o botão cria uma camada `null` chamada "Audio Amplitude" com 3 `sliderControl` ("Left Channel", "Right Channel",
"Both Channels") **com keyframes** — isso usa só o que já existe (+ os efeitos novos). A análise (`assets[].analysis`) serve de cache para
"Rebuild" e para o acesso por banda sem keyframes.

### 7.2 Texto, booleano e caminho (Fases C–F; antes chamados "v13", agora na mesma linha de versão v12)

| Campo | Mudança | Compatibilidade |
|---|---|---|
| `text.content` | `string` **ou** `{ "value": "...", "expression": "...", "expressionEnabled": true }` | Leitura aceita os dois; escrita só usa objeto quando há expressão (projetos sem expressão continuam com a string; ainda precisam de `formatVersion` 13) |
| Parâmetros BOOLEAN/CHOICE de efeitos | idem: literal **ou** `{ "value": …, "expression": … }` | idem |
| `shape.path` | `PathSpec` **ou** `{ "value": PathSpec, "keyframes": […], "expression": … }` | idem |
| `lineHeight`, `maxWidth`, etc. | literal ou animável, se escolhidos na Fase E | idem |

### 7.3 Impacto (lista de arquivos e processos)

- `core/.../model/Project.kt` (`FORMAT_VERSION`), `core/.../format/KnsprojJson.kt` (`Migrations`, nova etapa identidade), `Serializers.kt` (`AnimatableSerializer` para String/Boolean/Path), `ProjectValidator.kt` (novos erros `expression.*`).
- Novo `schema/knsproj-v12.schema.json`: estrito, como as anteriores; manter as antigas.
- `docs/knsproj.md` (versão, seções 4.1, 5.2, 7 e histórico), `docs/expressoes.md` (matriz).
- `tools/knsproj/` (`kns_migrate.py`, `kns_semantic.py`, `kns_eval.py`, `effects.json`) e a skill `.claude/skills/knsproj/` (exemplos e SKILL.md), porque validam contra o schema oficial.
- Pontos que copiam `Animatable` e precisam carregar os campos novos: `edit/MediaSwap.kt` (já copia `expression`), `edit/KeyframeClipboard.kt`, `edit/AnimTraversal.kt`, histórico/undo.
- Apps antigos **recusam** v12+ (por design). Mitigação: lançar a v12 só junto de recurso visível e avisar no changelog. Não propor exceção à regra do projeto (uma versão por mudança de formato).
- Testes: `ExpressionSceneTest` (`version2DocumentsStillOpen` → acrescentar `version11…`), round-trip, schema, CLI Python.

---

## 8. Áudio reativo

### 8.1 Princípio

Analisar **uma vez** (ao importar ou ao clicar em "converter"), guardar o resultado **no projeto**, e consultar por tempo. Nunca decodificar áudio dentro da avaliação do quadro
(preview e exportação têm de dar o mesmo número).

### 8.2 Análise

| Etapa | Proposta |
|---|---|
| Decodificar | `MediaCodec`/`MediaExtractor` no app → PCM mono/estéreo (o core recebe PCM; a análise em si fica no `core`, testável na JVM) |
| Janela | Taxa de saída fixa (p.ex. 60 amostras/s, independente do fps da comp) com interpolação linear na leitura |
| Amplitude | RMS por janela (~20–30 ms) por canal (esquerdo, direito, "ambos" = máx. ou média, **a decidir**) |
| Bandas | FFT (2048, janela de Hann) e soma de energia em **grave** (20–250 Hz), **médio** (250–4000 Hz), **agudo** (4000–16000 Hz). Os cortes são ajustáveis |
| Normalização | Guardar valores brutos + pico da faixa; escala configurável ao converter. A escala dos valores do AE ("Audio Amplitude") **não está documentada**: os tutoriais usam faixas como 2–18 (`linear(t, 2, 18, …)`), então o botão oferece "escala AE aproximada" e "0–1" — **não confirmado** |
| Suavização | Opcional: ataque/decaimento (já existe `smooth()` a implementar) |
| Reuso | Cache por hash do arquivo de áudio; invalidar se o clipe for trocado (`MediaSwap`) |

### 8.3 Exposição em expressions

Duas camadas de compatibilidade:

1. **Igual ao AE** (copiar/colar): `thisComp.layer("Audio Amplitude").effect("Both Channels")("Slider")` — funciona porque o botão gera a camada nula com
   `sliderControl` chamados "Left Channel", "Right Channel", "Both Channels" **com keyframes** (valor por amostra; 1 keyframe por quadro da comp).
2. **Extensão Kinesis** (documentada como não-AE): `audioAmplitude(layerOrName, t)` / `audioBand("low"|"mid"|"high", layerOrName, t)` lendo `assets[].analysis`
   diretamente (sem milhares de keyframes, e com bandas). Separado do namespace do AE para não confundir.

### 8.4 Botão "converter áudio em keyframes"

- Onde: ação na camada de áudio/vídeo (painel de propriedades) e no menu de camada.
- O que cria: camada `null` "Audio Amplitude" (com 3 sliders) e, opcionalmente, "Audio Bands" com `Low`, `Mid`, `High`.
- Opções: intervalo (comp inteira / trecho da camada), taxa de keyframes (por quadro / a cada N), suavização.
- Custo de arquivo: keyframes por quadro × duração (p.ex. 60 s × 30 fps × 3 canais = 5 400 keyframes) — aceitável, mas avaliar `analysis` como fonte e gerar keyframes só quando o usuário pedir.
- Impacto no formato: nenhum além dos efeitos de controle (v12) e, se aprovado, `assets[].analysis`.

---

## 9. UX móvel

| Item | Proposta |
|---|---|
| Registro único da API | `core/src/main/resources/expressions-api.json` (ou gerado de uma tabela Kotlin): nome, assinatura, parâmetros, retorno, descrição pt/en, exemplo, "suportado/parcial", **categoria**. Alimenta autocomplete, destaque (`BUILTINS` hoje é manual), a doc de compatibilidade (gerada) e testes (cada item tem um teste "golden") |
| Autocomplete | Sugestões ao digitar (≥ 2 letras), depois de `.` mostra os membros do objeto (`thisComp.` → `layer`, `width`…). Barra acima do teclado com atalhos: `[` `]` `(` `)` `.` `"` `;` `*` e as 6 funções mais usadas |
| Snippets | Biblioteca pronta: `wiggle`, `loopOut` (cycle/pingpong/offset), `inertial bounce` (estável), `overshoot`, `seguir com atraso`, `slider de controle`, `áudio reativo` (amplitude e bandas), `cascata de texto`, `auto-tamanho de fundo`, `olhar para (lookAt)`. Cada um com os controles criados automaticamente (p.ex. Slider "Amp", "Freq") |
| Erro em tempo real | Depois de ~250 ms sem digitar: (1) só compilar/parsear (barato) e sublinhar a linha; (2) se OK, uma execução de teste (`diagnose`) e mostrar o resultado atual da expression e o erro de execução. Chip de status "OK / Erro: linha N: …" |
| Aviso de função não suportada | Ao colar, varrer identificadores e comparar com o registro: "`sampleImage` ainda não é suportada" com link para o item na doc, em vez do erro genérico "is not defined" |
| Mensagens | Em português, mantendo o nome da função do AE |
| Pick whip | Na UI móvel: botão "vincular" que insere `thisComp.layer("X").position` / `effect("S")("Slider")` ao tocar em uma propriedade |
| Teclado | Fonte monoespaçada, aspas inteligentes desligadas, indentação automática |

---

## 10. Priorização

Critério (como pedido): **(1)** frequência em tutoriais, **(2)** quanto destrava, **(3)** custo. Escala 1–5 para frequência (F) e destrava (D); custo S/M/L (≈ 0,5 / 2 / 5+ dias).
Frequência vem de §16 da referência (receitas) e do que cada tutorial lido usa; é uma estimativa, não uma contagem estatística.

| # | Item | F | D | Custo | Fase |
|---|---|---|---|---|---|
| P1 | Reescrita de operadores em arrays (`+ - * /`) | 5 | 4 | M | 1 |
| P2 | Variáveis soltas e objetos Property (`position`, `numKeys`, `nearestKey`, `velocity`, `speed`, `name`…) + `thisLayer.position` | 5 | 5 | M | 1 |
| P3 | `valueAtTime`/`wiggle`/`key` em propriedades de **outras** camadas; `thisComp.layer(layer, rel)`; `parent`/`hasParent` | 5 | 5 | M | 1 |
| P4 | Inertial bounce/overshoot funcionando (sai de P2) + snippets | 5 | 1 | S | 1 |
| P5 | Expression Controls (7 efeitos) + `effect()` + `.param()` | 5 | 5 | L | 2 |
| P6 | Funções pequenas: `timeToFrames`, `framesToTime`, `gaussRandom`, `noise`, `smooth`, `temporalWiggle`, `loopOutDuration`/`loopInDuration`, `hexToRgb`, `rgbToHsl`, `hslToRgb`, `lookAt`, `ease*` com as curvas do AE | 3 | 1 | S | 1 |
| P7 | Compilar uma vez + Prelude congelado + memoização + disjuntor + timeout | 1 | 3 | M | 0–1 |
| P8 | Registro da API + autocomplete + snippets + erro em tempo real | 4 | 2 | M | 1b |
| P9 | `marker`/`thisComp.marker`, `sourceRectAtTime`, `toComp`/`fromComp`/`toWorld`/`fromWorld`, `activeCamera`, 3D (`orientation`, `rotationX/Y/Z`) | 3 | 3 | L | 2 |
| P10 | Áudio reativo: análise + camada "Audio Amplitude" + bandas + botão | 4 | 1 | L | 3 |
| P11 | Text animator completo: Range/Wiggly/Random selectors, `selectorValue` encadeado, propriedades de cor/tracking/blur/skew, `textIndex` por palavras/linhas com quebra automática | 3 | 2 | L | 4 |
| P12 | `sourceText` com expression + `text.sourceText.style.*` | 3 | 3 | L | 4 (v12) |
| P13 | Booleano/escolha animáveis (Checkbox/Dropdown) | 2 | 2 | M | 4 (v12) |
| P14 | Caminhos (`createPath`, `points`, `pointOnPath`…) | 2 | 1 | L | 5 (v12) |
| P15 | Máscaras, `sampleImage`, `footage()` (JSON/MGJSON), `comp()`, `thisProject` | 1 | 1 | L | 6 (opcional) |
| P16 | Spike QuickJS atrás de `ExpressionBackend` | — | — | M | só se justificar |

### Fases (ordem de execução atualizada: áudio primeiro)

- **Fase 0 — fundação (sem API nova, sem mudar o formato)**: interface `ExpressionBackend` (Rhino como única implementação); suíte de testes **golden** com os exemplos
  da referência oficial (`AeGoldenTest`: o que funciona é asserção; o que é lacuna fica "pending" por fase e vira regressão quando implementado); medição de custo antes/depois
  com `CoreBench`; **avaliação da subida do Rhino para 1.8/1.9** (compatibilidade com Android).
- **Fase A — áudio reativo** (P10 + o mínimo de P5): efeito `sliderControl` e `effect("Nome")("Slider")` (necessários porque a camada "Audio Amplitude" do AE é lida assim);
  análise de áudio (amplitude esquerda/direita/ambos + bandas grave/médio/agudo) no `core`; botão "converter áudio em keyframes"; camada nula "Audio Amplitude". **Formato v12.**
- **Fase 1 — "colar e funcionar"** (P1, P2, P3, P4, P6, P7, P8): operadores em arrays, objetos Property/Layer, variáveis soltas, camadas relacionadas, funções pequenas,
  Prelude congelado, snippets e autocomplete básico. Sem mudar o formato.
- **Fase 2 — controles restantes e espaço** (resto de P5, P9): Checkbox/Color/Point/Angle/Dropdown/Layer Control; marcadores; transformações de espaço; `sourceRectAtTime`.
- **Fase 4 — texto** (P11, P12, P13). Formato: mesma linha v12 (ou a versão seguinte, se a v12 já tiver sido publicada).
- **Fase 5 — caminhos** (P14).
- **Fase 6 — opcional**: máscaras, `sampleImage`, `footage()`, `comp()`.

Cada fase termina com: testes golden novos, atualização de `expressions-compat.md` e de `docs/expressoes.md`.

---

## 11. Riscos e pontos em aberto (precisam de decisão sua)

1. **Semântica do `wiggle`/`random` ≠ AE**: os números nunca vão coincidir (o gerador do AE não é público; o próprio AE mudou entre motores). Sugestão: manter e documentar.
2. **Escala do "Audio Amplitude" do AE**: não encontrei documentação oficial; o botão precisa de uma escala configurável.
3. **Bumps de formato**: resolvido — uma única versão (v12), conforme as decisões aprovadas.
4. **`Proxy` no Rhino**: se existir na versão escolhida, simplifica Property; se não, a abordagem "Number/Array com extras" cobre quase tudo, mas `typeof position` seria `object`.
5. **Rhino 1.8/1.9 em Android**: precisa de verificação de compatibilidade antes de subir de versão.
6. **Custo por quadro em aparelhos lentos**: o benchmark atual é de desktop JVM; falta medir num Android real.
7. **Nome das propriedades de Checkbox/Color/Point/Angle/Layer Control**: não confirmados (página da Adobe devolveu 403). Antes de implementar, conferir no AE ou na documentação oficial.
8. **O que não vale a pena**: máscaras, `sampleImage`, `footage()` MGJSON, Legacy ExtendScript. Sugiro deixar fora até pedirem.
