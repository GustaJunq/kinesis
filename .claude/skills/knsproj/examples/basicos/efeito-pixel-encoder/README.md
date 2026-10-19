# Pixel Encoder (`pixelEncoder`)

## O que demonstra
- `pixelEncoder` divide a imagem em células de `cellSize` px e desenha em cada uma um caractere escolhido pela luminância média da célula (do espaço ao `@`, no conjunto `ascii`).
- `charset`: `ascii`, `numbers` ou `blocks` (barras preenchidas de baixo para cima). O degradê usa `fgColor` e `bgColor`; o título usa a cor da imagem (`useSourceColor`) sobre fundo transparente.
- `invert` troca claro e escuro (útil sobre fundo claro).

## Quando usar
- Efeito de terminal, "Matrix", retratos em ASCII e transições em que a imagem vira texto (anime `cellSize`).

## Armadilhas
- Células muito pequenas (< 6 px) deixam os caracteres ilegíveis; células grandes mostram poucos caracteres.
- O alfa da camada é mantido por célula: partes transparentes da camada continuam transparentes, mesmo com `bgColor` opaco.
- O atlas de caracteres é criado uma vez por conjunto e reaproveitado em todos os quadros; as formas dos caracteres vêm da fonte monoespaçada do aparelho (`blocks` é desenhado sem fonte).
