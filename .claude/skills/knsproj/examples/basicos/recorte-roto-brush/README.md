# Recorte com Roto Brush (`video.roto`)

## O que demonstra
- Um vídeo com **transparência empacotada** (`alphaPacked`) feito pelo Roto Brush: `recorte-do-objeto` é o resultado e `origem-oculta` é a camada original, oculta.
- `video.roto` (formato v9) guarda a receita para editar de novo: o vídeo de origem (`source`), o trecho (`sourceStart`, `duration`) e as pinceladas por quadro (`frames`). Pontos em coordenadas normalizadas (0 a 1) do quadro em pé; `size` é o diâmetro do pincel como fração do menor lado.
- `mode`: `add` marca parte do objeto, `remove` marca o que não é.

## Quando usar
- Para recortar qualquer objeto (não só pessoas) de um vídeo. O app gera o vídeo com alfa; o arquivo `.knsproj` guarda as pinceladas para reabrir o Roto Brush.

## Armadilhas
- `source` precisa ser um asset de vídeo do projeto; `time` de cada quadro fica entre 0 e `duration`.
- No máximo 240 quadros pintados, 64 pinceladas por quadro e 2000 pontos por pincelada.
- `roto` só descreve camadas de vídeo com `alphaPacked`; o próprio arquivo do recorte é gerado pelo app (aqui é só uma cópia de exemplo).
