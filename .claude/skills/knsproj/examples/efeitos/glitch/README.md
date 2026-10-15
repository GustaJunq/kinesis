# Glitch: Block Glitch, RGB Split e VHS

## O que demonstra
- Título com blocos deslocados que trocam `rate` vezes por segundo, separação RGB saltitante e textura de fita VHS.

## Quando usar
- Aberturas tech, transições bruscas, estética de erro.

## Armadilhas
- Todo sorteio vem de `seed` e do tempo local: o mesmo projeto renderiza igual sempre.
- `rate: 0` congela o padrão (útil para um quadro estático).
- Empilhar muitos glitches deixa o texto ilegível: mantenha `intensity` moderada e anime-a com keyframes.
