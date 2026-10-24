/* decbench - what one 1080p frame costs the viewer's decoder thread, mode by
 * mode: one access unit in, one frame out, as raw-view's h264_decode does.
 *
 *   make decbench
 *   bash bench-clip.sh /tmp/clip8.h264 8          # a 1080p60 IDR+P clip like the console's
 *   ./decbench /tmp/clip8.h264 sw1 paced           # paced = at 60 fps, as live (clocks idle between frames)
 *
 * modes: sw1          software, one thread (the viewer's CPU default)
 *        sw1fast      + AV_CODEC_FLAG2_FAST
 *        ftN          N frame threads (N-1 frames of delay: "delay=" in the output)
 *        sliceN       N slice threads (the console sends ONE slice: no gain)
 *        vaapi_copy   VAAPI + av_hwframe_transfer_data (upstream's GPU path)
 *        vaapi_map    VAAPI + av_hwframe_map (+ vaapi_mapread: and a copy out of it)
 *        vaapi_sync   VAAPI + vaSyncSurface only (zerocopy.h: the frame stays on the GPU)
 *
 * Fork measurements (i3-8130U, UHD 620, iHD; synthetic testsrc2+noise clip,
 * 8 Mbps, paced): sw1 6.9 ms (p99 10.5, max 26), ft2 0.2 ms + 1 frame delay,
 * slice4 6.6 ms, vaapi_copy 7.1 ms (p99 12.9, max 19), vaapi_mapread 5.7 ms
 * (p99 12.0), vaapi_sync 2.1 ms (p99 4.8, max 7.2). FLAG2_FAST: no gain.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_vaapi.h>
#include <va/va.h>

static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1e3 + t.tv_nsec / 1e6; }
static int cmpd(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }
static enum AVPixelFormat get_fmt(AVCodecContext *c, const enum AVPixelFormat *f)
{
    (void)c;
    for (; *f != AV_PIX_FMT_NONE; f++) if (*f == AV_PIX_FMT_VAAPI) return *f;
    return AV_PIX_FMT_NONE;
}

int main(int argc, char **argv)
{
    if (argc < 3) { fprintf(stderr, "usage: decbench FILE.h264 MODE [paced]\n"); return 2; }
    const char *mode = argv[2];
    const int paced = argc > 3;
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror(argv[1]); return 1; }
    fseek(f, 0, SEEK_END); long sz = ftell(f); rewind(f);
    uint8_t *buf = malloc(sz + AV_INPUT_BUFFER_PADDING_SIZE);
    if (fread(buf, 1, sz, f) != (size_t)sz) return 1;
    fclose(f);
    memset(buf + sz, 0, AV_INPUT_BUFFER_PADDING_SIZE);

    /* the access units first, so parsing is not timed */
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    AVCodecParserContext *ps = av_parser_init(AV_CODEC_ID_H264);
    AVCodecContext *pc = avcodec_alloc_context3(codec);
    avcodec_open2(pc, codec, NULL);
    int cap = 1 << 16, n = 0;
    uint8_t **au = malloc(cap * sizeof(*au)); int *aul = malloc(cap * sizeof(int));
    for (long off = 0;;) {
        uint8_t *o; int ol;
        const int last = off >= sz;
        off += av_parser_parse2(ps, pc, &o, &ol, last ? NULL : buf + off, last ? 0 : (int)(sz - off), 0, 0, 0);
        if (ol && n < cap) {
            au[n] = malloc(ol + AV_INPUT_BUFFER_PADDING_SIZE);
            memcpy(au[n], o, ol); memset(au[n] + ol, 0, AV_INPUT_BUFFER_PADDING_SIZE); aul[n++] = ol;
        }
        if (last) break;
    }

    AVCodecContext *c = avcodec_alloc_context3(codec);
    AVBufferRef *hw = NULL;
    const int vaapi = !strncmp(mode, "vaapi", 5);
    if (vaapi) {
        if (av_hwdevice_ctx_create(&hw, AV_HWDEVICE_TYPE_VAAPI, NULL, NULL, 0)) { fprintf(stderr, "no VAAPI\n"); return 1; }
        c->hw_device_ctx = av_buffer_ref(hw); c->get_format = get_fmt;
        c->flags |= AV_CODEC_FLAG_LOW_DELAY; c->thread_count = 1;
    } else if (!strncmp(mode, "sw1", 3)) {
        c->flags |= AV_CODEC_FLAG_LOW_DELAY; c->thread_count = 1;
        if (!strcmp(mode, "sw1fast")) c->flags2 |= AV_CODEC_FLAG2_FAST;
    } else if (!strncmp(mode, "ft", 2)) {
        c->thread_count = atoi(mode + 2); c->thread_type = FF_THREAD_FRAME;
    } else if (!strncmp(mode, "slice", 5)) {
        c->flags |= AV_CODEC_FLAG_LOW_DELAY; c->thread_count = atoi(mode + 5); c->thread_type = FF_THREAD_SLICE;
    } else { fprintf(stderr, "unknown mode %s\n", mode); return 2; }
    if (avcodec_open2(c, codec, NULL)) return 1;

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frm = av_frame_alloc(), *sw = av_frame_alloc();
    double *t = calloc(n, sizeof(double)), sum_x = 0;
    int got = 0, first_out = -1;
    uint8_t *dst = malloc(4096 * 2304 * 2);
    const double t0 = now_ms();
    for (int i = 0; i < n; i++) {
        if (paced) { const double due = t0 + i * 16.667; while (now_ms() < due) { struct timespec s = { 0, 200000 }; nanosleep(&s, NULL); } }
        const double a = now_ms();
        pkt->data = au[i]; pkt->size = aul[i]; pkt->pts = i;
        avcodec_send_packet(c, pkt);
        if (avcodec_receive_frame(c, frm) != 0) continue;
        const double b = now_ms();
        if (first_out < 0) first_out = i;
        if (vaapi) {
            if (!strcmp(mode, "vaapi_copy")) {
                av_frame_unref(sw); av_hwframe_transfer_data(sw, frm, 0);
            } else if (!strncmp(mode, "vaapi_map", 9)) {
                av_frame_unref(sw); sw->format = AV_PIX_FMT_NV12;
                av_hwframe_map(sw, frm, AV_HWFRAME_MAP_READ);
                if (!strcmp(mode, "vaapi_mapread")) {
                    for (int y = 0; y < sw->height; y++) memcpy(dst + (size_t)y * sw->width, sw->data[0] + (size_t)y * sw->linesize[0], sw->width);
                    for (int y = 0; y < sw->height / 2; y++) memcpy(dst + (size_t)(sw->height + y) * sw->width, sw->data[1] + (size_t)y * sw->linesize[1], sw->width);
                }
                av_frame_unref(sw);
            } else if (!strcmp(mode, "vaapi_sync")) {
                const AVVAAPIDeviceContext *vc = ((AVHWDeviceContext *)hw->data)->hwctx;
                vaSyncSurface(vc->display, (VASurfaceID)(uintptr_t)frm->data[3]);
            }
        }
        const double x = now_ms() - b;
        av_frame_unref(frm);
        t[got++] = now_ms() - a;
        sum_x += x;
    }
    double sum = 0;
    for (int i = 0; i < got; i++) sum += t[i];
    if (!got) { fprintf(stderr, "no frames\n"); return 1; }
    qsort(t, got, sizeof(double), cmpd);
    printf("%-14s %s frames=%d delay=%d  avg %.2f ms (after receive_frame %.2f)  p50 %.2f  p95 %.2f  p99 %.2f  max %.2f\n",
           mode, paced ? "paced" : "flat ", got, first_out, sum / got, sum_x / got,
           t[got / 2], t[got * 95 / 100], t[got * 99 / 100], t[got - 1]);
    return 0;
}
