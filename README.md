# Kinesis Editor

**Editor de motion design gratuito e open-source para Android.** Texto animado, formas, vídeo, áudio, efeitos e
objetos 3D na mesma timeline, com keyframes e curvas. Sem marca-d'água, sem assinatura, sem conta, sem IA generativa e sem internet.
Basicamente offline-first.

## Baixar

1. Abra a página de **[Releases](https://github.com/GustaJunq/kinesis/releases/latest)** e baixe o arquivo
   `kinesis-X.Y.Z.apk` da versão mais recente.
2. No celular, abra o arquivo. Se o Android pedir, permita **"instalar apps desconhecidos"** para o navegador ou o
   gerenciador de arquivos.
3. Pronto. As próximas versões instalam por cima, sem perder os projetos.

**Requisitos:** Android 8.0 ou mais recente, com OpenGL ES 3.0 (praticamente todos os aparelhos desde 2015).

> O Kinesis está em **beta**: funciona, mas ainda pode ter falhas. Seus projetos são salvos automaticamente, com as
> últimas 10 versões de cada um, e podem ter uma cópia em `Documentos/Kinesis` (Ajustes).

## O que dá para fazer

- **Animar qualquer coisa** com keyframes, curvas de velocidade (editor de curvas e editor de gráfico), presets de
  easing que você salva e reaproveita, e copiar/colar keyframes entre camadas.
- **Texto:** fontes do sistema ou suas (TTF/OTF), animação por letra, palavra ou linha, e expressões no estilo do
  After Effects (`wiggle`, `loopOut`…).
- **Formas, imagens, vídeo e áudio:** cortar, dividir, velocidade, volume com fades, máscaras e track mattes.
- **68 efeitos** (brilho, desfoque, glitch, vidro, chroma key…), transições e motion blur.
- **3D:** cubos, esferas, modelos GLB, **texto 3D** com profundidade e chanfro, luzes com **sombras**, iluminação por
  **panorama (HDRI)**, câmera com **profundidade de campo**, nulos para montar rigs e estilos flat, toon e wireframe.
- **Legendas automáticas** geradas **no aparelho** pelo Whisper (sem internet depois de baixar o modelo uma vez):
  edite texto, tempos e aparência; importe e compartilhe `.srt`.
- **Exportar MP4** até 4K, direto para a galeria. A exportação mostra o tempo restante e, se falhar, continua de onde
  parou e indica a camada pesada — que pode ser **pré-renderizada** com um toque.
- Prévia com resolução ajustável (cheia, ½, ¼) para aparelhos mais simples.
- Português e inglês, tema claro e escuro.

## Projetos `.knsproj`

Os projetos podem ser exportados e importados como arquivos **`.knsproj`** (com todas as mídias e fontes dentro),
para fazer backup ou passar para outro aparelho. O formato é aberto e documentado em
[docs/knsproj.md](docs/knsproj.md), então outras ferramentas também podem criar projetos — por exemplo o
**Claude Code**, com a skill [`/knsproj`](.claude/skills/knsproj/SKILL.md) deste repositório:

```
/knsproj Crie uma animação vertical de 10 segundos com um cubo metálico girando e um título animado.
```

## Privacidade

O Kinesis funciona 100% offline: não há contas, anúncios nem telemetria. A **única** conexão de rede é o download opcional do
modelo de fala das legendas, que só acontece quando você toca em baixar (dá para importar o arquivo do modelo em vez disso).

## Para desenvolvedores

Compilação, testes, CLI e arquitetura: [docs/desenvolvimento.md](docs/desenvolvimento.md). Novidades de cada versão:
[CHANGELOG.md](CHANGELOG.md).

## Licença

[GPL v3.0](LICENSE).
