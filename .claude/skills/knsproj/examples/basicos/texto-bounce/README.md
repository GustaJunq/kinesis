# Texto com quique (Bounce Text)

## O que demonstra
- Um **animador de texto** (`text.propertyAnimators`) no estilo do After Effects: a propriedade `scale` do animador vale `[0, 0]` (%) e o **seletor** (`amount`, em %) decide, por caractere, o quanto dessa escala é aplicada.
- A expressão do tutorial "Bounce Text" **sem nenhuma alteração**, no `amount`. Ela roda uma vez por caractere, com `textIndex` (1, 2, 3...) e `textTotal`.
- Antes da vez do caractere a expressão devolve `value` (100%): escala 0, o caractere está invisível. Depois, `[s, s]` oscila com amortecimento e termina em 0%, escala normal.

## Quando usar
- Títulos que "quicam" letra por letra. Troque `delay` (0.030 s por caractere), `freq`, `amplitude` e `decay` para mudar o ritmo.
- Para caracteres que entram por fade: use `opacity: 0` no animador e uma expressão como `linear(time - inPoint - 0.05 * textIndex, 0, 0.4, 100, 0)`.

## Armadilhas
- `textIndex` conta **caracteres** (a partir de 1, espaços incluídos), não camadas. O índice da camada é `index`.
- No animador, `scale` está em **%** (100 = sem mudança) e o resultado do seletor também é em %: a escala final do caractere é `1 + (scale/100 − 1) · amount/100`.
- `time` é o tempo da composição (segundos) e `inPoint` o início da camada: `time - inPoint` é o tempo local.
- A escala por caractere é uniforme: com um array `[sx, sy]` o app usa a média dos dois.
