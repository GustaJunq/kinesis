# Modelo GLB com câmera girando

## O que demonstra
- Um `.glb` mínimo (pirâmide de cobre, 1,3 KB, gerado por script) importado como asset `model` e usado em `model3d`.
- A câmera gira em torno do modelo como filha do `rig`. Sem camadas `light`, vale a iluminação de estúdio padrão.

## Quando usar
- Mostrar um modelo exportado do Blender ou de bibliotecas de assets 3D.

## Armadilhas
- O modelo é centrado e escalado para que a **maior dimensão** tenha `size` pixels.
- Só GLB (ou glTF com buffers `data:`), sem Draco/meshopt, skinning ou animações internas.
- O arquivo precisa estar em `assets/` e declarado em `assets[]` com `type: "model"`.
