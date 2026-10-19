# Kernel (`kernel`)

## O que demonstra
- `kernel` aplica uma convolução 3×3. Os predefinidos são `sharpen`, `emboss`, `edges` (detectar bordas) e `outline` (linhas escuras sobre fundo claro).
- Com `preset: "custom"` valem os 9 pesos `weight0` a `weight8` (linha de cima para baixo, da esquerda para a direita). O degradê usa um laplaciano de 4 vizinhos que realça só as bordas.
- `strength` mistura o resultado com a imagem original (0 = original); no título ele sobe de 0 a 1 em 2 s.

## Quando usar
- Nitidez extra, relevo, desenho de contornos e filtros personalizados (desfoque: todos os pesos 1/9, por exemplo).

## Armadilhas
- Pesos cuja soma não é 1 mudam o brilho (soma > 1 clareia, soma < 1 escurece); somas 0 realçam só as bordas.
- Os pesos só aparecem na interface com o predefinido `custom`, mas são sempre lidos do arquivo quando `preset` é `custom`.
- O espaço entre amostras é 1 pixel da composição, então o resultado é parecido na prévia e na exportação; o alfa da camada é preservado.
