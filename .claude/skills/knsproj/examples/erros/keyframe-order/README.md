# Keyframes fora de ordem

Projeto **inválido de propósito**: `python3 tools/knsproj validate` deve falhar com um único erro.

**Código esperado:** `keyframe.order`

## O que está errado
Os tempos dos keyframes de `position` são `0`, `1` e `1`: o segundo e o terceiro empatam, mas precisam ser **estritamente crescentes** (e relativos ao `start` da camada). Tempos em ordem decrescente também são rejeitados (o app recusa a importação com "keyframes must be sorted by time").

## Mensagem da CLI
```
error [keyframe.order] layers[0].transform.position.keyframes[2]: keyframe times must be strictly increasing
```

## Conserto
Ordene os keyframes por tempo e não repita o mesmo tempo (diferença mínima de 0.0001 s). Para um salto instantâneo use `easing: "hold"`.

```json
"position": {"keyframes": [
  {"time": 0, "value": [-300, 0]},
  {"time": 1, "value": [0, 0]},
  {"time": 1.5, "value": [300, 0]}
]}
```
