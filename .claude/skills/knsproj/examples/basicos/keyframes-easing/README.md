# Keyframes e easing

## O que demonstra
- `overshoot`: `cubicBezier [0.34, 1.56, 0.64, 1]` passa do destino e volta (y > 1).
- `bounce`: preset `easeOutBounce` na queda.
- `pulse`: dois keyframes com `loop: pingpong` para um pulso infinito.

## Quando usar
- Entradas com personalidade; pulsos e respirações que duram a camada toda.

## Armadilhas
- O `easing` pertence ao segmento que **começa** no keyframe; o do último keyframe não tem efeito.
- Na Bézier, `x1` e `x2` ficam entre 0 e 1 (só `y` pode extrapolar), senão o schema rejeita.
- Os tempos são relativos ao `start` da camada e devem crescer estritamente (`keyframe.order`).
