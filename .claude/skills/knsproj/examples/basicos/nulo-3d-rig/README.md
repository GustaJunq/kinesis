# Rig de câmera em nulo 3D

## O que demonstra
- Um nulo 3D (`"null": {"threeD": true}`) gira 360° em `loop: cycle`; a `camera` é **filha** dele e orbita o cubo.
- A câmera usa `"aim": "rotation"`: `position` fica a `[0, -300, -2400]` e `rotation` `[7, 0, 0]` só inclina o olhar para o cubo, sem mexer na posição.

## Quando usar
- Órbitas, travellings e rigs: em vez de um `object3d` com `primitive: "empty"`, use um nulo 3D como pivô.
- Mover vários objetos 3D, luzes e câmeras com um único controle.

## Armadilhas
- Câmeras e luzes podem ser **filhas** de um nulo 3D, mas nunca pais.
- O nulo 3D não desenha nada nem entra no vídeo; no app ele aparece como um cubo de contorno.
- Com `aim: "rotation"` o `target` é ignorado; com `aim: "target"` (padrão) a `rotation` é ignorada.
