# Changelog

Todas as versões do Kinesis Editor. As notas completas de cada release ficam em [docs/releases/](docs/releases).

## Não lançado

### Adicionado
- CLI: `knsproj preview` desenha quadros aproximados de um projeto (contact sheet, PNGs por tempo ou GIF) para o agente
  da skill `/knsproj` conferir o resultado antes de entregar. Reimplementa em Python a avaliação de cena do core
  (tempos, easings, loops, pais, grupos, transições, animadores de texto, máscaras, mattes, câmera e luzes) com um
  renderizador em software (Pillow + numpy; ffmpeg opcional para vídeo) — funciona igual no desktop e em sandbox.
  Lista no fim o que não foi simulado (expressões, sombras, DOF, a maioria dos efeitos de shader…).

## 0.1.8-hotfix-2 — 2026-10-20

Formato `.knsproj` **v11** (abre v1 a v10; só um efeito novo — projetos antigos ficam idênticos).

### Adicionado
- **Copiar fundo** (`copyBackground`, em *Utilitário e expressão*): copia o que está embaixo da camada para dentro dela, e a camada vira uma camada de ajuste. Os efeitos que vêm depois dele atuam nos pixels de fundo cobertos pelas partes opacas da camada; máscaras, `feather` e opacidade recortam a região. `amount` mistura a camada com o fundo. Uma forma que cobre o quadro, com `copyBackground` e um efeito de cor, funciona como a camada de ajuste de outros editores. Exemplo: `basicos/efeito-copiar-fundo` da skill `/knsproj`.

## 0.1.8-hotfix — 2026-10-19

### Corrigido
- **Preview sem borda preta:** com camadas no projeto, a área fora do quadro da composição passa a ter a cor da tela, sem o painel escuro e arredondado de antes. O painel escuro só aparece com o projeto vazio, e só com o texto "No layers currently" ("Nenhuma camada no momento").

## 0.1.8 — 2026-10-19

Formato `.knsproj` **v10** (abre v1 a v9; só efeitos novos — projetos antigos ficam idênticos).

### Adicionado
Pacote de efeitos retrô e de glitch (um passe de shader cada, sem estado entre quadros, todos os números animáveis;
o alfa da camada é respeitado). Todo sorteio vem de um hash determinístico (posição + tempo da camada + `seed`), então
prévia e exportação saem idênticas, e nada depende do fps da prévia.
- **Estilizar:**
  - **8-bit** (`eightBit`): pixeliza em grade e reduz as cores (níveis por canal, tons de cinza ou paleta fixa de 16 cores de console).
  - **PixDither** (`pixDither`): dithering ordenado com matriz de Bayer 2×2, 4×4 ou 8×8, em cores ou em duas cores.
  - **Pixel Encoder** (`pixelEncoder`): cada célula vira um caractere escolhido pela luminância (ASCII, números ou blocos), com atlas de glifos criado uma vez.
  - **Kernel** (`kernel`): convolução 3×3 com nitidez, relevo, detecção de bordas, contorno e 9 pesos personalizados.
- **Glitch:**
  - **Signal Analog** (`signalAnalog`): linhas deslocadas por ruído, separação RGB, scanlines e chuvisco.
  - **Glitchify** (`glitchify`): blocos deslocados, separação RGB por bloco e blocos que ficam transparentes; `speed` 0 congela o glitch.
  - **Dissolve Shake** (`dissolveShake`): grade de blocos que tremem e somem com o `progress`.
- Exemplos de cada efeito em `basicos/efeito-*` da skill `/knsproj`, no schema v10 e na tabela de efeitos de `docs/knsproj.md`.

### Alterado
- Editor: voltar, play e avançar são três botões redondos separados, acima da barra de ferramentas.
- Editor: o preview usa a largura toda (a composição, de qualquer proporção, ocupa o máximo que a tela deixa) e a barra de vistas (câmera, editor, entrar na câmera) flutua sobre ele; um botão no canto direito da linha de controles abre e fecha essa barra, e duplicar fica junto do refazer.
- Timeline: botão na régua para esconder os nomes das camadas.
- O job do emulador voltou a rodar em todo push e PR; o log do aparelho passa a ser gravado no runner e o job não fica mais preso quando o emulador cai.

## 0.1.7 — 2026-10-19

Novo visual da tela de edição. Sem funções novas e sem mudança de formato (`.knsproj` continua na v9).

