# Intro de logo com glow

## O que demonstra
- Halo desfocado que respira (`blur` + `scale` em `pingpong`), anel que se desenha (`trim`), marca (estrela) com `easeOutBack` e `glow`, wordmark com `letterSpacing` animado.

## Quando usar
- Abertura de vídeos e vinhetas. Troque `mark` por uma imagem do logo (`add-asset`) e o texto do `wordmark`.

## Armadilhas
- `letterSpacing` é em *em*: valores acima de ~0.5 deixam o texto largo demais para a tela.
- Um `blur` grande no halo aumenta o custo de render; mantenha o raio abaixo de ~200 px.
- O anel usa `rotation: -90` para o desenho começar no topo.
