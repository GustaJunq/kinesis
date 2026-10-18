/* Thin handle-based wrapper around vid.stab (GPL-2.0-or-later), see native/third_party/vidstab. */
#include <string.h>
#include "ks_stab.h"
#include "libvidstab.h"

struct KsStab {
    VSFrameInfo fi;
    VSMotionDetect md;
    VSTransformData td;
    VSTransformations trans;
    VSManyLocalMotions mlms;
    int detect_ready;
    int transform_ready;
    int frames;
};

static void set_frame(VSFrame* f, const uint8_t* y, int ys, const uint8_t* u, int us, const uint8_t* v, int vs) {
    memset(f, 0, sizeof(*f));
    f->data[0] = (uint8_t*)y; f->linesize[0] = ys;
    f->data[1] = (uint8_t*)u; f->linesize[1] = us;
    f->data[2] = (uint8_t*)v; f->linesize[2] = vs;
}

KsStab* ks_stab_new(int width, int height, int shakiness, int accuracy) {
    if (width < 16 || height < 16 || (width & 1) || (height & 1)) return NULL;
    KsStab* s = (KsStab*)calloc(1, sizeof(KsStab));
    if (!s) return NULL;
    vs_log_level = 0; /* no logging from the library (it would go to stderr) */
    if (!vsFrameInfoInit(&s->fi, width, height, PF_YUV420P)) { free(s); return NULL; } /* returns 1 on success */
    VSMotionDetectConfig conf = vsMotionDetectGetDefaultConfig("kinesis");
    conf.shakiness = shakiness < 1 ? 1 : (shakiness > 10 ? 10 : shakiness);
    conf.accuracy = accuracy < 1 ? 1 : (accuracy > 15 ? 15 : accuracy);
    conf.numThreads = 1;
    if (vsMotionDetectInit(&s->md, &conf, &s->fi) != VS_OK) { free(s); return NULL; }
    vs_vector_init(&s->mlms, 256);
    s->detect_ready = 1;
    return s;
}

int ks_stab_detect(KsStab* s, const uint8_t* y, int ys, const uint8_t* u, int us, const uint8_t* v, int vs) {
    if (!s || !s->detect_ready || s->transform_ready) return -1;
    VSFrame frame;
    set_frame(&frame, y, ys, u, us, v, vs);
    LocalMotions lms;
    if (vsMotionDetection(&s->md, &lms, &frame) != VS_OK) return -2;
    vs_vector_append_dup(&s->mlms, &lms, sizeof(LocalMotions));
    s->frames++;
    return 0;
}

int ks_stab_prepare(KsStab* s, int smoothing, double zoom, int optzoom, int crop_black) {
    if (!s || !s->detect_ready || s->transform_ready || s->frames < 1) return -1;
    VSTransformConfig tc = vsTransformGetDefaultConfig("kinesis");
    tc.relative = 1;
    tc.smoothing = smoothing < 0 ? 0 : smoothing;
    tc.crop = crop_black ? VSCropBorder : VSKeepBorder;
    tc.zoom = zoom;
    tc.optZoom = optzoom;
    tc.interpolType = VS_BiLinear;
    tc.simpleMotionCalculation = 1; /* fast and good for hand-held footage; avoids the gradient-descent pass */
    if (vsTransformDataInit(&s->td, &tc, &s->fi, &s->fi) != VS_OK) return -2;
    vsTransformationsInit(&s->trans);
    if (vsLocalmotions2Transforms(&s->td, &s->mlms, &s->trans) != VS_OK) return -3;
    if (vsPreprocessTransforms(&s->td, &s->trans) != VS_OK) return -4;
    s->transform_ready = 1;
    s->frames = 0; /* now counts the transformed frames */
    return 0;
}

int ks_stab_apply(KsStab* s,
                  const uint8_t* sy, int sys, const uint8_t* su, int sus, const uint8_t* sv, int svs,
                  uint8_t* dy, int dys, uint8_t* du, int dus, uint8_t* dv, int dvs) {
    if (!s || !s->transform_ready) return -1;
    VSFrame src, dst;
    set_frame(&src, sy, sys, su, sus, sv, svs);
    set_frame(&dst, dy, dys, du, dus, dv, dvs);
    if (vsTransformPrepare(&s->td, &src, &dst) != VS_OK) return -2;
    VSTransform t = vsGetNextTransform(&s->td, &s->trans);
    if (vsDoTransform(&s->td, t) != VS_OK) return -3;
    vsTransformFinish(&s->td);
    s->frames++;
    return 0;
}

int ks_stab_frames(const KsStab* s) { return s ? s->frames : 0; }

void ks_stab_free(KsStab* s) {
    if (!s) return;
    if (s->detect_ready) {
        vsMotionDetectionCleanup(&s->md);
        for (int i = 0; i < vs_vector_size(&s->mlms); i++) {
            LocalMotions* lm = VSMLMGet(&s->mlms, i);
            if (lm) vs_vector_del(lm);
        }
        vs_vector_fini(&s->mlms);
    }
    if (s->transform_ready) {
        vsTransformDataCleanup(&s->td);
        vsTransformationsCleanup(&s->trans);
    }
    free(s);
}
