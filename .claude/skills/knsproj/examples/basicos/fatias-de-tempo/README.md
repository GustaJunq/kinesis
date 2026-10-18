# Fatias de tempo (`timeSlice`)

## O que demonstra
- O efeito `timeSlice` corta a imagem em `slices` faixas verticais (`angle` 0°) e cada faixa mostra a camada alguns quadros mais atrás.
- `order: "center"`: as faixas do meio mostram o presente e as das pontas, o passado (`offset` = 3 quadros a mais por faixa).
- `smooth` 0.35 mistura um pouco as faixas vizinhas (0 = degraus, 1 = mistura suave).

## Quando usar
- Em camadas de vídeo (ou pré-renderizadas) para o efeito de "deslocamento no tempo"; aqui uma forma girando mostra bem a diferença entre as faixas.

## Armadilhas
- Só os efeitos que vêm **antes** do `timeSlice` na lista entram nas imagens do passado.
- Faixas muito deslocadas pedem muitos quadros em memória; na prévia de aparelhos fracos o app usa menos faixas e guarda menos quadros, e a exportação é exata.
- Antes do início da camada não há imagem: se a camada começa depois do instante pedido, a faixa fica vazia até esse passado existir.
