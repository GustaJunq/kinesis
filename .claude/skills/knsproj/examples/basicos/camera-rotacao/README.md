# Câmera: posição X/Y/Z independente da rotação X/Y/Z

## O que demonstra
- `"aim": "rotation"`: a câmera tem `position` e `rotation` próprias, animadas separadamente.
- A `position` anima o *dolly* (`z` de -3200 a -1800) e a `rotation` anima um *pan* (`y` de -14° a 14° e de volta a 0°). Mover a câmera não a gira e girá-la não a move.

## Quando usar
- Pans, tilts e rolls (`rotation`), travellings e dollies (`position`) sem que um mexa no outro.
- Use o padrão `"aim": "target"` quando a câmera deve **sempre olhar para um ponto** (órbitas em torno de um objeto).

## Armadilhas
- Rotação zero olha para `+z` (`+x` à direita, `+y` para baixo); a ordem dos eixos é X, depois Y, depois Z.
- Com `aim: "rotation"` o `target` é ignorado.
- Se `position` for omitida, a câmera fica em `[0, 0, -d]` com `d = (altura/2) / tan(fov/2)`.
