# Tempo e transição: Echo, Posterize Time e Strobe

## O que demonstra
- A bola deixa um rastro de 6 cópias (`echo`).
- O título gira em 6 quadros por segundo (`posterizeTime`), num visual de stop-motion.
- Um retângulo transparente pisca 2 vezes por segundo (`strobe` em modo flash).

## Quando usar
- Rastros, ritmo de stop-motion, flashes de batida.

## Armadilhas
- Transições de entrada/saída não ficam em `effects`: use `transitionIn`/`transitionOut` (o atalho do app abre esse editor).
- O `echo` copia só os efeitos que vêm antes dele na lista.
- `posterizeTime` congela a camada inteira (inclusive outros efeitos) entre quadros.
