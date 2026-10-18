# vid.stab 1.1.0

Video stabilization library by Georg Martius and contributors, <http://public.hronopik.de/vid.stab/>
(upstream <https://github.com/georgmartius/vid.stab>).

- **License:** GNU General Public License, version 2 or (at your option) any later version (see the header of each
  source file). Kinesis is GPL-3.0; the "or any later version" clause makes the two compatible.
- **Source:** the unmodified upstream 1.1.0 release tarball (`libvidstab_1.1.0.orig.tar.gz`,
  SHA-256 `14d2a053e56edad4f397be0cb3ef8eb1ec3150404ce99a426c4eb641861dc0bb`), with only the `src/orc/` optimisations
  (optional, not used) and the Visual Studio project file removed.
- It is compiled into `libkinesis_native.so` together with `native/src/ks_stab.c`, a small C wrapper that exposes a
  handle-based API (detect motion frame by frame, prepare the smoothed transforms, transform frames).
- `src/transformfloat.*` (only used by upstream's own tests) is not included either.
