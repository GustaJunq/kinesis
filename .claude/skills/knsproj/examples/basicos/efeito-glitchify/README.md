# Glitchify (`glitchify`)

## O que demonstra
- `glitchify` desloca blocos aleatórios (`blockSize`, `blockDensity`), separa os canais RGB em cada bloco (`rgbSplit`) e faz blocos inteiros ficarem transparentes (`dropoutChance`, aqui com keyframes de 0 a 25%).
- `intensity` controla o quanto os blocos se afastam; `speed` define quantas vezes por segundo o sorteio muda.
- O efeito está num grupo `tela`: os blocos transparentes deixam ver o fundo da composição.

## Quando usar
- Transições bruscas, falhas de sinal digital, aberturas tech e golpes de impacto (anime `intensity` ou `dropoutChance`).
- Quadro estático com glitch: ponha `speed` em `0`.

## Armadilhas
- Com `speed` `0` o glitch fica congelado num único sorteio (só `seed` o muda); com o mesmo `seed` e o mesmo tempo o resultado é sempre idêntico, na prévia e na exportação.
- `dropoutChance` apaga também o conteúdo opaco: use valores baixos, ou o elemento some por completo.
- Blocos de dois formatos (largos e altos) podem se sobrepor; `blockDensity` alto com `intensity` alta deixa a imagem irreconhecível.
