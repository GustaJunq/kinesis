# Ambiente HDRI e modos de sombreamento

## O que demonstra
- `composition.environment`: um panorama 360° equirretangular (`assets/ceu.jpg`, 2:1) que os objetos 3D **refletem** e que os ilumina de leve. `intensity` regula a força; `rotation` gira o panorama.
- Os quatro `material.shading`: `pbr` (padrão, a esfera metálica espelha o céu), `flat` (facetado, low-poly), `toon` (faixas de luz duras e contorno escuro) e `wireframe` (só as arestas).

## Quando usar
- Produtos e objetos metálicos/brilhantes: o reflexo de um panorama dá muito mais realismo que o estúdio padrão.
- Estilos gráficos: `toon` para motion cartunesco, `flat` para low-poly, `wireframe` para visuais técnicos.

## Armadilhas
- O panorama precisa ser 2:1 (equirretangular); outras proporções ficam distorcidas.
- Imagens comuns (JPG/PNG) funcionam; o brilho máximo é o da imagem (não há HDR de 32 bits).
- `wireframe` não projeta sombra.
