# Título cinético

## O que demonstra
- Título em duas linhas: a primeira sobe por caractere, a segunda é revelada por um track matte que desliza; barra de destaque e ponto quicando.

## Quando usar
- Títulos de abertura e frases de impacto em 16:9. Troque os textos mantendo as larguras (a linha 1 tem ~1500 px com 190 px de fonte).

## Armadilhas
- `motionBlur` só aparece se `composition.motionBlur.enabled` estiver ligado **e** a camada tiver `"motionBlur": true`.
- A camada `reveal-box` é a fonte do matte e some sozinha: não a use como elemento visual.
- A saída (`transitionOut`) começa 0.4 s antes do fim da camada: não deixe a animação de entrada ocupar esse trecho.
