# Vista do editor (`editorView`)

## O que demonstra
- `editorView` na raiz (formato v9) guarda a **câmera livre da prévia**: `mode` (`camera`, `editor` ou `inside`), `target`, `yaw`, `pitch`, `distance` e `fov`.
- Aqui o projeto abre na vista do editor, de cima e de lado, com a câmera da cena (`cena-camera`) desenhada como pirâmide e o cubo girando no centro.

## Quando usar
- Para deixar o projeto aberto numa vista de trabalho específica. É estado do editor: **não é renderizado** e não entra no vídeo exportado.
- Omita `editorView` para o comportamento de sempre (prévia pela câmera ativa).

## Armadilhas
- `pitch` vai de −89 a 89 (positivo olha de cima) e `distance` de 50 a 200000 pixels do `target`.
- Mudar a vista no app não é um passo de desfazer; mudar a câmera da cena (modo `inside`) é.
- Só camadas 3D mudam com a vista; camadas 2D continuam desenhadas no quadro.
