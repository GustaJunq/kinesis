# 8-bit (`eightBit`)

## O que demonstra
- `eightBit` pixeliza em grade (`pixelSize`) e reduz as cores. Aqui o degradê passa de pixels pequenos a grandes (keyframes) com 4 níveis por canal.
- `paletteMode`: `free` usa `colorLevels` níveis por canal, `gray` quantiza em tons de cinza e `console` usa uma paleta fixa de 16 cores (o título).
- O alfa da camada é preservado: só as cores mudam.

## Quando usar
- Visual de videogame antigo; transições em que a imagem "desmancha" em pixels (anime `pixelSize`).

## Armadilhas
- `pixelSize` é em pixels da composição; valores baixos com `colorLevels` altos quase não mudam a imagem.
- No modo `console`, `colorLevels` não é usado.
- O efeito amostra o centro de cada célula (não faz média): detalhes menores que a célula somem.
