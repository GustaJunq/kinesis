# Matte que não é irmão

Projeto **inválido de propósito**: `python3 tools/knsproj validate` deve falhar com um único erro.

**Código esperado:** `layer.matteMissing`

## O que está errado
`title` usa `wipe-box` como matte, mas `wipe-box` está **dentro do grupo** `shapes`: o matte precisa ser uma camada irmã (mesmo contêiner).

## Mensagem da CLI
```
error [layer.matteMissing] layers[1].matte.layer: matte layer 'wipe-box' must be in the same group
```

## Conserto
Coloque a fonte do matte no mesmo nível de `title` (tire-a do grupo) ou mova `title` para dentro do grupo.

```json
"layers": [
  { "id": "wipe-box", "type": "shape", "shape": { "kind": "rectangle", "size": [900, 240] } },
  { "id": "title", "type": "text", "matte": { "layer": "wipe-box", "mode": "alpha" }, "text": { "content": "REVELADO" } }
]
```
