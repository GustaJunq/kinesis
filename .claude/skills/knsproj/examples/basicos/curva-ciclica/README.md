# Curva cíclica (onda entre dois keyframes)

## O que demonstra
- `"easing": {"cyclic": {"cycles": 3.5}}` num keyframe: o valor **oscila** entre o valor desse keyframe e o do próximo, em vez de ir direto de um ao outro.
- `pendulo` vai e volta na horizontal (3,5 ciclos), `pulso` pulsa de 60% a 140% (5,5 ciclos) e `giro` balança entre -25° e 25° (2,5 ciclos).
- O último ciclo termina no valor do keyframe seguinte, então a animação chega ao valor final sem salto.

## Quando usar
- Pêndulos, pulsações, balanços e tremidos regulares com **dois** keyframes (em vez de dezenas de keyframes alternados ou `wiggle`).
- `cycles` = número de ondas completas no trecho; `offset` (-1..1) desloca a onda para a direita (fração do trecho).

## Armadilhas
- Para a curva sair de 0 e chegar em 1 **sem salto**, use `offset: 0` e `cycles` com meio (1,5; 2,5; 3,5...). Com outros valores a onda começa ou termina no meio de um balanço e o valor salta no começo ou no fim do trecho.
- O easing vale para o trecho que **começa** no keyframe onde está escrito.
- `cycles` aceita de 0,5 a 64 e `offset` de -1 a 1.
