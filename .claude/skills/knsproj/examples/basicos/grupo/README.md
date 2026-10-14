# Grupo: tempo relativo e efeito no conjunto

## O que demonstra
- `badge` começa em 1 s; seus filhos têm tempo **relativo** ao grupo (`badge-title` entra 0.3 s depois do início do grupo).
- O `glow` e a rotação do grupo valem para a imagem combinada dos filhos.

## Quando usar
- Agrupar um selo, um card ou uma legenda para animar, desfocar ou brilhar tudo de uma vez.

## Armadilhas
- O grupo recorta os filhos no próprio intervalo: um filho com `start + duration` maior que o do grupo é cortado.
- Tempos de keyframe dos filhos são relativos ao **filho**, o `start` do filho é relativo ao grupo.
- `parent` e `matte` só funcionam entre irmãos do mesmo grupo.
