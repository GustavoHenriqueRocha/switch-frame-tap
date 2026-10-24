/* fork: an adaptive cushion for the game audio (raw-view.c audio_packet).
 *
 * Upstream starts playback once 40 ms are queued and clears the queue past
 * 120 ms. The console's packets come every ~16 ms but USB/network stalls of
 * 30-60 ms happen every second or so (the log's "worst gap"), so a fixed 40 ms
 * runs dry over and over: underruns with only 30-50 ms queued.
 *
 * Here the cushion is a target that moves:
 *   - every underrun raises it by AP_STEP_UP_MS (up to AP_MAX_MS): the next
 *     start waits for more sound;
 *   - AP_CALM_MS without an underrun lowers it by AP_STEP_DOWN_MS (down to
 *     AP_MIN_MS): the latency comes back once the link is calm;
 *   - more than target + AP_SLACK_MS queued drops the incoming packet (one
 *     packet, ~16 ms) instead of clearing the whole queue - the picture stays
 *     in sync without a gap of silence and a fresh rebuild.
 * --audio-fixed keeps upstream's 40/120 ms behaviour.
 *
 * The test's model of that link (stalls of 30-60 ms every 0.6-1.4 s, 120 s):
 * fixed 40 ms 53 underruns, 56.8 ms queued on average; adaptive 6 underruns,
 * 69.4 ms queued (target settles at 45 ms). A clean link: both 0 underruns,
 * 34.5 ms queued - no latency added. (Steps up of 10 ms: 5 underruns but
 * 84.7 ms queued; the slack never triggered in the model.)
 *
 * Pure logic (no SDL) so tools/test/audiopace_test.c can drive it with a
 * simulated device and packet arrivals.
 */
#pragma once
#include <stdint.h>

#ifndef AP_MIN_MS
#define AP_MIN_MS 40
#endif
#ifndef AP_MAX_MS
#define AP_MAX_MS 120
#endif
#ifndef AP_STEP_UP_MS
#define AP_STEP_UP_MS 5
#endif
#ifndef AP_STEP_DOWN_MS
#define AP_STEP_DOWN_MS 5
#endif
#ifndef AP_CALM_MS
#define AP_CALM_MS 20000
#endif
#ifndef AP_SLACK_MS
#define AP_SLACK_MS 80
#endif

typedef struct {
    int fixed;          /* 1: upstream's fixed 40 ms start / 120 ms clear */
    int target_ms;      /* queued sound needed before playing (again) */
    int calm_ms;        /* sound received since the last underrun or step down */
    int paused;         /* 1: building the cushion */
    long underruns, drops, resyncs;
} apace_t;

static void apace_init(apace_t *a, int fixed)
{
    a->fixed = fixed;
    a->target_ms = AP_MIN_MS;
    a->calm_ms = 0;
    a->paused = 1;
    a->underruns = a->drops = a->resyncs = 0;
}

enum { AP_QUEUE = 0, AP_DROP = 1, AP_CLEAR_QUEUE = 2 };

/* a packet of pkt_ms arrives with queued_ms still queued on the device.
 * Returns what to do with it; *pause_change is -1 (pause the device),
 * +1 (start it) or 0 - after queueing it, when queueing. */
static int apace_packet(apace_t *a, int queued_ms, int pkt_ms, int *pause_change)
{
    int action = AP_QUEUE;
    *pause_change = 0;
    if (!a->paused && queued_ms == 0) {        /* ran dry: build the cushion again, a bigger one */
        a->paused = 1;
        *pause_change = -1;
        a->underruns++;
        a->calm_ms = 0;
        if (!a->fixed && a->target_ms < AP_MAX_MS) {
            a->target_ms += AP_STEP_UP_MS;
            if (a->target_ms > AP_MAX_MS) a->target_ms = AP_MAX_MS;
        }
    }
    if (a->fixed) {
        if (queued_ms > 120) { action = AP_CLEAR_QUEUE; queued_ms = 0; a->resyncs++; }
    } else {
        a->calm_ms += pkt_ms;
        if (a->calm_ms >= AP_CALM_MS) {
            a->calm_ms = 0;
            if (a->target_ms > AP_MIN_MS) a->target_ms -= AP_STEP_DOWN_MS;
            if (a->target_ms < AP_MIN_MS) a->target_ms = AP_MIN_MS;
        }
        if (queued_ms > a->target_ms + AP_SLACK_MS) { a->drops++; return AP_DROP; }
    }
    if (a->paused && queued_ms + pkt_ms >= a->target_ms) {
        a->paused = 0;
        *pause_change = +1;
    }
    return action;
}
