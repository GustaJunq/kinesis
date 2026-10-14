# Utilitário: Depth Map e Channel Shuffle

## O que demonstra
- Cubo e esfera 3D renderizados como mapa de profundidade (branco perto, preto longe).
- Legenda com `channelShuffle` trocando os canais vermelho e azul.

## Quando usar
- Gerar passes de profundidade para composição, conferir distâncias na cena, truques de cor.

## Armadilhas
- `depthMap` só funciona em `object3d`/`model3d`; em camadas 2D é ignorado e o validador avisa `effect.layerKind`.
- `near`/`far` são distâncias à câmera em pixels: com a câmera padrão em 1080 px de altura, o plano z = 0 fica a ~1484 px.
- Sem estimativa por IA: só o z-buffer real das camadas 3D.
