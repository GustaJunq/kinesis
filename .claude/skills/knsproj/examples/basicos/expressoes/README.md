# Expressões: wiggle, loopOut e matemática

## O que demonstra
- `wiggle(3, 40)` na posição: tremor suave e **determinístico** (o mesmo quadro sempre dá o mesmo resultado, no preview e na exportação).
- `loopOut("pingpong")` depois de dois keyframes de rotação: continua indo e voltando para sempre.
- Uma expressão só com matemática (`linear` e `Math.sin`) na opacidade, em porcentagem (20 a 100).

## Quando usar
- Movimento contínuo sem keyframes infinitos: câmera na mão (`wiggle`), loops (`loopOut`/`loopIn`), pulsos (`Math.sin`).
- A forma de uma propriedade com expressão é `{"value": ..., "expression": "..."}`; com keyframes: `{"keyframes": [...], "expression": "..."}`.

## Armadilhas
- As expressões seguem as **unidades do After Effects**: escala e opacidade em %, posição medida do canto superior esquerdo da composição (`[w/2, h/2]` é o centro). O arquivo continua guardando os valores no formato do Kinesis.
- O resultado é a **última expressão avaliada**; não use `return` fora de função.
- `expressionEnabled: false` desliga a expressão sem apagá-la.
- Nem toda função do After Effects existe: veja a matriz em `docs/expressoes.md`.
