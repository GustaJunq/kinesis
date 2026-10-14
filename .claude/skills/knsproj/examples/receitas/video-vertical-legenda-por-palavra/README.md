# Vídeo vertical com legenda por palavra (1080x1920)

## O que demonstra
- Três legendas, cada uma com animador `scale` por **palavra** (`easeOutBack`, `amount: 0`): as palavras "estouram" uma após a outra.
- Barra de progresso (escala em x com pivô à esquerda) e tópico no topo, tudo dentro da área segura.

## Quando usar
- Shorts, Reels e TikTok. Para legendar um vídeo real, coloque a camada `video` entre `backdrop` e as legendas e sincronize `start` com a fala.

## Armadilhas
- Legendas ficam dentro de ±470 px em x e ±820 px em y; o app de destino cobre a parte de baixo com a interface, por isso a legenda está em y = 480.
- `maxWidth: 940` quebra o texto em linhas; sem ele frases longas passam das bordas.
- O traço (`stroke`) preto garante leitura sobre qualquer fundo; tire só se o fundo for sempre escuro.
