# Dissolver tremendo (`dissolveShake`)

## O que demonstra
- `dissolveShake` divide a imagem numa grade de blocos (`blockSize`); conforme `progress` sobe de 0 a 1 (keyframes de 0,5 s a 3,5 s), cada bloco treme (`shakeAmount`) e some num momento sorteado.
- Como o efeito está num grupo, o degradê e o título se desfazem juntos.

## Quando usar
- Saídas de títulos e cenas, transições que desmancham a imagem em pedaços (use `progress` de 1 a 0 para a entrada).

## Armadilhas
- `progress` 0 mostra a imagem inteira e 1 mostra nada; fora desse intervalo o valor é limitado.
- A ordem em que os blocos somem vem de `seed`; o tremor depende só do tempo da camada, então prévia e exportação saem iguais.
- Blocos pequenos custam o mesmo (um passe só), mas deixam o tremor quase imperceptível.
