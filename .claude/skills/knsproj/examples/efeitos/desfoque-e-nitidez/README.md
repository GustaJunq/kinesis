# Desfoque e nitidez: Lens Blur, Radial Blur e Sharpen

## O que demonstra
- Fundo com `lensBlur` (bokeh hexagonal, `blades: 6`) desfocando com o tempo.
- Título entra com `radialBlur` em modo `zoom` que zera em 1 s, e `sharpen` depois.

## Quando usar
- Mudar o foco entre fundo e título; entradas com impacto.

## Armadilhas
- Os desfoques usam menos amostras no preview durante a reprodução; a exportação usa a qualidade total.
- Em `radialBlur`, `amount` é graus no modo `spin` e % da distância ao centro no modo `zoom`.
- A ordem importa: `sharpen` antes do desfoque não tem efeito visível.
