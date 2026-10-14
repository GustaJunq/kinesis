# Exemplos da skill /knsproj

| Pasta | Conteúdo | Regra |
|---|---|---|
| `basicos/` | um recurso por pasta, 1 a 3 camadas | `validate` com 0 erros e 0 avisos |
| `receitas/` | composições completas (lower third, intro, título cinético, slideshow, vídeo vertical) | `validate` com 0 erros e 0 avisos |
| `erros/` | um projeto **inválido** por pasta, com o código de erro esperado | `validate` precisa falhar com o código documentado |

Cada pasta tem `project.json` e `README.md` (o que demonstra, quando usar, armadilhas). Valide qualquer uma com
`python3 tools/knsproj validate <pasta>`.
