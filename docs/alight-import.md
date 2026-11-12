# Importar do Alight Motion

O Kinesis converte o projeto que o Alight Motion exporta como XML (`Novo projeto.xml`, testado com o Alight 5.0.83) em um
projeto novo do Kinesis. O XML original nunca é alterado.

O XML **não traz as mídias**: só endereços `content://` que funcionam apenas no aparelho que criou o projeto. Por isso o
importador procura cada arquivo pelo **nome, tamanho em bytes e resolução**; o tamanho em bytes basta quando o arquivo foi
renomeado. O que não for achado vira um **retângulo cinza com o nome do arquivo** (mesma caixa, mesmo lugar), e dá para
trocar pelo arquivo real depois.

## No app

1. Início → importar → escolha o `.xml`.
2. O app pede acesso às suas mídias **só nesse momento** e procura os arquivos (primeiro pelo endereço original, depois no
   MediaStore por nome, tamanho e resolução).
3. A tela **Mídia não encontrada** lista o que faltou: escolha o arquivo de cada item ou siga com placeholders.
4. Aparece o **relatório da importação**: o que foi convertido, aproximado, não suportado e as mídias ausentes.

## Na linha de comando

```
tools/alight2kns scan    projeto.xml [--media DIR]...
tools/alight2kns convert projeto.xml -o saida.knsproj [--media DIR]... [--report relatorio.json] [--allow-name-match]
```

`scan` imprime (JSON) o título, tamanho, duração, número de camadas e, para cada mídia, `found` (tamanho + nome ou resolução),
`found-by-size`, `name-only` (mesmo nome, outro tamanho: nunca usado sem `--allow-name-match`) ou `missing`. `convert` grava o
`.knsproj` e imprime o relatório. Saída 0 = pronto (uma conversão parcial também é 0: leia o relatório), 1 = entrada inválida ou
falha ao gravar, 2 = uso errado. O jar sai de `./gradlew :core:alight2knsJar`.

## O que é convertido

| Alight | Kinesis |
| --- | --- |
| Imagens, vídeos, formas retangulares | camadas de imagem, vídeo e forma (a mídia cobre a caixa; o que sobra é cortado por máscara) |
| Ordem das camadas | a primeira do XML fica embaixo, a última em cima |
| Posição, escala, rotação, pivô, opacidade | transformação 2D (a posição é recalculada quando o pivô e a escala/rotação são animados) |
| Keyframes e easing | keyframes; `cubicBezier` exato; `elastic` ≈ easeOutElastic; `cyclic` ≈ curva cíclica (aproximado) |
| Pré-composições (`embedScene`) | grupos com máscara do tamanho da cena; `inTime`, `speed` e pais respeitados |
| Vídeo e áudio | ponto de entrada, velocidade e ganho (ganho 0 = mudo) |
| Sombra externa | efeito Sombra (`dropShadow`) |
| `gaussianblur`, `hueshift`, `invert`, `mosaic`, `solidcolor` | Desfoque, Matiz, Inverter, Pixelizar, Preencher |
| `bendwarp` | Dobrar (`bendWarp`): ângulo e limites; eixo vertical |
| `swing2`, `oscillate3`, `scaleassist`, `offset` | **assados** em keyframes de rotação, posição e escala |
| `motionblur4` | liga o desfoque de movimento da camada |

Não convertidos (aparecem no relatório): `colorize`, `threshold`, sombra interna, inversão de só alguns canais RGB.
A tabela de efeitos fica em `core/src/main/resources/alight/effects_map.json`.

## Suposições a conferir

O Alight não documenta alguns detalhes; foram deduzidos do arquivo de exemplo e marcados como aproximados no relatório:
unidade do desfoque, forma da senoide de `swing2`/`oscillate3`, eixo de `scaleassist`, cor das formas sólidas (o XML não traz;
usa-se branco) e a conversão de `elastic`/`cyclic`.
