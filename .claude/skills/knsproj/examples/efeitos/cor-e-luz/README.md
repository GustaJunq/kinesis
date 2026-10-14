# Cor e luz: Gradient Map, Deep Glow e Lens Flare

## O que demonstra
- `gradientMap` recolore o fundo pela luminância com três stops.
- `deepGlow` (bloom em várias passadas) cresce com keyframes em `intensity`.
- `lensFlare` com `position` animada atravessando o quadro.

## Quando usar
- Títulos luminosos, grading rápido, brilhos de abertura.

## Armadilhas
- `stops` é estático (2 a 8 cores); anime `amount` para entrar/sair do mapa.
- `deepGlow` só pega o que passa do `threshold`: em texto escuro abaixe o limiar.
- `position` do flare é relativo à origem da camada (aqui, o centro do título).
