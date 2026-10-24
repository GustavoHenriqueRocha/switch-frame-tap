/* audiopace_test - tools/raw-recv/audiopace.h against a simulated sound device.
 *
 * The device pulls 512 frames (10.67 ms) at a time while playing, as SDL's
 * queue does with raw-view's 512-sample spec. The console's audio packets are
 * made every 16.67 ms; arrival jitters by +-3 ms and, once every 0.6-1.4 s,
 * the link stalls 30-60 ms and the late packets then arrive together (what
 * the viewer log shows as "worst gap 40-60 ms"). Fixed seed: reproducible.
 *
 * Checks: on the stalling link the adaptive cushion has at least 5x fewer
 * underruns than upstream's fixed 40 ms, while the sound queued on average
 * stays within 20 ms of upstream's; on a clean link it stays at 40 ms (no added latency).
 */
#include <stdio.h>
#include <stdlib.h>
#include "../audiopace.h"

static unsigned long long rng = 88172645463325252ULL;
static double urand(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return (rng >> 11) * (1.0 / 9007199254740992.0); }

typedef struct { long underruns, drops, clears; double avg_q; int final_target; } result_t;

static result_t sim(int fixed, int stalls, double secs)
{
    rng = 88172645463325252ULL;
    apace_t a;
    apace_init(&a, fixed);
    const double pkt_ms = 1000.0 / 60.0, chunk_ms = 512.0 / 48.0;
    double queued = 0, next_pull = 0, q_sum = 0; long q_n = 0;
    int playing = 0;
    /* arrival times */
    const long npk = (long)(secs * 60);
    double *arr = malloc(sizeof(double) * npk);
    double stall_at = 800, stall_len = 0;
    for (long i = 0; i < npk; i++) {
        const double made = i * pkt_ms;
        double t = made + (urand() * 6.0 - 3.0);
        if (stalls) {
            if (made >= stall_at) {
                stall_len = 30 + urand() * 30;
                stall_at = made + 600 + urand() * 800;
                arr[i] = made + stall_len;
                double end = arr[i];
                for (long j = i + 1; j < npk && (j * pkt_ms) < made + stall_len; j++) arr[j] = end + (j - i) * 0.2;
                long j = i + 1; while (j < npk && j * pkt_ms < made + stall_len) j++;
                i = j - 1;
                continue;
            }
        }
        arr[i] = t;
    }
    long k = 0;
    for (double now = 0; now < secs * 1000; now += 0.25) {
        while (k < npk && arr[k] <= now) {
            int pc;
            const int act = apace_packet(&a, (int)queued, (int)(pkt_ms + 0.5), &pc);
            if (act == AP_CLEAR_QUEUE) queued = 0;
            if (act != AP_DROP) queued += pkt_ms;
            if (pc == -1) playing = 0;
            if (pc == +1) { playing = 1; next_pull = now; }
            k++;
        }
        if (playing && now >= next_pull) {
            queued -= chunk_ms;
            if (queued < 0) queued = 0;
            next_pull += chunk_ms;
        }
        if (playing) { q_sum += queued; q_n++; }
    }
    free(arr);
    result_t r = { a.underruns, a.drops, a.resyncs, q_n ? q_sum / q_n : 0, a.target_ms };
    return r;
}

int main(void)
{
    int fail = 0;
    const result_t f = sim(1, 1, 120), d = sim(0, 1, 120), fc = sim(1, 0, 60), dc = sim(0, 0, 60);
    printf("stalling link, 120 s: fixed 40 ms: %ld underruns, %ld clears, %.1f ms queued | adaptive: %ld underruns, %ld drops, %.1f ms queued, target %d ms\n",
           f.underruns, f.clears, f.avg_q, d.underruns, d.drops, d.avg_q, d.final_target);
    printf("clean link, 60 s:     fixed 40 ms: %ld underruns, %.1f ms queued | adaptive: %ld underruns, %.1f ms queued, target %d ms\n",
           fc.underruns, fc.avg_q, dc.underruns, dc.avg_q, dc.final_target);
    if (!(f.underruns >= 20)) { printf("FAIL: the model should reproduce upstream's underruns\n"); fail = 1; }
    if (!(d.underruns * 5 <= f.underruns)) { printf("FAIL: adaptive should have 5x fewer underruns\n"); fail = 1; }
    if (!(d.avg_q < f.avg_q + 20.0)) { printf("FAIL: adaptive queues too much sound (latency)\n"); fail = 1; }
    if (!(dc.final_target == AP_MIN_MS && dc.underruns == 0 && dc.avg_q < fc.avg_q + 5.0)) { printf("FAIL: a clean link must stay at the minimum\n"); fail = 1; }
    return fail;
}
