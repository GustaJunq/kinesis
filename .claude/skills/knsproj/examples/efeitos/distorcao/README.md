# Distorção: Glass, Warp e Pinch/Bulge

## O que demonstra
- Um círculo com `glass` desliza sobre o fundo e refrata o que está atrás, com dispersão em 7 bandas.
- O fundo ondula com `warp` fractal; `evolution` de 0 a 360° fecha o ciclo sem salto.
- O título alterna entre pinch e bulge (`amount` de -0.6 a 0.6).

## Quando usar
- Lentes, gotas, letreiros de vidro; fundos vivos.

## Armadilhas
- O `glass` refrata só as camadas **abaixo dele no mesmo contêiner** (dentro de um grupo, só o que está no grupo).
- A forma do vidro é o alfa da camada: a cor do círculo não aparece.
- Os parâmetros de `warp` dependem de `mode` (`amplitude`… só no `wave`; `scale`… só no `fractal`).
