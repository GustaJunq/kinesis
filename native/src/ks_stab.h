#ifndef KS_STAB_H
#define KS_STAB_H
#include <stdint.h>

/* Handle-based wrapper around vid.stab (YUV 4:2:0 planar frames, planes given with their own strides). */
typedef struct KsStab KsStab;

/* shakiness 1..10 (how shaky the video is), accuracy 1..15 (detection accuracy). width/height must be even. NULL on failure. */
KsStab* ks_stab_new(int width, int height, int shakiness, int accuracy);

/* Pass 1: feed every frame in order. Returns 0 on success. */
int ks_stab_detect(KsStab* s, const uint8_t* y, int ys, const uint8_t* u, int us, const uint8_t* v, int vs);

/* Between the passes: smoothing = frames on each side used for the camera path (e.g. 15),
 * zoom in percent added on top (0 = none), optzoom 0 none / 1 optimal static zoom (hides the borders) / 2 adaptive,
 * crop_black 1 = black borders, 0 = keep the border of earlier frames. Returns 0 on success. */
int ks_stab_prepare(KsStab* s, int smoothing, double zoom, int optzoom, int crop_black);

/* Pass 2: the next frame, in order, from src planes into dst planes (they must not overlap). Returns 0 on success. */
int ks_stab_apply(KsStab* s,
                  const uint8_t* sy, int sys, const uint8_t* su, int sus, const uint8_t* sv, int svs,
                  uint8_t* dy, int dys, uint8_t* du, int dus, uint8_t* dv, int dvs);

int ks_stab_frames(const KsStab* s);
void ks_stab_free(KsStab* s);
#endif
