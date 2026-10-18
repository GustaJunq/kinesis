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

Tipo conhecido: `0x0a` = forma 3D. Os demais tipos ainda são ignorados.

### Seção 4: modelos 3D (versão 1)

Por objeto: nome da camada e uma string `aurea-shape3d:v1;k=…;m=…;c0=…;…;c5=…;` (`c0`…`c5`: cor RGBA de cada face,
em hexadecimal), seguida mais adiante do nome do primitivo (`cube`, …). Cada objeto aparece duas vezes.

## Conversão

| Aurea | Kinesis |
|---|---|
| título, criação, modificação | `meta` |
| composição (tamanho, fps, quadros) | `composition` (duração em segundos) |
| forma 3D | camada `object3d`; posição convertida para origem no centro; cor da primeira face |

Ainda não importados (avisados): rotação, keyframes, efeitos, câmera, texto, mídia e demais tipos de camada.
Para mapear mais campos, compare projetos que diferem em uma única coisa (um keyframe, uma rotação, um texto).
