# Movimentar e transformar: Spin, Pulse, Auto-Shake e Float

## O que demonstra
- Estrela que gira (`spin`, graus por segundo) e pulsa (`pulse`).
- Título com tremor pseudoaleatório (`autoShake`, `seed: 7`) e subtítulo que flutua (`float`).

## Quando usar
- Movimento contínuo sem keyframes: selos, ícones, títulos com energia.

## Armadilhas
- Esses efeitos movem a imagem já renderizada; `transform` não muda (os gizmos do app continuam no lugar original).
- O tremor é o mesmo para o mesmo `seed`: troque o seed para variar entre camadas.
- O conteúdo que sair do quadro é cortado.
