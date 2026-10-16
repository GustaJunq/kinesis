# Changelog

Todas as versões do Kinesis Editor. As notas completas de cada release ficam em [docs/releases/](docs/releases).

## 0.1.3-hotfix — 2026-10-16

Sem mudança de formato (`.knsproj` v6).

### Corrigido
- **Cenas 3D com muitos objetos travavam.** O motion blur reavaliava a cena inteira para cada objeto e cada
  sub-quadro; agora cada instante é avaliado uma vez por quadro.

### Melhorado
- Objetos 3D com motion blur que ficam parados durante o obturador são desenhados sem o custo do desfoque.
- Objetos totalmente fora do enquadramento da câmera deixam de ser desenhados (câmera e profundidade de campo).
- Menos trabalho por objeto: malhas calculadas uma vez por quadro, material e sombreamento só reenviados à GPU
  quando mudam, ordenação de objetos translúcidos e cálculo do shadow map sem alocações.

## 0.1.3 — 2026-10-16

Formato `.knsproj` **v6** (abre v1 a v5).

### Adicionado
- Texto 3D extrudado (profundidade, bevel); sombras de luzes direcionais/pontuais; iluminação por panorama (HDRI);
  profundidade de campo na câmera; sombreamento flat, toon e wireframe; qualidade 3D do preview separada da exportação.
- Editor de gráfico com alças Bézier; presets de easing salvos, copiar/colar curva, aplicar a todos os trechos;
  copiar/colar keyframes entre camadas.
- Últimas 10 versões de cada projeto, restauráveis; oferta de reabrir após fechamento inesperado; salvamento ao sair
  do app; cópia opcional em `Documentos/Kinesis`.
- Exportação em blocos retomáveis com tempo estimado; relatório de falha com instante e camada; pré-renderizar
  camada (vídeo com alfa empacotado, `video.alphaPacked`).
- Preview com resolução ajustável, cache de quadros, decodificador compartilhado por arquivo, liberação de recursos
  fora de uso e proxies opcionais para vídeos pesados.
- Ajustes: prévia, qualidade 3D, proxies e cópia em Documentos.

### Corrigido
- Contornos sobrepostos de fontes variáveis viravam buracos no texto 3D.

### Documentação
- README simplificado para download; guia de desenvolvimento em `docs/desenvolvimento.md`; especificação, schema,
  CLI e skill `/knsproj` na versão 6.

## 0.1.2 — 2026-10-15

Formato `.knsproj` **v5**.

- Curva cíclica (onda entre dois keyframes) no editor de curvas.
- Camadas nulas 2D e 3D; câmera apontada por rotação, independente da posição.
- Trocar ou remover o pai de uma camada não a move mais na tela.

## 0.1.1 — 2026-10

Formato `.knsproj` **v3**.

- Expressões JavaScript no estilo do After Effects e animadores de texto por caractere.
- 60 efeitos em 13 categorias, interface redesenhada, tema claro/escuro, português/inglês, apresentação inicial e novo logo.
- Pipeline de release com assinatura de produção verificada.

## 0.1.0-beta

Primeira beta pública: timeline com keyframes e curvas, texto, formas, mídia, efeitos, máscaras, transições, motion
blur, 3D simplificado, exportação MP4 e o formato aberto `.knsproj`.
