# Procedimental: Fractal Noise, Grid e Checkerboard

## O que demonstra
- Um retângulo de tela cheia vira ruído fractal animado (`evolution` em loop) com uma grade somada por cima (`mode: add`).
- O título recebe um xadrez multiplicado (`mode: multiply`) dentro das letras.

## Quando usar
- Fundos animados sem mídia, texturas, padrões.

## Armadilhas
- Geradores ignoram a cor da camada mas **mantêm o alfa**: aplique num retângulo para cobrir o quadro.
- `mix` controla a força; `mode` decide como o padrão combina com a camada.
- A cor da grade com alfa (`#FFFFFF33`) suaviza as linhas.
