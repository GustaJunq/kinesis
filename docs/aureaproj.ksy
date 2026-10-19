meta:
  id: aureaproj
  title: Aurea project (project.aurea)
  file-extension: aurea
  endian: le
  encoding: UTF-8
doc: |
  Container of `project.aurea`, the binary inside an `.aureaproj` zip (format 2.0.0, saved by Aurea 0.2.2).
  Recreated (not reverse-engineered!) from sample projects and NOT validated with the Kaitai compiler. Only the container is
  described: header, section index and CRC32. Section bodies are left opaque; their layout (layers, 3D models,
  keyframes) is documented in aureaproj.md because it is only known by offsets found with heuristics.
  Layout of a camera channel, for reference (not parsed here): f32 value, u8 0x02, 4 x f32 Bezier (x1 y1 x2 y2),
  10 zero bytes, u8 0x01, u8 frame of the second keyframe, 7 zero bytes, f32 value, u8 0x01, 4 x f32 Bezier.
seq:
  - id: magic
    contents: "UREA"
  - id: container_major
    type: u2
  - id: container_minor
    type: u2
  - id: section_count
    type: u4
  - id: index_offset
    type: u4
    doc: Offset of the section index (64 in the samples).
  - id: unknown_16
    size: 4
  - id: file_size
    type: u4
  - id: unknown_24
    size: 4
  - id: format_version
    type: strz
    size: 36
    doc: "`2.0.0`"
types:
  section_entry:
    seq:
      - id: type
        type: u2
        doc: "1 = project, 2 = compositions and layers, 4 = 3D models and texts (others not mapped)."
      - id: version
        type: u2
        doc: Section version (2 was seen at 46 and 48).
      - id: flags
        type: u2
      - id: offset
        type: u8
      - id: size
        type: u8
      - id: raw_size
        type: u8
        doc: Equal to size when the body is not compressed.
      - id: crc32
        type: u8
        doc: CRC32 of the body, in the low 32 bits.
      - id: unknown_38
        size: 2
    instances:
      body:
        pos: offset
        size: size
        io: _root._io
instances:
  sections:
    pos: index_offset
    io: _root._io
    type: section_entry
    repeat: expr
    repeat-expr: section_count
