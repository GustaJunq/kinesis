# Efeitos customizados (.knsfx)

Um `.knsfx` é um efeito empacotado: importado no Kinesis, ele aparece em **Efeitos → Efeitos customizados** e se
aplica às camadas como qualquer efeito nativo, com interface de parâmetros, keyframes, prévia e exportação.
Exemplos prontos em [`examples/knsfx/`](../examples/knsfx) (fontes em `src/`, empacotados com `python3 tools/pack_knsfx.py`).

## Estrutura do ZIP

| Arquivo | Obrigatório | Conteúdo |
|---|---|---|
| `manifest.json` | sim | metadados e caminhos dos outros arquivos |
| `params.xml` | não | parâmetros e interface |
| `*.js` | um dos dois | script `animate()` (mexe no transform e na cor) |
| `*.frag` | um dos dois | fragment shader GLSL (mexe nos pixels) |
| `thumb.webp` / `thumb.png` | não | miniatura da lista |

Com script **e** shader, o script roda primeiro (no transform) e o shader depois (nos pixels).

Limites: 16 MB no total, 64 arquivos, 1 MB por texto. Caminhos são relativos à raiz do ZIP; um caminho absoluto,
com `..`, `\` ou `:` (no ZIP ou no manifest) recusa a importação.

## manifest.json

```json
{
  "format": "knsfx",
  "formatVersion": 1,
  "id": "dev.usuario.pulsate",
  "name": "Pulsate",
  "description": "Pulsa a escala da camada",
  "author": "usuario",
  "version": "1.0.0",
  "category": "transform",
  "tags": ["transform", "scale"],
  "thumb": "thumb.webp",
  "minKinesis": "0.2.6",
  "params": "params.xml",
  "script": "effect.js",
  "shader": "effect.frag"
}
```

| Campo | Tipo | Obrigatório | |
|---|---|---|---|
| `format` | `"knsfx"` | sim | |
| `formatVersion` | inteiro | sim | hoje `1`; um número maior pede um Kinesis mais novo |
| `id` | string | sim | domínio reverso (`dev.autor.efeito`), único na biblioteca; é o `type` do efeito no `.knsproj` |
| `name` | string | sim | até 80 caracteres |
| `script` / `shader` | caminho | pelo menos um | |
| `params`, `thumb` | caminho | não | |
| `description`, `author`, `version`, `tags` | | não | `version` aparece ao substituir |
| `category` | string | não | `transform`, `color`, `blur`, `distort`, `stylize`, `glitch`, `draw`, `procedural`, `keying`, `time`; outro valor = Utilitário |
| `minKinesis` | versão | não | maior que a do app → importação recusada com mensagem |

Campos desconhecidos são ignorados (compatibilidade com versões futuras).

## params.xml

```xml
<params>
  <selector id="axis" default="3" style="radio" label="Eixo">
    <choice label="X" value="0"/>
    <choice label="Y" value="1"/>
    <choice label="X,Y" value="2" enableTargets="damp"/>
    <choice label="X,-Y" value="3" enableTargets="damp"/>
  </selector>
  <spinner id="scale" default="1.0" min="0" max="1000" step="0.001" label="Escala" type="percent" ticks="0.5,1,1.5,2"/>
  <spinner id="damp" default="1.0" min="0" max="1000" step="0.01" label="Amortecimento" type="percent" enabled="false"/>
</params>
```

| Elemento | Valor | Atributos | Animável |
|---|---|---|---|
| `selector` | um dos `value` (texto) | `default`, `style` (`radio`/`dropdown`), `<choice label value enableTargets>` | não |
| `spinner`, `slider` | número | `default`, `min`, `max`, `step`, `type="percent"`, `ticks` | sim |
| `angle` | número em graus | `default`, `min`, `max` (padrão ±3600) | sim |
| `checkbox` | booleano | `default="true|false"` | não |
| `color` | cor | `default="#RRGGBB"` ou `#RRGGBBAA` | sim |
| `point` | vec2 em pixels | `default="x,y"` | sim |

- `id`: letras, números e `_`, começando por letra; único; vira o nome do uniform. `uTexture`, `uResolution`,
  `uTime`, `uProgress`, `vTexCoord` e `main` são reservados.
- `label`: texto mostrado. Strings `@am:string/...` não são suportadas: aparece o `id`.
- `type="percent"`: o valor guardado é multiplicado por 100 na tela e ganha `%` (1.0 aparece como 100%).
- `ticks`: marcas onde o slider encaixa ao chegar perto.
- `enableTargets` (em um `choice`): escolher essa opção habilita os parâmetros listados; os parâmetros citados em
  qualquer `enableTargets` de um selector ficam desabilitados (em cinza) nas outras opções. `enabled="false"` deixa
  um parâmetro desabilitado quando nenhum selector o habilita.
