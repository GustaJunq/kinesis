# Efeitos: glow, dropShadow e chromaticAberration

## O que demonstra
- `glow` com intensidade pulsando (`pingpong`).
- `dropShadow` suave em um cartão claro.
- `chromaticAberration` com picos curtos: efeito de glitch.

## Quando usar
- Dar profundidade e brilho a textos e formas; glitch em cortes e títulos.

## Armadilhas
- Os efeitos são aplicados **em ordem** sobre a camada já transformada; o `glow` pode ser cortado pelos limites da camada se o raio for enorme.
- No glitch use `hold` nos keyframes de repouso para o efeito não ficar "deslizando" entre os picos.
- Efeitos em camadas `audio`, `camera` ou `light` geram o aviso `layer.effects`.
