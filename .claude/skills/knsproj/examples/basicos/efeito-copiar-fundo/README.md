# Copiar fundo (`copyBackground`)

## O que demonstra
- `copyBackground` copia o que está embaixo da camada (as camadas de baixo) para dentro dela: a camada vira uma camada de ajuste. Os efeitos que vêm **depois** dele atuam nos pixels de fundo cobertos pelas partes opacas da camada.
- Aqui a elipse `lente` percorre o quadro com `copyBackground` + `hueShift` (`hue` de 0° a 180° com keyframes): só o que está dentro dela muda de cor, o resto da imagem fica como está.
- `amount` mistura a camada com o fundo copiado (1 = só o fundo, 0 = a camada como ela é).

## Quando usar
- Camada de ajuste: uma forma que cobre o quadro inteiro com `copyBackground` e os efeitos de cor, desfoque ou estilo que devem valer para tudo o que está embaixo.
- Lentes, lupas e janelas que mudam só uma região (anime `position`, `size` ou a máscara da camada).
- A região também pode ser recortada com as máscaras da camada, `feather` e `opacity`.

## Armadilhas
- Ponha `copyBackground` **antes** dos outros efeitos da camada: o que vem antes dele é descartado, só o alfa da camada é mantido.
- O efeito só enxerga o que está **abaixo** na lista de camadas; camadas acima não entram na cópia.
- O desenho da própria camada (cor, imagem) some enquanto `amount` é 1: o que decide a região é só o alfa dela.
- Um efeito que mexe no alfa depois dele (desfoque, por exemplo) também suaviza a borda da região.