### Alterado
- **Tela de edição redesenhada:** cabeçalho com voltar, configurações do projeto (formato, FPS, resolução) e Exportar; preview centralizado
  com cantos arredondados; linha de controles (marcador, desfazer · tempo · refazer, duplicar); timeline com uma faixa por camada,
  clipes com início e fim reais, nome só no clipe, keyframes como losangos brancos, cursor escuro e marcadores em roxo; aba do play
  sobre a barra de ferramentas.
- **Barra de ferramentas rolável:** única parte com inclinação (~15°), com o último item sumindo num degradê, seta "›" e indicador de rolagem.
  *Legendas* passou do cabeçalho para a barra de ferramentas.
- **Câmera / vista do editor:** os quatro botões sobre a cena viraram um seletor vertical ao lado do preview (câmera, vista do editor e
  *Entrar na câmera*; dentro da câmera, o terceiro botão liga o auto keyframe). *Recentrar* agora é toque duplo no preview.
- Paleta nova do editor (roxo só para a ação principal e o estado selecionado) e ícones Lucide (ISC) como vetores locais.

## 0.1.6-hotfix — 2026-10-18

### Corrigido
- **Importação de `.aureaproj`:** texto 3D do Aurea virava cubos; agora vira texto 3D de verdade (conteúdo, alinhamento,
  profundidade, chanfro, cor e material). A câmera do Aurea, antes ignorada, é importada com posição, rotação e keyframes (curvas Bézier incluídas) e campo de visão fixo.
  Tamanho da fonte e chanfro são aproximados; rotação/keyframes dos objetos ainda não são lidos (o aviso da importação diz isso).

## 0.1.6 — 2026-10-18

Formato `.knsproj` **v9** (abre v1 a v8; só campos novos — `editorView`, `video.roto` e o efeito `timeSlice` — projetos antigos ficam idênticos).

### Adicionado
- **FPS personalizável.** Um controle deslizante de 12 a 60 fps (números inteiros) no painel da composição e na criação de
  projeto, no lugar das predefinições. Sem mudança de formato (`composition.fps` já aceitava qualquer valor).
- **TimeSlice** (efeito, categoria Tempo): cada faixa da imagem mostra um momento diferente do vídeo. Parâmetros animáveis:
  faixas (2–24), direção, deslocamento em quadros entre faixas (0–30), ordem (para frente, para trás, do centro) e
  interpolação (degraus ↔ mistura suave). Usa um cache de quadros limitado (LRU) para não decodificar o vídeo de novo e,
  em aparelho fraco, a prévia usa menos faixas, guarda só alguns quadros e refina quando para; a exportação é exata.
- **Visualizador de câmera.** Na prévia: *Câmera* (a imagem exportada), *Vista do editor* (câmera livre: um dedo orbita, dois
  dedos deslizam e dão zoom; a câmera da cena aparece como uma pirâmide, com o quadro da composição e os eixos) e *Entrar
  na câmera* (os gestos movem a câmera de verdade; com *Auto keyframe* ligado gravam keyframes). *Sair da câmera* volta à
  vista do editor sem mexer nela. A vista fica salva no projeto (`editorView`), fora do que é renderizado e sem passos de
  desfazer.
- **Real Camera.** A câmera 3D segue o movimento do celular: vetor de rotação do jogo (ou vetor de rotação, ou giroscópio
  integrado), contagem 3-2-1 e gravação enquanto a timeline toca. A pose ao tocar em *Gravar* vira a rotação atual da
  câmera; intensidade, travar eixos X/Y/Z, suavização, simplificar curva (menos keyframes, mesma forma) e regravar só um
  trecho (os keyframes de fora ficam). Posição opcional e experimental. Escondido em aparelhos sem sensor.
- **Roto Brush.** Recorta qualquer objeto pintando sobre o vídeo: pincel adicionar/remover, tamanho, desfazer pincelada,
  visões Final / Sobreposição / Máscara, tira de quadros com recorte, progresso e cancelamento. O objeto é seguido para
  frente e para trás a partir dos quadros pintados; pintar de novo corrige dali em diante. O resultado é uma camada com
  alfa (`alphaPacked`) acima da original, que fica oculta; as pinceladas ficam em `video.roto` para reabrir.
- **Importar projetos do Aurea** (`.aureaproj`) pelo mesmo botão de abrir projeto: composição, tempos e formas 3D
  (posição, escala, primitivo e cor). O que ainda não é convertido aparece num aviso depois da importação. Formato
  mapeado em [docs/aureaproj.md](docs/aureaproj.md).

