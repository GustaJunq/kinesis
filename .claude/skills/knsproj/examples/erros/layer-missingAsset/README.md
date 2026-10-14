# Asset inexistente

Projeto **inválido de propósito**: `python3 tools/knsproj validate` deve falhar com um único erro.

**Código esperado:** `layer.missingAsset`

## O que está errado
A camada `logo` referencia o asset `logo`, mas `assets[]` está vazio.

## Mensagem da CLI
```
error [layer.missingAsset] layers[0].image.asset: asset 'logo' does not exist
```

## Conserto
Copie o arquivo para o projeto com `python3 tools/knsproj add-asset <pasta> caminho/logo.png` (ele cria `assets/logo.png` e registra o asset) ou remova a camada.

```json
"assets": [
  { "id": "logo", "type": "image", "path": "assets/logo.png" }
]
```
