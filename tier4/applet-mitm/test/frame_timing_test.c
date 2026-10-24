/* fork (melhorias-switch): the per-frame timing word (sft_frame_timing.h) -
 * what the console packs into the SFTR header and the viewer reads back.
 * Built as C (the viewer) and as C++ (the sysmodule) by run_pc_tests.sh. */
#include "sft_frame_timing.h"
#include <stdio.h>
#include <math.h>

static int g_fail = 0;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); ++g_fail; } } while (0)
#define NEAR(a, b) (fabs((a) - (b)) < 1e-9)

int main(void)
{
    sft_timing_t t;
    /* an older console: the field was always 0 */
    CHECK(!sft_timing_unpack(0u, &t));
    CHECK(!sft_timing_unpack(4u, &t));                 /* a raw stream's block height */

    /* BOTW-like: 32.5 ms of game, 2.4 ms read, 0.7 ms VIC, 1.5 ms NVENC */
    uint32_t w = sft_timing_pack(32500000u, 2400000u, 700000u, 1500000u, 0);
    CHECK(w & SFT_TIMING_VALID);
    CHECK(!(w & SFT_TIMING_READY));
    CHECK(sft_timing_unpack(w, &t));
    CHECK(NEAR(t.wait_ms, 32.5) && NEAR(t.read_ms, 2.4) && NEAR(t.vic_ms, 0.7) && NEAR(t.enc_ms, 1.5) && !t.ready);

    /* rounding to the unit, and the ready flag */
    w = sft_timing_pack(1240000u, 149999u, 50000u, 124999u, 1);
    CHECK(sft_timing_unpack(w, &t));
    CHECK(NEAR(t.wait_ms, 1.0) && NEAR(t.read_ms, 0.1) && NEAR(t.vic_ms, 0.1) && NEAR(t.enc_ms, 0.0) && t.ready);

    /* each field saturates at its maximum and never spills into the next */
    w = sft_timing_pack(UINT64_C(5000000000), UINT64_C(5000000000), UINT64_C(5000000000), UINT64_C(5000000000), 1);
    CHECK(sft_timing_unpack(w, &t));
    CHECK(NEAR(t.wait_ms, 127.5) && NEAR(t.read_ms, 25.5) && NEAR(t.vic_ms, 12.7) && NEAR(t.enc_ms, 31.75) && t.ready);
    CHECK(w == 0xFFFFFFFFu);
    w = sft_timing_pack(0, UINT64_C(5000000000), 0, 0, 0);
    CHECK(w == (SFT_TIMING_VALID | 0xFF00u));
    w = sft_timing_pack(0, 0, UINT64_C(5000000000), 0, 0);
    CHECK(w == (SFT_TIMING_VALID | (0x7Fu << 16)));
    w = sft_timing_pack(0, 0, 0, UINT64_C(5000000000), 0);
    CHECK(w == (SFT_TIMING_VALID | (0x7Fu << 23)));
    /* all zero is still a valid split (the marker bit) */
    CHECK(sft_timing_unpack(sft_timing_pack(0, 0, 0, 0, 0), &t) && NEAR(t.wait_ms + t.read_ms + t.vic_ms + t.enc_ms, 0.0));

    printf("%s (%d failure%s)\n", g_fail == 0 ? "frame timing word OK" : "FAILED", g_fail, g_fail == 1 ? "" : "s");
    return g_fail == 0 ? 0 : 1;
}