### Notas técnicas
- Modelo do Roto Brush: Interactive Segmenter do MediaPipe com o modelo Magic Touch (Apache-2.0). Ele recebe **um ponto**
  por pergunta; as pinceladas são aplicadas sobre a resposta do modelo e vários pontos são perguntados um a um.

## 0.1.5 — 2026-10-18

Formato `.knsproj` **v8**, sem mudança: tudo o que a versão gera usa campos que já existiam (imagens PNG, `video.alphaPacked`).
Projetos antigos abrem idênticos.

### Adicionado
- **Equalizador gráfico** na aba Áudio. Pontos arrastáveis sobre a curva de resposta (X = frequência, escala log 20 Hz–20 kHz;
  Y = ganho, ±18 dB). São cinco pontos (graves, médios e agudos); tocar num ponto o seleciona e o Q dele fica num controle discreto abaixo
  do gráfico (1,0 por padrão; 0,7 nas pontas). Arrastar fora dos pontos continua rolando o painel. Sumiram a linha de presets e os campos numéricos; "Restaurar
  padrão" continua. Botão **▶ ouvir** toca o clipe com o EQ aplicado e as mudanças soam em tempo real.
- **Remover fundo** (imagem e vídeo), no aparelho: MediaPipe Image Segmenter + modelo Selfie Segmenter (Apache-2.0).
  Vídeo é processado quadro a quadro com suavização temporal das bordas, barra de progresso e cancelamento. O resultado
  é uma nova camada (PNG com alfa, ou vídeo `alphaPacked`) logo acima da original, que fica oculta. **Só recorta pessoas.**
- **Estabilização de vídeo** com vid.stab (GPL-2.0 ou posterior), em duas passadas (`vidstabdetect` / `vidstabtransform`),
  com controles de suavização, tremor e corte/zoom automático; progresso e cancelamento; o som é copiado sem recodificar.
  O resultado é uma nova camada acima da original (oculta).
- **Créditos de terceiros** em Ajustes → Licenças de terceiros (vid.stab, GPL-3.0, MediaPipe/modelo, Apache-2.0, fontes).
- **Navegação por keyframes** nas setas anterior/próximo: percorre os keyframes de **todas** as propriedades da camada
  selecionada; keyframes no mesmo instante em propriedades diferentes são uma única parada. O botão "− ◆" remove os
  keyframes da parada mais próxima do cursor, sem precisar estar exatamente em cima (a tolerância acompanha o zoom).

## 0.1.4-hotfix — 2026-10-17

Formato `.knsproj` **v8** (abre v1 a v7; só campos novos, projetos antigos ficam idênticos).

### Adicionado
- **Marcadores de tempo.** Botão na régua (ao lado de "ajustar") adiciona um marcador no cursor; toque longo na régua
  também. Tocar num marcador leva o cursor até ele; toque longo edita (nome, cor, mover para o cursor, excluir).
  Os botões anterior/próximo ao lado do Play agora vão ao marcador ou keyframe mais próximo. Marcadores são pontos de
  ímã ao arrastar clipes e ficam salvos no `.knsproj`.
- **Extrair áudio** de uma camada de vídeo: o som vira uma camada de áudio própria (mesmo lugar e corte), copiado sem
  recodificar para um `.m4a` do projeto. O vídeo original não é alterado e sua camada é silenciada para não soar em
  dobro. Vídeos sem áudio e codecs que não cabem no MP4 (só AAC e AMR) são avisados.
- **Painel de áudio** (botão "Áudio" para camadas com som): waveform, volume, fade in/out, silenciar e **equalizador
  paramétrico** (até 8 bandas: pico, shelf grave/agudo; frequência, ganho e Q) com presets Flat, Bass Boost, Treble
  Boost e Vocal, curva de resposta e restaurar padrão. Não destrutivo, ouvido no preview e aplicado na exportação.
- O botão **+ ◆** agora é contextual: adiciona o keyframe no cursor ou, se já houver um, remove (vira "− ◆"
  preenchido). Um único passo de desfazer; o gerenciamento de keyframes do painel de propriedades continua.

### Corrigido
- **Zoom da timeline.** Com zoom alto, um clipe mais largo que a tela era espremido (a largura do clipe era limitada
  pela largura da trilha e depois deslocada pela rolagem, então ele parecia encolher). Agora toda conversão
  tempo↔pixel passa por `TimelineScale`: o clipe mantém o tamanho real (duração × pixels por segundo), só a parte
  visível é desenhada, e régua, waveform, keyframes, marcadores e cursor usam a mesma escala.

