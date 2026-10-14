# Track matte (alpha e luma)

## O que demonstra
- Alpha: o texto `title` só aparece onde o retângulo `wipe` (irmão) é opaco; o retângulo desliza e revela o texto.
- Luma: `panel` fica visível onde `ramp` é claro, criando um degradê de transparência da esquerda para a direita.

## Quando usar
- Revelar texto com formas, recortar vídeo em formatos, degradês de opacidade complexos.

## Armadilhas
- A camada-fonte (`wipe`, `ramp`) é **ocultada automaticamente**; não adianta mexer na opacidade dela.
- O matte precisa ser irmão (mesmo grupo): `layer.matteMissing` se estiver em outro grupo.
- Este exemplo tem 4 camadas porque cada matte precisa de uma fonte; separe em dois projetos se quiser só um tipo.
