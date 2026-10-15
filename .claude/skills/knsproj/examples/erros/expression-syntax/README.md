# Expressão com erro de sintaxe

Projeto **inválido de propósito**: `python3 tools/knsproj validate` deve falhar com um único erro.

**Código esperado:** `expression.syntax`

## O que está errado
A expressão de `position` é `x = wiggle(2, 30;` seguida de `x`: o parêntese de `wiggle(` nunca é fechado.

## Mensagem da CLI
```
error [expression.syntax] layers[0].transform.position.expression: line 1: '(' is never closed
```
O app e o core Kotlin interpretam o JavaScript de verdade e informam a linha exata e a causa; a CLI Python só confere parênteses, colchetes, chaves, aspas e comentários.

## Conserto
Feche o parêntese:

```json
"position": {"value": [0, 0], "expression": "wiggle(2, 30)"}
```
