# Cubo metálico e câmera em órbita

## O que demonstra
- Cubo metálico (`metallic: 1`, `roughness: 0.22`) iluminado por 3 luzes: direcional, pontual azul (contraluz) e ambiente.
- A `camera` é **filha** do `rig` (um `empty`) que gira 360° em `loop: cycle`: o `rig` gira e a câmera orbita o cubo.

## Quando usar
- Qualquer cena 3D em que a câmera deve circular, subir ou seguir um alvo.
- Use `empty` como pivô para órbitas de objetos e de câmera.

## Armadilhas
- Câmera e luzes **não podem ser pais**; só o `rig` (objeto 3D) pode.
- Metal precisa de luz: sem luzes fortes ou ambiente o cubo fica escuro (o ambiente procedural só reflete o que existe).
- Camadas 3D consecutivas na pilha compartilham profundidade; uma camada 2D entre elas quebra o grupo.
