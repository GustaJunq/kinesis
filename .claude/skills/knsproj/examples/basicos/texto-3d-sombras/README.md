# Texto 3D com sombras e profundidade de campo

## O que demonstra
- `object3d` com `"primitive": "text"` e `object3d.text`: texto extrudado (`depth`) com chanfro arredondado (`bevel`), na fonte e peso escolhidos.
- Uma luz direcional com `castShadows`: o título e a esfera projetam sombra no chão (`shadowDarkness`, `shadowSoftness`).
- Câmera com `dof`: o título (a ~2200 px) fica nítido e a esfera ao fundo desfocada (`focusDistance`, `range`, `blur`).

## Quando usar
- Títulos e logotipos 3D, aberturas, lower thirds 3D.
- Sombras para "assentar" objetos num chão; profundidade de campo para destacar o assunto.

## Armadilhas
- O texto é medido em pixels (`fontSize`, `depth`, `bevel`); `object3d.size` não o escala (use `transform3d.scale`).
- `bevel` vai de 0 até metade de `depth`.
- Só a **primeira** luz com `castShadows` gera sombra em cada quadro; luzes pontuais funcionam melhor longe dos objetos.
- O desfoque age sobre camadas 3D; camadas 2D não passam pela câmera.
