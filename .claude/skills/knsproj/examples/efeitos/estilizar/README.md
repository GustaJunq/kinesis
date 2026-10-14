# Estilizar: Halftone, Posterize, Scanlines/CRT e Film Grain

## O que demonstra
- Fundo em retícula (`halftone`) e título posterizado com sombra dura, dentro de um grupo `screen` que recebe scanlines com curvatura de CRT e granulação.

## Quando usar
- Visual de impresso, de TV antiga ou de filme.

## Armadilhas
- Para afetar a tela toda, aplique o efeito num grupo com tudo dentro (ou num retângulo do tamanho da composição).
- `curvature` > 0 deixa os cantos transparentes, como num tubo de TV.
- `filmGrain` muda a cada quadro de forma determinística (mesmo seed, mesmo grão).
