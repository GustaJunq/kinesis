# Camada 2D com pai 3D

Projeto **inválido de propósito**: `python3 tools/knsproj validate` deve falhar com um único erro.

**Código esperado:** `layer.parentKind`

## O que está errado
`badge` é uma forma 2D e declara `parent: "cube"`, que é um objeto 3D. Camadas 2D só podem ter pai 2D e camadas 3D só pai 3D (câmeras, luzes e áudio nunca são pais).

## Mensagem da CLI
```
error [layer.parentKind] layers[1].parent: 2D layers can only be parented to 2D layers and 3D layers to 3D layers
```

## Conserto
Remova o `parent` ou aponte para uma camada 2D (por exemplo um grupo ou outra forma). Para seguir o cubo, anime a posição da forma com os mesmos keyframes.

```json
{ "id": "badge", "type": "shape", "shape": { "kind": "rectangle", "size": [240, 240] } }
```
