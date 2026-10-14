# Transições de entrada e saída

## O que demonstra
- `transitionIn` (`slideUp`) e `transitionOut` (`fade`) em uma camada simples.
- `transitionIn` (`zoomIn`) e `transitionOut` (`blur`) em um **grupo**: os filhos entram juntos.

## Quando usar
- Entrada e saída limpas sem keyframes manuais; trocar cenas em apresentações e slideshows.

## Armadilhas
- `transitionIn` ocupa os primeiros `duration` segundos da camada e `transitionOut` os últimos: camada curta demais deixa as duas se sobrepor.
- `wipeLeft`/`wipeRight` viram `fade` em grupos e camadas 3D.
- A direção descreve o movimento na tela: `slideLeft` entra vindo da direita.
