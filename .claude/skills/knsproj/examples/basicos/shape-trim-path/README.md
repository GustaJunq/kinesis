# Trim path

## O que demonstra
- Linha curva que se **desenha** animando `trim.end` de 0 a 1.
- Círculo de loading: `trim.end` em `pingpong` + `rotation` linear em `cycle`.

## Quando usar
- Assinaturas, sublinhados, gráficos de linha e indicadores de carregamento.

## Armadilhas
- `fill` precisa de `"enabled": false`, senão o caminho aberto é preenchido.
- Em `loop: cycle` o último keyframe repete o primeiro: use 0 → 360 em rotação para um giro sem salto.
- Caminhos exigem ao menos 2 pontos (`shape.path`); tangentes `in`/`out` são relativas ao ponto.
