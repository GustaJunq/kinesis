# Máscara com feather

## O que demonstra
- Uma elipse (`masks`, modo `add`) cresce de 0 a 1300 px e revela o retângulo com borda suave (`feather: 80`).

## Quando usar
- Revelar imagens e formas em círculo, vinhetas, focos de luz e recortes com borda suave.

## Armadilhas
- A máscara fica no espaço **local** da camada e começa vazia: sem uma máscara `add` a camada some.
- `feather` suaviza para dentro e para fora; com a máscara muito pequena o resultado fica quase transparente.
- Máscaras só valem para camadas 2D (até 4 por camada).
