---
name: knsproj
description: Gera projetos editáveis do Kinesis Editor (.knsproj) a partir de uma descrição em linguagem natural — composição, camadas de texto/formas/mídia, objetos 3D, materiais, câmera, luzes, keyframes e curvas — valida contra o schema oficial e empacota o arquivo pronto para importar no app Android. Use quando o usuário pedir uma animação, vídeo, motion graphics, título animado ou cena 3D "para o Kinesis", ou invocar /knsproj.
argument-hint: "<descrição da animação>"
---

# /knsproj — gerar projetos do Kinesis

Você vai transformar o pedido do usuário em um projeto `.knsproj` **válido e editável**: tudo deve continuar
ajustável no app (camadas separadas, keyframes reais, nada "achatado"). O trabalho é feito localmente, sem serviços
do Kinesis: só arquivos JSON, a CLI `tools/knsproj` (Python 3, biblioteca padrão) e o schema em `schema/`.

Referências (leia quando precisar de detalhes):
- Especificação: `docs/knsproj.md`
- Schema oficial: `schema/knsproj-v1.schema.json`
- Exemplos completos: `examples/metal-cube`, `examples/kinetic-title`, `examples/solar-system`

## Fluxo

1. **Interpretar o pedido.** Extraia: proporção (vertical 9:16 → 1080×1920; horizontal 16:9 → 1920×1080;
   quadrado → 1080×1080; 4:5 → 1080×1350), duração, fps (30 por padrão), cores, textos exatos, elementos 2D/3D,
   estilo e ritmo. Se algo essencial for ambíguo, escolha um padrão sensato e diga qual escolheu no fim — não
   interrompa o usuário por detalhes.
2. **Criar a pasta do projeto:**
   ```bash
   python3 tools/knsproj new generated/<slug> --title "<Título>" --width 1080 --height 1920 --fps 30 --duration 10 --background "#0A2463"
   ```
3. **Adicionar mídias fornecidas pelo usuário** (imagens, vídeos, áudio, GLB, fontes) — nunca invente arquivos:
   ```bash
   python3 tools/knsproj add-asset generated/<slug> caminho/do/arquivo.png   # imprime o id do asset
   ```
   Sem arquivos do usuário, construa o visual com texto, formas, gradientes, efeitos e primitivas 3D.
4. **Escrever as camadas** editando `generated/<slug>/project.json` (seção "Referência rápida").
5. **Validar** e corrigir até não haver erros:
   ```bash
   python3 tools/knsproj validate generated/<slug>
   ```
   As mensagens trazem o caminho JSON do problema (ex.: `$.layers[3].text.fontSzie: unknown property (did you mean 'fontSize'?)`).
   Trate também os avisos: normalmente indicam intenção errada.
6. **Conferir o resultado** com `python3 tools/knsproj info generated/<slug>` (árvore de camadas e tempos) e revise
   mentalmente a linha do tempo: tudo entra, permanece legível e sai dentro de `composition.duration`?
7. **Empacotar:**
   ```bash
   python3 tools/knsproj pack generated/<slug> -o generated/<slug>.knsproj
   ```
8. **Entregar:** informe o caminho do `.knsproj`, um resumo (formato, duração, camadas principais, decisões
   tomadas) e como importar: *app Kinesis → tela inicial → ícone "Importar .knsproj" → escolher o arquivo*.

## Referência rápida do formato

- Origem no **centro** da composição; +x direita, +y **para baixo**, +z se afasta da câmera. Unidades: pixels.
- Tempos em segundos. **Keyframes usam tempo relativo ao `start` da camada.**
- Camadas em ordem de pintura: a primeira fica **embaixo**. Ids únicos em todo o projeto.
- Cor: `"#RRGGBB"` ou `"#RRGGBBAA"`. Escala: `1` = 100%. Rotação em graus (horário). Opacidade 0..1.
- Propriedade animável: valor simples **ou** `{"keyframes": [{"time": 0, "value": …, "easing": "easeOutCubic"}, …], "loop": "cycle"}`.
  O `easing` vale para o segmento que **começa** naquele keyframe.

Esqueleto de camada (campos comuns): `id`, `type`, `name`, `start`, `duration`, `parent`, `blendMode`,
`motionBlur`, `transform` (2D) ou `transform3d` (3D), `effects`, `masks`, `matte`, `transitionIn`, `transitionOut`,
e o objeto de conteúdo com o **mesmo nome do tipo**.