## 0.1.4 — 2026-10-17

Formato `.knsproj` **v7** (abre v1 a v6; projetos antigos ficam idênticos).

### Adicionado
- **Legendas automáticas.** O Whisper (whisper.cpp) reconhece a fala **no próprio aparelho**, sem API externa e sem
  internet depois de baixar um modelo (ou importar o arquivo). As legendas são um grupo de camadas de texto na
  timeline: edite texto e tempos, divida/junte, mude tamanho, posição, cor e contorno; importe e compartilhe `.srt`.
  Saem na exportação como qualquer outra camada.
- **Núcleo nativo em Rust** (`native/`): extrusão de texto 3D e o motor do Whisper, via JNI, com a versão em Kotlin como
  reserva. O restante do core continua em Kotlin porque, medido, não era gargalo (ver `docs/desenvolvimento.md`).

### Mudado
- **Cíclico: o `offset` agora é a assimetria (skew) de cada ciclo**, não um deslocamento. Negativo: sobe rápido e desce
  devagar; 0: simétrico; positivo: sobe devagar e desce rápido. Mínimo, máximo, número e duração dos ciclos não mudam e
  a curva continua contínua. O deslocamento antigo passa a se chamar `phase` (migração v6→v7 automática).
- O app agora pede a permissão de internet, usada somente para baixar o modelo de fala quando você pede.

## 0.1.3-hotfix — 2026-10-16

Sem mudança de formato (`.knsproj` v6).

### Corrigido
- **Cenas 3D com muitos objetos travavam.** O motion blur reavaliava a cena inteira para cada objeto e cada
  sub-quadro; agora cada instante é avaliado uma vez por quadro.

### Melhorado
- Objetos 3D com motion blur que ficam parados durante o obturador são desenhados sem o custo do desfoque.
- Objetos totalmente fora do enquadramento da câmera deixam de ser desenhados (câmera e profundidade de campo).
- Menos trabalho por objeto: malhas calculadas uma vez por quadro, material e sombreamento só reenviados à GPU
  quando mudam, ordenação de objetos translúcidos e cálculo do shadow map sem alocações.

## 0.1.3 — 2026-10-16

Formato `.knsproj` **v6** (abre v1 a v5).

### Adicionado
- Texto 3D extrudado (profundidade, bevel); sombras de luzes direcionais/pontuais; iluminação por panorama (HDRI);
  profundidade de campo na câmera; sombreamento flat, toon e wireframe; qualidade 3D do preview separada da exportação.
- Editor de gráfico com alças Bézier; presets de easing salvos, copiar/colar curva, aplicar a todos os trechos;
  copiar/colar keyframes entre camadas.
- Últimas 10 versões de cada projeto, restauráveis; oferta de reabrir após fechamento inesperado; salvamento ao sair
  do app; cópia opcional em `Documentos/Kinesis`.
- Exportação em blocos retomáveis com tempo estimado; relatório de falha com instante e camada; pré-renderizar
  camada (vídeo com alfa empacotado, `video.alphaPacked`).
- Preview com resolução ajustável, cache de quadros, decodificador compartilhado por arquivo, liberação de recursos
  fora de uso e proxies opcionais para vídeos pesados.
- Ajustes: prévia, qualidade 3D, proxies e cópia em Documentos.

### Corrigido
- Contornos sobrepostos de fontes variáveis viravam buracos no texto 3D.

### Documentação
- README simplificado para download; guia de desenvolvimento em `docs/desenvolvimento.md`; especificação, schema,
  CLI e skill `/knsproj` na versão 6.

## 0.1.2 — 2026-10-15

Formato `.knsproj` **v5**.

- Curva cíclica (onda entre dois keyframes) no editor de curvas.
- Camadas nulas 2D e 3D; câmera apontada por rotação, independente da posição.
- Trocar ou remover o pai de uma camada não a move mais na tela.

## 0.1.1 — 2026-10

Formato `.knsproj` **v3**.

- Expressões JavaScript no estilo do After Effects e animadores de texto por caractere.
- 60 efeitos em 13 categorias, interface redesenhada, tema claro/escuro, português/inglês, apresentação inicial e novo logo.
- Pipeline de release com assinatura de produção verificada.

## 0.1.0-beta

Primeira beta pública: timeline com keyframes e curvas, texto, formas, mídia, efeitos, máscaras, transições, motion
blur, 3D simplificado, exportação MP4 e o formato aberto `.knsproj`.
