# Animadores de texto

## O que demonstra
- `typewriter`: cada caractere aparece de uma vez, um depois do outro.
- `slideUp` por **palavra** (`unit: word`) com `easeOutCubic`.
- `wave`: o texto ondula continuamente (ignora `mode`).

## Quando usar
- Títulos e legendas que entram aos poucos sem criar uma camada por letra.
- Use `unit: word` para frases curtas e `character` para palavras de impacto.

## Armadilhas
- O tempo de cada unidade é `start + i * stagger`: texto longo com `stagger` alto só termina muito depois do fim da camada.
- `amount` muda de significado: pixels nos slides e no `wave`, graus no `rotate`, escala inicial em % no `scale`.
- `typewriter` não usa `unitDuration` de forma visível; deixe-o curto (0.05).
