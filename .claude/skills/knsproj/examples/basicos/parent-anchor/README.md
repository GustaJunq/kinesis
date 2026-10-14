# Pivô fora do centro e camada filha

## O que demonstra
- `anchor: [-200, 0]` põe o pivô na ponta esquerda da barra (o conteúdo é centrado na origem local).
- `tip` é filha de `arm` (`parent`) e herda a rotação; sua `position` está no espaço do pai.

## Quando usar
- Braços, ponteiros de relógio, portas e qualquer coisa que gira em torno de uma ponta.
- Hierarquias: mova o pai e os filhos acompanham.

## Armadilhas
- `anchor` e `position` são coisas diferentes: `anchor` é o pivô **no conteúdo**, `position` é onde o pivô fica **no espaço do pai**.
- O `parent` herda posição, rotação e escala, mas **não** a opacidade.
- Pai e filho precisam ser irmãos e do mesmo tipo de espaço (2D com 2D, 3D com 3D).
