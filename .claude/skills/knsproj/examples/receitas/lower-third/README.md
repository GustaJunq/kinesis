# Lower third

## O que demonstra
- Placa com `slideRight`/`slideLeft` (entrada e saída), barra de destaque que cresce em `scale.y` e texto em duas linhas escalonadas.

## Quando usar
- Identificar pessoas e lugares em vídeos 16:9. Mude os textos, as cores e a posição; mantenha a placa dentro da área segura (±880 px em x, ±480 px em y).

## Armadilhas
- O texto é centrado na própria posição: para alinhar à esquerda da placa, ajuste a `position` manualmente.
- Os textos têm `start` depois da placa e terminam antes dela, para nunca aparecerem sem fundo.
- A cor `#101820D9` tem alfa; para sobrepor num vídeo real, troque o fundo da composição pelo vídeo.
