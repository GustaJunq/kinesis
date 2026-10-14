# Texto: animadores + efeitos

## O que demonstra
- Animador `slideUp` por caractere (em `text.animators`) combinado com efeitos comuns (`fill` e `glow`).

## Quando usar
- Qualquer título: anime as letras com animadores e dê acabamento com efeitos.

## Armadilhas
- A categoria "Texto" do app é um atalho para os animadores de texto; eles não ficam em `effects`.
- Animadores só existem em camadas `text`.
- `fill` troca a cor preservando o alfa: útil para recolorir um texto sem mexer em `text.color`.
