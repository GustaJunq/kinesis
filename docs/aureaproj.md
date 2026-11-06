# Importar projetos do Aurea (`.aureaproj`)

O Kinesis abre projetos do Aurea (editor de vídeo mobile) pelo mesmo botão de importar `.knsproj`: o formato é
detectado pelo primeiro arquivo do zip. O formato do Aurea não é documentado; o que está abaixo foi mapeado a partir
de projetos salvos pelo Aurea 0.2.2 (formato `2.0.0`) e pode mudar a cada beta. O que não é reconhecido não é
adivinhado: vira um aviso mostrado depois da importação.

Código: `core/src/main/kotlin/dev/kinesis/core/format/AureaImport.kt`. Teste: `AureaImportTest`.

## Contêiner

Zip com:

| Arquivo | Conteúdo |
|---|---|
| `manifest.txt` | `aurea-project 1`, `title <título>`, `app <versão>` (texto) |
| `project.aurea` | binário little-endian, descrito abaixo |

## `project.aurea`

Cabeçalho de 64 bytes:

| Offset | Tipo | Campo |
|---|---|---|
| 0 | 4 bytes | assinatura `UREA` |
| 4 | u16, u16 | versão do contêiner (1.1) |
| 8 | u32 | número de seções |
| 12 | u32 | offset do índice de seções (64) |
| 20 | u32 | tamanho do arquivo |
| 28 | texto (36 bytes, termina em 0) | versão do formato (`2.0.0`) |

Índice: uma entrada de 40 bytes por seção.

| Offset | Tipo | Campo |
|---|---|---|
| 0 | u16 | tipo |
| 2 | u16 | versão da seção |
| 4 | u16 | flags |
| 6 | u64 | offset do corpo |
| 14 | u64 | tamanho |
| 22 | u64 | tamanho bruto (igual ao tamanho quando não comprimido) |
| 30 | u64 | CRC32 do corpo |

Os campos dos corpos são colados (sem alinhamento). Textos: u32 com o tamanho + bytes UTF-8.

### Seção 1: projeto (versão 1)

Título; 8 bytes desconhecidos; criação e modificação (u64, milissegundos desde 1970); depois resolução
(u32 × 2), fps (f64) e configurações ainda não mapeadas (taxa de áudio 48000, ...).

### Seção 2: composições e camadas (versão 46)

Composição: prefixo desconhecido, nome, largura e altura (u32), fps (f64), duração em quadros (u32).

Camada: id (u32, > 0), tipo (u8), nome. A partir do fim do nome:

| Offset | Tipo | Campo |
|---|---|---|
| +4 | u32 | quadro inicial |
| +8 | u32 | quadro final |
| +78 | f32 × 3 | posição x, y, z (pixels, origem no canto superior esquerdo) |
| +90 | f32 × 3 | escala x, y, z |

Tipos conhecidos: `0x0a` = objeto 3D (forma **ou** texto) e `0x08` = câmera. Os demais são ignorados. A seção 2 foi
testada nas versões 46 e 48.

Para objetos 3D, a partir do fim do nome: u32 + texto em +155 (`Texto` nas formas; o conteúdo nos textos), cor (4 bytes)
e o tamanho (f32, 100 por padrão) em 8 bytes depois do fim da cor. A câmera usa os mesmos offsets de posição
(por padrão em `(largura/2, altura/2, −1,2 × altura)`).

Câmera, depois da escala: 6 canais (rotação x, y, z em graus; posição x, y, z). Cada bloco tem o valor no primeiro
keyframe, o byte `02` e a Bézier da curva (x1 y1 x2 y2, f32), o quadro do segundo keyframe (u8 depois de `01`, 90 no
exemplo), o valor nele e `01` + outra Bézier. Só canais com dois keyframes foram vistos; os blocos não são alinhados, então
são achados por esses padrões.

### Seção 4: modelos 3D (versão 1)

Por objeto: nome da camada e uma string `aurea-shape3d:v1;k=…;m=…;c0=…;…;c5=…;` (`c0`…`c5`: cor RGBA de cada face,
em hexadecimal), seguida mais adiante do nome do primitivo (`cube`, …). Cada objeto aparece duas vezes.

Textos 3D: `aurea-text3d:v2;d=<profundidade>;a=<0|1|2>;c=<rgba>;bv=<…>;m0=<cor>/<metal>/<rugosidade>/…;m0e=<emissivo>/…;font=;anim=…;t=<texto>`.
Todos os textos se chamam `Texto 3D`, então a correspondência é feita pelo **conteúdo**, não pelo nome.

## Conversão

| Aurea | Kinesis |
|---|---|
| título, criação, modificação | `meta` |
| composição (tamanho, fps, quadros) | `composition` (duração em segundos) |
| forma 3D | camada `object3d`; posição convertida para origem no centro; cor da primeira face |
| texto 3D | camada `object3d` com `Primitive.TEXT`: conteúdo, alinhamento, profundidade, chanfro, cor e material; tamanho da fonte = tamanho Aurea × 3,45 (aproximado) |
| câmera | camada `camera`: posição e rotação (com keyframes e Bézier) convertidas; campo de visão derivado da distância inicial (`2·atan((altura/2)/|z|)`), fixo |

