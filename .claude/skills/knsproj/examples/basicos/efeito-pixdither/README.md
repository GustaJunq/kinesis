# PixDither (`pixDither`)

## O que demonstra
- `pixDither` aplica dithering ordenado: compara cada pixel com uma matriz de Bayer (`matrixSize` 2, 4 ou 8) antes de reduzir as cores (`levels` por canal).
- `scale` é o tamanho, em pixels da composição, de cada pixel do dither. No degradê as três cores por canal ganham a textura da matriz.
- Com `monochrome` ligado, a luminância vira só `colorA` (escuros) e `colorB` (claros), como no título.

## Quando usar
- Aparência de jogo ou impressora antigos, imagens em duas cores, transições que "desfazem" a imagem em pontos.

## Armadilhas
- `levels` 2 com `monochrome` desligado deixa só 8 cores; para o efeito clássico de dois tons, ligue `monochrome`.
- `colorA` e `colorB` só valem no modo `monochrome`; matrizes maiores dão mais tons intermediários, com padrão mais visível.
- O alfa da camada é preservado (e multiplicado pelo alfa de `colorA`/`colorB` no modo `monochrome`).
