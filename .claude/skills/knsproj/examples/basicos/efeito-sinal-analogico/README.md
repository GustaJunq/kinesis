# Sinal analógico (`signalAnalog`)

## O que demonstra
- `signalAnalog` desloca cada linha horizontalmente com ruído (`lineJitter`), faz a imagem derivar devagar, rasga faixas de vez em quando e separa os canais RGB (`rgbSplit`).
- `scanlineIntensity` e `scanlineDensity` desenham as linhas de varredura; `noise` soma um chuvisco; `amount` (aqui com keyframes) dá a força geral dos deslocamentos.
- O efeito está num grupo `tela`, então vale para o degradê e o título juntos.

## Quando usar
- Sinal de TV ruim, fita velha, câmeras de segurança e aberturas de "transmissão interrompida".

## Armadilhas
- `speed` multiplica o tempo da camada: com `0` o efeito congela num quadro estático; `seed` muda o sorteio, mas o mesmo `seed` e o mesmo tempo dão sempre o mesmo quadro.
- `rgbSplit` é em pixels da composição; valores altos deixam o texto ilegível.
- Os deslocamentos movem também o alfa da camada: as bordas de elementos transparentes tremem junto.
