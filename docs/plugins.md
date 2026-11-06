# Plugins do Kinesis Editor

Este guia serve para pessoas e para agentes de IA que vão criar plugins. Tudo aqui é **offline**: um plugin nunca acessa
a internet, outros apps, a câmera ou arquivos fora dele mesmo.

## Resumo para agentes

1. Um plugin é uma pasta zipada como `nome.knsplugin` com `manifest.json` na raiz.
2. Três tipos instaláveis: `presets` (só JSON), `script` (JavaScript) e `data` (dados para um plugin oficial).
   `native` existe só dentro do app (o plugin oficial "Texto para fala").
3. Valide sempre empacotando e abrindo no app; os exemplos prontos estão em `plugins/examples/`.
4. Erros comuns: `id` fora do formato `com.autor.nome`, `minKinesisVersion` maior que o app, função do comando ausente
   no script, caminho com `..`, permissão `network` (não existe).

## Pacote

```
meu-plugin.knsplugin   (zip)
├── manifest.json
├── presets/basicos.json     (type presets/script)
├── main.js                  (type script)
└── assets/...               (qualquer arquivo; só .json .js .txt .md são legíveis pelo script)
```

### manifest.json

| Campo | Obrigatório | Descrição |
|---|---|---|
| `format` | sim | sempre `"kinesis-plugin"` |
| `formatVersion` | sim | `1` |
| `id` | sim | `com.autor.nome` (minúsculas, dígitos, `_`, pontos) |
| `name`, `author` | sim | texto mostrado ao usuário (nome até 60 caracteres) |
| `version` | sim | `x.y.z` |
| `minKinesisVersion` | sim | versão mínima do app, `x.y.z` |
| `type` | sim | `presets`, `script` ou `data` |
| `permissions` | não | lista; veja abaixo |
| `description` | não | texto |
| `presets` | presets | arquivos de presets (lista de caminhos) |
| `script`, `commands` | script | arquivo `.js` e os comandos que ele expõe |
| `extends` | data | id do plugin oficial que usa os dados |

Permissões: `project.read` (ler o projeto e a seleção), `project.write` (devolver um projeto alterado).
`media.write` e `speech` são só de plugins oficiais. O app mostra as permissões antes de instalar.

## Presets (JSON declarativo)

```json
{ "presets": [
  { "id": "subir", "name": "Subir surgindo", "kind": "animation",
    "properties": {
      "opacity":  [ { "t": 0, "v": 0, "easing": "easeOutCubic" }, { "t": 0.5, "v": 1 } ],
      "position": [ { "t": 0, "v": [0, 80] }, { "t": 0.6, "v": [0, 0] } ] } },
  { "id": "amarelo", "name": "Título amarelo", "kind": "textStyle", "style": { "fontSize": 140, "color": "#FFD23F" } },
  { "id": "slide", "name": "Desliza", "kind": "transition", "in": { "type": "slideUp", "duration": 0.4 } }
] }
```

- `animation`: propriedades `opacity` (absoluto 0..1), `position` (px somados), `scale` (fatores multiplicados) e
  `rotation` (graus somados). `t` em segundos a partir do ponto de aplicação (a agulha). `easing` é um id do
  `.knsproj` ou `[x1,y1,x2,y2]`.
- `textStyle`: campos de texto (`font`, `fontSize`, `color`, `align`, `lineHeight`, `letterSpacing`, `maxWidth`, `stroke`,
  `animators`, `propertyAnimators`), como no `.knsproj`.
- `transition`: `in` e/ou `out` como no `.knsproj`.

## Scripts (JavaScript)

Rodam num motor Rhino embutido, **num processo separado** (`:plugins`). Limites por execução: tempo (10 s, com watchdog
que encerra o processo), passos de interpretação e tamanho do resultado. Erro, estouro de memória ou travamento só
produzem uma mensagem; o app e o projeto não são afetados. Sem classes Java, arquivos, rede ou `Date`.

```json
"commands": [ { "id": "escalonar", "title": "Escalonar seleção", "function": "escalonar",
  "params": [ { "id": "intervalo", "type": "number", "label": "Intervalo (s)", "default": 0.2, "min": 0, "max": 5 } ] } ]
```

Tipos de parâmetro: `number` (com `min`/`max` vira slider), `text`, `boolean`, `choice` (`options`).

A função recebe um objeto: `{ apiVersion, time, selection, params, project }` (`project` só com `project.read`;
`selection` também) e devolve `{ project?, message? }` (`project` só com `project.write`). O projeto é o `.knsproj`
(veja [knsproj.md](knsproj.md)). O resultado é validado: projeto inválido, ou com mídia diferente da original, é
recusado. Uma alteração aceita vale uma etapa de desfazer.

API do objeto global `kinesis`: `log(...)`, `readText(caminho)` (arquivos de texto do próprio plugin), `newId(prefixo)`,
`apiVersion`.

Exemplos completos: `plugins/examples/presets-basicos` e `plugins/examples/escalonar`.

## Instalar, ativar, remover

- Abrir o `.knsplugin` pelo gerenciador de arquivos do Android, ou Configurações → Plugins → Instalar de arquivo.
- O app valida o manifesto, os arquivos, os presets e a sintaxe do script, e mostra as permissões antes de instalar.
- Na tela de Plugins dá para desativar, desinstalar e (para os oficiais) reinstalar.

## Texto para fala (plugin oficial) e Kokoro

Vem instalado; usa as vozes **offline** do Android (pt-BR primeiro). Aba Plugins → Texto para fala: texto, voz,
velocidade; o áudio vira uma camada de áudio na agulha. Pacote Kokoro (vozes pf_dora, pm_alex, pm_santa; Apache-2.0,
modelo ~90 MB): é um plugin `data` com `extends: "dev.kinesis.tts"`; a integração com o motor ONNX ainda não está
nesta versão (o app procura o pacote, mas sem o motor ele não é usado).
