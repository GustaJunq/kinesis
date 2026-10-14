# Slideshow com transições e música

## O que demonstra
- Quatro slides (cada um um `group`) de 3.6 s que se sobrepõem em 0.8 s; a transição de entrada do slide seguinte cobre o anterior.
- Zoom lento (Ken Burns) no fundo, textos escalonados, barra de progresso e trilha em `fadeIn`/`fadeOut`. A música é um acorde sintético gerado com ffmpeg.

## Quando usar
- Apresentações de fotos: troque cada `*-bg` por uma imagem (`add-asset` + `image`) e mantenha a estrutura dos grupos.

## Armadilhas
- Os grupos mais acima na lista cobrem os de baixo: o slide seguinte precisa vir **depois** na lista.
- O `start` de cada slide = início do anterior + duração - sobreposição; some errado e há um buraco preto entre eles.
- Ids são globais: os filhos têm prefixo do slide (`praia-title`) para não duplicar.