Cada tipo de objeto tem versão própria na string (`aurea-shape3d:v1`, `aurea-text3d:v2`); outra versão gera um aviso.
O contêiner está descrito em [aureaproj.ksy](aureaproj.ksy) (Kaitai Struct, sem validação com o compilador).

Ainda não importados (avisados): rotação, keyframes, efeitos, campo de visão animado da câmera, rotação/keyframes dos objetos, fonte e chanfro exatos do texto, mídia e demais tipos de camada.
Para mapear mais campos, compare projetos que diferem em uma única coisa (um keyframe, uma rotação, um texto).

---

# Camadas v55 (Aurea 0.4.x)

Mapeado a partir de "The oldest buh in the buh" (Aurea 0.4.6-beta, 15 composições, 50 camadas). Código:
`AureaV55.kt`; teste: `AureaV55ImportTest` (com uma cópia reduzida do arquivo em `core/src/test/resources/aurea/`).
A seção 2 com versão ≥ 50 usa este leitor; versões menores usam o descrito acima.

## Contêiner

O zip tem também `media/NNN-nome.ext` (arquivos de mídia, sem compressão); `manifest.txt` ganha linhas
`media <entrada>\t<caminho original>`. Seções: 1 = projeto (título, datas, composição principal), 2 = composições
e camadas, 4 = pool de mídia, 6 = 59 bytes não identificados (vira aviso).

## Seção 4: pool de mídia

`u32 n`, depois `n` itens: `u32 índice`, `u32 1`, `u8 tipo` (1 vídeo, 2 áudio, 3 imagem, 5 modelo), nome (u32+UTF-8),
caminho original, 128 bytes (largura e altura u32 em +47 e +51), nome de novo, 30 bytes. A mídia de índice sem
arquivo no zip (ex.: `content://`) gera aviso e as camadas que a usam são ignoradas.

## Seção 2: composições

Cada composição: `u32 índice`, `u32 1`, nome, `u32 largura`, `u32 altura`, `f64 fps`, `u32 quadros`, camadas e rodapé.
Os tempos são em quadros da composição.

### Camada

`u32 1`, `u8 tipo` (1 vídeo, 2 imagem, 3 áudio, 5 forma/retângulo, 6 nulo, 10 modelo 3D, 12 pré-composição), nome. Depois
do nome (offsets relativos ao fim do nome): `+0 u64 início`, `+8 u64 fim`, `+16 i64 deslocamento da fonte` (quadro
da mídia/composição que cai no início), `+43 i32 pai`, `+51 u32 id`, `+55 u32 opções` (bit 0x04 = oculta),
`+61 i32 mídia`, `+69 i32 composição`, `+78 f32×3 posição`, `+90 escala`, `+102 rotação`, `+114 âncora`,
`+126 f32 opacidade`, `+138 f32` (sempre 1), `+143` trilhas.

Posição e âncora medem a partir do canto superior esquerdo (da composição, ou do conteúdo para a âncora); no Kinesis
a origem é o centro. Filhos medem a posição a partir do canto do pai.

### Trilhas e keyframes

`u32 n`; cada trilha: `u16 canal`, `i32 efeito`, `u32 parâmetro`, `f32 estático`, `u32 k`, `k × 40 bytes`
(`u64 quadro`, `f32 valor`, `u8 interpolação`, `f32×4 Bézier`, 11 bytes). Canais: 0–2 posição, 3–5 escala, 6–8 rotação
(graus), 9–11 âncora, 12 opacidade, 31 parâmetro de efeito, 32 volume (não confirmado). Interpolação: 1 linear,
2 Bézier, 8 "ease" (aproximado por easeOutExpo, com aviso). Os tempos dos keyframes são absolutos na composição;
no Kinesis são relativos ao início da camada (`quadro − deslocamento`). Canais x/y/z com tempos diferentes são
reamostrados e combinados.

### Efeitos, forma e rodapé

Depois das trilhas: `u32 n` efeitos (`u32 idx`, `u32 tipo`, `u8 ligado`, `u8`, `u32 pc`, `pc × 29 bytes` com
`f32×4` de valor, 16 bytes finais). `0x59008a59` = desfoque (raio Kinesis = 0,4 × valor); os demais viram aviso com os
parâmetros. Depois: contagem de máscaras (0), texto "Texto", e campos fixos; o tamanho da forma fica 90 bytes após
o texto. Modelos: índice de mídia 227 bytes após o texto. Rodapé: assinatura `66 66 66 3f a6 9b c4 3a`, contagem de
marcadores em +130, cada um `u64 quadro`, `u32 cor ARGB`, nome, `u32`; o resto é ignorado (aviso).

## Conversão

- Pré-composição → grupo com os filhos da composição referenciada, recortado ao tamanho dela.
- Ordem de desenho: z decrescente, estável (confirmado contra a renderização do próprio Aurea).
- Modelo 3D: tamanho 0,45 × altura e câmera equivalente (z = −1,2 × altura).
- Composições não usadas, canais, efeitos, opções e seções desconhecidos viram avisos.
