# Vídeo e áudio

## O que demonstra
- `sourceStart: 0.5`: o clipe começa a tocar no meio da mídia, não no início.
- `volume` animado (fade manual por keyframes) somado a `fadeIn`/`fadeOut` do próprio objeto.
- Trilha `audio` separada com volume crescente. Os arquivos em `assets/` foram gerados com ffmpeg (sinal de teste e acordes sintéticos).

## Quando usar
- Cortar um trecho de um vídeo, ajustar velocidade e fazer fade de som; música de fundo sob um vídeo.

## Armadilhas
- Vídeo e imagem aparecem no **tamanho nativo**: aqui o clipe de 480x270 usa `scale 3` para encher 1440x810.
- `speed` diferente de 1 também muda o tom do áudio; se mudar `speed`, ajuste `duration` da camada (mídia consumida = `duration * speed`).
- Todo asset precisa existir em `assets/` e estar listado em `assets[]` (`layer.missingAsset`, `asset.missingFile`); rode `knsproj pack` para preencher os metadados.
