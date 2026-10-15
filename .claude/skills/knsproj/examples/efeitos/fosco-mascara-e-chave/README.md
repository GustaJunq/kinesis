# Fosco, máscara e chave: Luma Key e Matte Choker

## O que demonstra
- `lumaKey` remove as partes escuras do fundo radial (fica um disco claro com borda suave).
- `matteChoker` contrai o alfa do título e solta com o tempo (`choke` de 8 a 0).

## Quando usar
- Recortar fundos claros/escuros, ajustar bordas de recortes; para tela verde use `chromaKey` + `spillSuppressor`.

## Armadilhas
- Máscaras e track mattes não são efeitos: no app, os atalhos desta categoria abrem os editores de máscara e de matte.
- `chromaKey` mede a distância de **cor** (crominância), não de brilho: sombras no verde continuam sendo removidas.
- Em `lumaKey`, `invert` remove as áreas claras em vez das escuras.