- Validação: ids únicos, `default` dentro de `min`/`max`, `min` ≤ `max`, números e cores bem formados,
  `default` do selector entre os `value`. `DOCTYPE`/`ENTITY` não são aceitos.

## Script (`animate`)

```js
function animate(env, el, p) {
  if (p.axis == 0) el.transform.scale.x *= p.scale;
  else if (p.axis == 1) el.transform.scale.y *= p.scale;
  else if (p.axis == 2) { el.transform.scale.x *= p.scale; el.transform.scale.y *= p.scale * p.damp; }
  else if (p.axis == 3) { el.transform.scale.x *= p.scale; el.transform.scale.y *= 1.0 - ((p.scale - 1.0) * p.damp); }
  return el;
}
```

Chamada a cada quadro, para cada camada com o efeito ligado:

| Argumento | Conteúdo |
|---|---|
| `env` | `time` (s, dentro do clipe), `frame`, `fps`, `duration` (do clipe), `progress` (0..1 no clipe), `width`, `height` (da composição) |
| `el` | cópia do estado da camada: `transform.position {x,y}`, `transform.scale {x,y}` (1 = 100%), `transform.rotation` (graus, horário), `transform.anchor {x,y}`, `transform.opacity` (0..1) e `fill {r,g,b,a}` (0..1; formas e texto) |
| `p` | parâmetros já interpolados pelos keyframes: números; `selector` como número quando o `value` é numérico, senão texto; `checkbox` booleano; `color {r,g,b,a}`; `point {x,y}` |

Devolva `el` (ou só altere `el`; o objeto alterado vale). Vários efeitos com script rodam em ordem, cada um recebendo
o resultado do anterior, depois das expressões da camada.

Limites e isolamento:
- Rhino interpretado, sem acesso a classes Java, rede, arquivos ou APIs do Android.
- O script é compilado uma vez e guardado em cache. Não conte com variáveis globais entre quadros: a ordem dos quadros
  na prévia e na exportação não é a mesma.
- Cada chamada tem 5 ms e 2 milhões de passos. Erro (exceção, sintaxe) desliga o efeito na camada na hora; passar do
  tempo desliga depois de 3 quadros seguidos. Em ambos os casos aparece um aviso; ligar o efeito de novo dá outra chance.
- Scripts agem em camadas 2D (e grupos); em camadas 3D são ignorados.

## Shader

```glsl
#version 300 es
precision highp float;
uniform sampler2D uTexture;   // a camada, já com os efeitos anteriores
uniform vec2 uResolution;     // tamanho do alvo em pixels
uniform float uTime;          // segundos dentro do clipe
uniform float uProgress;      // 0..1 dentro do clipe
uniform float intensity;      // um uniform por parâmetro, com o mesmo id
in vec2 vTexCoord;            // 0..1, origem embaixo à esquerda
out vec4 fragColor;
void main() { fragColor = texture(uTexture, vTexCoord); }
```

| Parâmetro | Uniform |
|---|---|
| spinner, slider, angle | `float` |
| selector | `int` (o `value` quando numérico, senão o índice da opção) |
| checkbox | `bool` |
| color | `vec4` (r, g, b, a) |
| point | `vec2` em pixels do alvo, mesma orientação de `vTexCoord * uResolution` |

- GLSL ES 3.00 (`#version 300 es`) é o formato recomendado. Shaders no estilo 1.00 (`varying`, `texture2D`,
  `gl_FragColor`, sem `#version`) são traduzidos para 3.00 automaticamente. Sem `precision`, entra `highp float`.
- A textura é **pré-multiplicada** pelo alfa; devolva cor pré-multiplicada também.
- O shader é compilado na importação, no aparelho: se não compilar, a importação é recusada e o log do compilador
  aparece na tela.
- Ele entra no encadeamento de efeitos que o app já usa (um alvo por passe), na posição em que o efeito está na lista,
  e roda igual na prévia e na exportação (na prévia o alvo pode ser menor: use `uResolution`).

## Biblioteca e projetos

- Os efeitos importados ficam no armazenamento interno do app e aparecem em **Efeitos customizados** (miniatura,
  nome, autor, categoria), de onde podem ser removidos. Importar um `id` que já existe pergunta se quer substituir,
  mostrando a versão instalada e a nova.
- No `.knsproj`, o efeito aparece em `effects[]` com `type` igual ao `id` do pacote e os parâmetros pelo nome,
  como os nativos. O pacote vai junto em `effects/<id>.knsfx`, então o projeto abre em outro aparelho, ou depois de
  remover o efeito da biblioteca. Se o pacote faltar, o efeito fica no projeto como está e não é desenhado.