```jsonc
// Texto com entrada por caractere
{ "id": "titulo", "type": "text", "name": "Título", "start": 0.3, "duration": 9.7,
  "transform": { "position": [0, -620] },
  "text": { "content": "OLÁ", "font": { "family": "sans-serif", "weight": 900 }, "fontSize": 150, "color": "#FFFFFF",
            "letterSpacing": 0.08,
            "animators": [{ "type": "slideUp", "unit": "character", "stagger": 0.05, "unitDuration": 0.5, "easing": "easeOutCubic", "amount": 90 }] },
  "transitionOut": { "type": "fade", "duration": 0.5 } }

// Forma com gradiente e brilho
{ "id": "fundo", "type": "shape", "start": 0, "duration": 10,
  "shape": { "kind": "rectangle", "size": [1080, 1920],
             "fill": { "gradient": { "type": "radial", "start": [0, 0], "end": [0, 1100],
                                     "stops": [{ "offset": 0, "color": "#1E4FBF" }, { "offset": 1, "color": "#061638" }] } } } }

// Linha que se desenha (trim path)
{ "id": "linha", "type": "shape", "start": 1, "duration": 5,
  "shape": { "kind": "path", "path": { "closed": false, "points": [{ "point": [-300, 0] }, { "point": [300, 0] }] },
             "fill": { "enabled": false }, "stroke": { "color": "#FFC857", "width": 8 },
             "trim": { "end": { "keyframes": [{ "time": 0, "value": 0, "easing": "easeInOutCubic" }, { "time": 1, "value": 1 }] } } } }

// Cubo metálico girando
{ "id": "cubo", "type": "object3d", "start": 0, "duration": 10, "motionBlur": true,
  "transform3d": { "rotation": { "keyframes": [{ "time": 0, "value": [-25, 0, 0] }, { "time": 10, "value": [-25, 360, 0] }] } },
  "object3d": { "primitive": "cube", "size": 420,
                "material": { "color": "#DDE3EA", "metallic": 1, "roughness": 0.22 } } }

// Órbita: filho de um "empty" que gira (loop infinito)
{ "id": "pivo", "type": "object3d", "duration": 10, "object3d": { "primitive": "empty" },
  "transform3d": { "rotation": { "keyframes": [{ "time": 0, "value": [0, 0, 0] }, { "time": 4, "value": [0, 360, 0] }], "loop": "cycle" } } }
{ "id": "lua", "type": "object3d", "duration": 10, "parent": "pivo", "transform3d": { "position": [380, 0, 0] },
  "object3d": { "primitive": "sphere", "size": 80, "material": { "color": "#C9CCD3", "roughness": 0.9 } } }

// Câmera e luzes
{ "id": "camera", "type": "camera", "duration": 10, "camera": { "position": [0, -250, -2600], "target": [0, 0, 0], "fov": 40 } }
{ "id": "luz", "type": "light", "duration": 10, "light": { "kind": "directional", "intensity": 1.3, "position": [-900, -1300, -1400] } }
{ "id": "ambiente", "type": "light", "duration": 10, "light": { "kind": "ambient", "color": "#A8C0FF", "intensity": 0.3 } }
```

Valores possíveis (o schema é a fonte da verdade):
- `type` de camada: `text`, `shape`, `image`, `video`, `audio`, `group`, `object3d`, `model3d`, `camera`, `light`.
- `shape.kind`: `rectangle`, `ellipse`, `polygon`, `star`, `path`. `primitive`: `cube`, `sphere`, `cylinder`, `cone`, `torus`, `plane`, `empty`.
- Efeitos (`effects[].type`): `blur` (radius), `colorAdjust` (brightness, contrast, saturation, hue), `tint` (color, amount),
  `dropShadow` (color, opacity, angle, distance, softness), `glow` (color, radius, intensity), `invert` (amount),
  `pixelate` (size), `chromaticAberration` (amount, angle), `vignette` (amount, softness).
- Transições: `fade`, `slideLeft`, `slideRight`, `slideUp`, `slideDown`, `zoomIn`, `zoomOut`, `spin`, `blur`, `wipeLeft`, `wipeRight`.
- Animadores de texto: `typewriter`, `fade`, `slideUp`, `slideDown`, `slideLeft`, `slideRight`, `scale`, `rotate`, `wave`;
  `unit`: `character` | `word` | `line`; `order`: `forward` | `backward` | `center` | `random`; `mode`: `in` | `out`.
- Easings: `linear`, `hold`, `ease`, `easeIn`, `easeOut`, `easeInOut`, `easeIn/Out/InOut` + `Sine|Quad|Cubic|Quart|Quint|Expo|Circ|Back|Bounce|Elastic`,
  ou `{"cubicBezier": [x1, y1, x2, y2]}`.
- Track matte: `"matte": {"layer": "<id irmão>", "mode": "alpha"}` — a camada-fonte some automaticamente.
- Máscaras: `"masks": [{"shape": "ellipse", "mode": "add", "size": [600, 400], "feather": 30}]`.

## Boas práticas de motion design

- **Hierarquia temporal:** fundo primeiro, elemento principal em seguida, textos por último; escalone entradas
  (0.1–0.3 s entre elementos) em vez de fazer tudo aparecer junto.
- **Easing:** entradas com `easeOutCubic`/`easeOutExpo`/`easeOutBack`, saídas com `easeInCubic`, movimentos de ida e
  volta com `easeInOut*`. Rotação contínua: dois keyframes `linear` (ou `loop: "cycle"`).
- **Leitura:** cada texto fica legível por pelo menos ~1,5 s. Em 1080×1920 use 120–180 px para títulos e 40–64 px para
  apoio; mantenha textos dentro de ±470 px em x e ±820 px em y (margem de segurança).
- **3D:** uma câmera levemente acima (`y` negativo) e afastada dá profundidade; combine luz principal, contraluz
  colorida e ambiente fraca. Metais ficam bons com `metallic: 1` e `roughness` 0.15–0.35. Primitivas `empty` servem de
  pivô para órbitas e grupos 3D.
- **Profundidade 2D:** halos com `blur` grande, sombras suaves (`dropShadow`) e `motionBlur: true` em elementos rápidos.
- Termine animações antes do fim da composição ou use `transitionOut` para sair de forma limpa.
- Prefira poucos keyframes bem escolhidos a muitos keyframes lineares.

## Checklist antes de entregar

- [ ] `validate` sem erros (e avisos compreendidos)
- [ ] Todos os `parent`/`matte` apontam para irmãos do mesmo contêiner; 2D só com 2D, 3D só com 3D
- [ ] Assets copiados com `add-asset`; nenhum caminho fora de `assets/`
- [ ] Tempos de keyframe relativos ao início de cada camada
- [ ] `.knsproj` gerado com `pack` e caminho informado ao usuário
