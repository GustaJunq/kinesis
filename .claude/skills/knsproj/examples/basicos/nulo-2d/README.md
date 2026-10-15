# Nulo 2D controlando duas formas

## O que demonstra
- Uma camada `null` (invisível) com `position` e `rotation` animadas; `disco` e `quadrado` são **filhos** dela e herdam o movimento.
- As posições dos filhos (`[0, -220]` e `[0, 220]`) são medidas **a partir do nulo**: os dois giram juntos em torno dele.

## Quando usar
- Mover, girar ou escalar várias camadas 2D com um único controle sem criar um grupo.
- Prender uma camada a um ponto que não tem conteúdo (um pivô sob medida).

## Armadilhas
- O nulo não desenha nada nem entra no vídeo exportado; no app ele aparece como um quadrado de contorno.
- Um nulo 2D só é pai de camadas 2D; para 3D use `"null": {"threeD": true}` (veja `nulo-3d-rig`).
- `opacity` do nulo não tem efeito e a opacidade não é herdada pelos filhos.
