/* frame_timing.h - fork (melhorias-switch): where a frame's console-side age
 * went, carried in the SFTR header of every H.264 frame.
 *
 * The header's `stride` has carried the console-side age since M85: the
 * game's queueBuffer -> the frame's header sent, in microseconds - the
 * viewer's "console" latency. It mixes two different things:
 *
 *   - the GAME: queueBuffer happens when the game hands the frame to the
 *     display, but the GPU may still be drawing it (the acquire fence). A
 *     GPU-bound game (BOTW unlocked at 35-45 fps) queues a frame 20-40 ms
 *     before it is drawn; the console's own screen waits for that fence too.
 *   - the CAPTURE: reading the slot, the VIC, NVENC, the USB send.
 *
 * `block_h_log2` (offset 24) is unused for H.264 (0 from every console
 * before this), so it carries the split, packed into 32 bits:
 *
 *   bit  31     1 = this word is valid (a console that fills it)
 *   bit  30     1 = the present was already drawn when the capture took it:
 *                   `wait` then includes time the capture loop was busy with
 *                   the frame before (a capture cost), not only the GPU
 *   bits 0-7    wait  : present -> slot read starts, 0.5 ms units (<= 127 ms)
 *   bits 8-15   read  : the CPU copy of the slot (+ cache flush), 0.1 ms (<= 25.5)
 *   bits 16-22  vic   : read done -> NVENC submitted (VIC + setup), 0.1 ms (<= 12.7)
 *   bits 23-29  enc   : NVENC submitted -> its end noticed, 0.25 ms (<= 31.75)
 *
 * Each field saturates at its maximum. What is left of the age (age minus
 * the four) is the send: waiting for the previous USB transfer, the audio
 * packet sent before the frame, the bitstream copy. Viewers that do not know
 * the field ignore it (raw-view before this fork, the Android app).
 */
#ifndef SFT_FRAME_TIMING_H
#define SFT_FRAME_TIMING_H

#include <stdint.h>

#define SFT_TIMING_VALID 0x80000000u
#define SFT_TIMING_READY 0x40000000u

typedef struct {
    double wait_ms, read_ms, vic_ms, enc_ms;
    int ready;     /* the present was already drawn when taken */
} sft_timing_t;

static inline uint32_t sft_timing_field(uint64_t ns, uint64_t unit_ns, uint32_t max)
{
    const uint64_t v = (ns + unit_ns / 2) / unit_ns;
    return v > max ? max : (uint32_t)v;
}

/* the console: durations in nanoseconds */
static inline uint32_t sft_timing_pack(uint64_t wait_ns, uint64_t read_ns, uint64_t vic_ns, uint64_t enc_ns, int ready)
{
    return SFT_TIMING_VALID | (ready ? SFT_TIMING_READY : 0u)
         | sft_timing_field(wait_ns, 500000u, 255u)
         | sft_timing_field(read_ns, 100000u, 255u) << 8
         | sft_timing_field(vic_ns, 100000u, 127u) << 16
         | sft_timing_field(enc_ns, 250000u, 127u) << 23;
}

/* the viewer: 0 if the word carries no split (an older console) */
static inline int sft_timing_unpack(uint32_t w, sft_timing_t *t)
{
    if (!(w & SFT_TIMING_VALID)) return 0;
    t->wait_ms = (w & 0xFFu) * 0.5;
    t->read_ms = ((w >> 8) & 0xFFu) * 0.1;
    t->vic_ms  = ((w >> 16) & 0x7Fu) * 0.1;
    t->enc_ms  = ((w >> 23) & 0x7Fu) * 0.25;
    t->ready   = (w & SFT_TIMING_READY) != 0;
    return 1;
}

#endif
