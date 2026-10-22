/*
 * v0.7.14: the system overlays in the stream. Off unless
 * sdmc:/config/switch-frame-tap/overlays.txt exists (beta).
 *
 * The stream copies the game's own frames, before the console composites
 * anything over them, and the composited screen cannot be read. So the
 * overlays come from their own processes, as the HOME menu's pictures do
 * from vi's:
 *   - overlayDisp (the volume bar, 010000000000100C) draws into a 7680 KB
 *     region: 2 x 1280x720 RGBA8, block-linear, 16-GOB blocks, premultiplied
 *     alpha (probe 1, logs/v0714-ovl1-run1/decoded). The bar sits in the
 *     top-left 448x128 - the first 224 KB of a buffer. It is read 5 times a
 *     second per buffer; when it changed it is copied, and shown for 2.5 s
 *     after its last change (the buffers keep the bar after it fades, so the
 *     content alone cannot say it is gone - probe 2).
 *   - Tesla / Ultrahand (nx-ovlloader) draws 2 x 448x720 RGBA4444 (1344 KB,
 *     premultiplied). Its presents come through vi:m (wrapped while this is
 *     on); while it presents, the buffer it last queued is converted to RGBA8
 *     10 times a second, and it is attached only then (attaching stalls it
 *     while a debug event waits; events are answered every 10 ms).
 * Both go into an area of the VIC's own output buffer that streaming does not
 * use (applet_mitm_nv.cpp lends it while the engines are up), double-
 * buffered, un-premultiplied: the VIC blends a slot over the game with
 * straight alpha whatever its factor fields say (the blend card, ovl2). The
 * VIC job adds them as slots 1 and 2 only while they show - with no overlay
 * on screen the job is the same as without this.
 * No floats are formatted (a stratosphere module has no newlib heap).
 */
#include <stratosphere.hpp>
#include <cstdio>
#include <cstring>
#include "applet_mitm_log.hpp"
#include "applet_mitm_dmdbg.hpp"
#include "applet_mitm_uibinder.hpp"
#include "applet_mitm_ovlprobe.hpp"
#include "applet_mitm_overlay.hpp"
#include "vic40_config.hpp"

namespace ams::mitm::applet {

    constinit std::atomic<bool> g_ovl_vi_mitm{false};
    constinit std::atomic<u32> g_tesla_txns{0}, g_tesla_presents{0};

    namespace {

        constexpr const char *OverlaysFile = "sdmc:/config/switch-frame-tap/overlays.txt";
        constexpr const char *ProbeFile    = "sdmc:/config/switch-frame-tap/probe-ovl.txt";
        constexpr u64 OverlayDispProgramId = UINT64_C(0x010000000000100C);

        constinit bool g_armed = false;
        constinit std::atomic<bool> g_calibrated{false};

        /* Tesla's presents, from its binder (the vi:m wrap) */
        constinit std::atomic<u64> g_tesla_present_tick{0};
        constinit std::atomic<u32> g_tesla_slot{0};
        constinit std::atomic<u32> g_tesla_buffers_gen{0};   /* setPreallocatedBuffer: new buffers */
        constinit u32 g_tesla_logged[16] = {};

        /* the area the engines lend */
        constinit os::SdkMutex g_area_lock;
        constinit u8 *g_area = nullptr;
        constinit std::atomic<u32> g_area_dev{0};
        constinit std::atomic<bool> g_area_up{false};

        /* its layout. Block-linear rows of 128 (16 GOBs of 8 rows), 8 KB per
         * 64-byte-wide GOB column of a block row */
        constexpr size_t BlockBytes = 0x2000;
        constexpr u32 BarW = 448, BarH = 128;                   /* 28 GOB columns, one block row */
        constexpr size_t BarBytes = 28 * BlockBytes;            /* 224 KB: the start of an overlayDisp buffer */
        constexpr u32 TeslaW = 448, TeslaH = 720;
        constexpr size_t TeslaSrcRow = 14 * BlockBytes;         /* a block row of its RGBA4444 (112 KB) */
        constexpr size_t TeslaSrcBuf = 6 * TeslaSrcRow;         /* one buffer, 768 rows (672 KB) */
        constexpr size_t TeslaRegion = 2 * TeslaSrcBuf;         /* 1344 KB */
        constexpr size_t TeslaDstRow = 28 * BlockBytes;         /* the same rows in RGBA8 (224 KB) */
        constexpr size_t TeslaDstBuf = 6 * TeslaDstRow;         /* 1344 KB */
        constexpr size_t OffBar[2]   = { 0, BarBytes };
        constexpr size_t OffTesla[2] = { 2 * BarBytes, 2 * BarBytes + TeslaDstBuf };
        constexpr size_t OffScratch  = 2 * BarBytes + 2 * TeslaDstBuf;
        constexpr size_t AreaNeeded  = OffScratch + TeslaSrcRow;
        static_assert(AreaNeeded <= 0x330000, "the overlays must fit the VIC output buffer past the self-tests' 64 KB");

        constexpr u64 OdBufBytes = UINT64_C(1280) * 768 * 4;     /* one overlayDisp buffer */
        constexpr u64 OdRegionBytes = 2 * OdBufBytes;            /* 7680 KB */

        /* what the VIC side reads: bit 0 the buffer, bit 1 set once it holds pixels */
        constinit std::atomic<u32> g_bar_pub{0};
        constinit std::atomic<u64> g_bar_change_tick{0};
        constinit std::atomic<u32> g_tesla_pub{0};
        constexpr u64 BarHoldNs   = UINT64_C(2500000000);        /* shown this long after its last change */
        constexpr u64 TeslaHoldNs = UINT64_C(300000000);         /* shown while it presented this recently */

        alignas(os::ThreadStackAlignment) constinit u8 g_ovl_stack[8_KB];
        constinit os::ThreadType g_ovl_thread;

        /* un-premultiplying: c * 255 / a as (c * g_inv[a]) >> 16 */
        constinit u32 g_inv[256] = {};
        /* byte offsets inside a GOB: RGBA4444 pixel x (0-31) of row y, RGBA8 pixel x (0-15) */
        constinit u16 g_src_off[8][32] = {}, g_dst_off[8][16] = {};

        u32 GobOffset(u32 xb, u32 y) { return (xb / 32) * 256 + (y / 2) * 64 + ((xb % 32) / 16) * 32 + (y % 2) * 16 + (xb % 16); }
        u64 AgoNs(u64 tick) { return tick == 0 ? UINT64_MAX : armTicksToNs(armGetSystemTick() - tick); }
        u64 NowNs() { return armTicksToNs(armGetSystemTick()); }
        bool FileThere(const char *path) {
            fs::DirectoryEntryType t;
            return R_SUCCEEDED(fs::GetEntryType(std::addressof(t), path));
        }
        u64 PidOf(u64 program_id) {
            os::ProcessId pid{0};
            if (R_FAILED(::ams::pm::dmnt::GetProcessId(std::addressof(pid), ncm::ProgramId{program_id}))) { return 0; }
            return pid.value;
        }
        u32 Fnv(const u8 *p, size_t n) {
            u32 h = 2166136261u;
            for (size_t i = 0; i < n; i += 4) { h = (h ^ (p[i] | (p[i + 1] << 8) | (p[i + 2] << 16) | (static_cast<u32>(p[i + 3]) << 24))) * 16777619u; }
            return h;
        }

        /* an attached overlay process */
        struct Proc {
            svc::Handle h = svc::InvalidHandle;
            u64 base = 0;
            u64 retry_at = 0;
            const char *name = "";
        };
        constinit Proc g_od, g_tesla;

        void Detach(Proc &p, const char *why) {
            if (p.h != svc::InvalidHandle) {
                svc::CloseHandle(p.h);
                LogLine("overlays: %s detached (%s)", p.name, why);
            }
            p.h = svc::InvalidHandle;
            p.base = 0;
        }
        /* a debug event (a thread starting or ending) stops the whole process
         * until it is answered */
        void Pump(Proc &p) {
            if (p.h == svc::InvalidHandle) { return; }
            s32 idx = 0;
            if (R_SUCCEEDED(svc::WaitSynchronization(std::addressof(idx), std::addressof(p.h), 1, 0))) {
                u32 n = 0;
                static_cast<void>(DbgResume(p.h, std::addressof(n)));
            }
        }
        /* the device-shared region of exactly `size` bytes */
        u64 FindRegion(svc::Handle h, u64 size) {
            u64 addr = 0;
            for (u32 guard = 0; guard < 4096; ++guard) {
                svc::MemoryInfo mi = {};
                svc::PageInfo pi = {};
                if (!DbgQuery(h, std::addressof(mi), std::addressof(pi), addr) || mi.size == 0) { break; }
                const bool dev = (static_cast<u32>(mi.attribute) & svc::MemoryAttribute_DeviceShared) != 0 || mi.device_count != 0;
                if (dev && mi.size == size && (mi.permission & svc::MemoryPermission_Read) != 0) { return mi.base_address; }
                const u64 next = mi.base_address + mi.size;
                if (next <= addr) { break; }
                addr = next;
            }
            return 0;
        }
        bool Attach(Proc &p, u64 program, u64 region) {
            if (p.h != svc::InvalidHandle) { return true; }
            if (NowNs() < p.retry_at) { return false; }
            p.retry_at = NowNs() + UINT64_C(5000000000);
            const u64 pid = PidOf(program);
            if (pid == 0) { return false; }
            svc::Handle h = svc::InvalidHandle;
            if (const Result rc = svc::DebugActiveProcess(std::addressof(h), pid); R_FAILED(rc)) {
                LogLine("overlays: %s (pid %llu) - DebugActiveProcess rc=0x%x", p.name, static_cast<unsigned long long>(pid), rc.GetValue());
                return false;
            }
            u32 nev = 0;
            static_cast<void>(DbgResume(h, std::addressof(nev)));
            p.h = h;
            p.base = FindRegion(h, region);
            if (p.base == 0) {
                LogLine("overlays: %s (pid %llu) - no %llu KB region", p.name, static_cast<unsigned long long>(pid), static_cast<unsigned long long>(region / 1024));
                Detach(p, "no frame buffers");
                return false;
            }
            LogLine("overlays: %s attached (pid %llu), frame buffers at %010llx", p.name, static_cast<unsigned long long>(pid), static_cast<unsigned long long>(p.base));
            return true;
        }

        /* premultiplied RGBA8 -> straight, in place; true if any pixel shows */
        bool Unpremultiply(u8 *p, size_t n) {
            bool any = false;
            for (size_t i = 0; i < n; i += 4) {
                const u32 a = p[i + 3];
                if (a == 0) { p[i] = p[i + 1] = p[i + 2] = 0; continue; }
                any = true;
                if (a == 255) { continue; }
                for (u32 c = 0; c < 3; ++c) {
                    const u32 v = (p[i + c] * g_inv[a]) >> 16;
                    p[i + c] = static_cast<u8>(v > 255 ? 255 : v);
                }
            }
            return any;
        }

        /* one 128-row block row of Tesla: RGBA4444 (14 GOB columns, r in the
         * low nibble of the first byte ... a in the high nibble of the second,
         * premultiplied) -> straight RGBA8 (28 GOB columns) */
        void ConvertTeslaRow(const u8 *src, u8 *dst) {
            for (u32 gx = 0; gx < 14; ++gx) {
                for (u32 gy = 0; gy < 16; ++gy) {
                    const u8 *sg = src + gx * BlockBytes + gy * 512;
                    u8 *dg0 = dst + (2 * gx) * BlockBytes + gy * 512;
                    u8 *dg1 = dg0 + BlockBytes;
                    for (u32 y = 0; y < 8; ++y) {
                        for (u32 x = 0; x < 32; ++x) {
                            const u8 *s = sg + g_src_off[y][x];
                            const u32 a = (s[1] >> 4) * 17;
                            u8 *d = (x < 16 ? dg0 : dg1) + g_dst_off[y][x & 15];
                            if (a == 0) { d[0] = d[1] = d[2] = d[3] = 0; continue; }
                            const u32 r = (s[0] & 15) * 17, g = (s[0] >> 4) * 17, b = (s[1] & 15) * 17;
                            const u32 inv = g_inv[a];
                            const u32 rr = (r * inv) >> 16, gg = (g * inv) >> 16, bb = (b * inv) >> 16;
                            d[0] = static_cast<u8>(rr > 255 ? 255 : rr);
                            d[1] = static_cast<u8>(gg > 255 ? 255 : gg);
                            d[2] = static_cast<u8>(bb > 255 ? 255 : bb);
                            d[3] = static_cast<u8>(a);
                        }
                    }
                }
            }
        }

        /* counters for the minute's log line */
        constinit u32 g_bar_copies = 0, g_tesla_frames = 0;
        constinit u64 g_work_ns = 0;

        /* the volume bar: read one buffer's corner (they alternate); copy it
         * when it changed */
        constinit u32 g_bar_hash[2] = {}, g_bar_turn = 0;
        constinit bool g_bar_seen[2] = {};
        void StepBar() {
            if (g_od.h == svc::InvalidHandle) {
                if (!Attach(g_od, OverlayDispProgramId, OdRegionBytes)) { return; }
                g_bar_seen[0] = g_bar_seen[1] = false;
            }
            const u32 b = (g_bar_turn++) & 1;
            const u32 pub = g_bar_pub.load(std::memory_order_relaxed);
            const u32 di = (pub & 2) ? ((pub & 1) ^ 1) : 0;    /* never the buffer being shown */
            u8 *dst = g_area + OffBar[di];
            if (!DbgRead(g_od.h, dst, g_od.base + b * OdBufBytes, BarBytes)) { Detach(g_od, "read failed"); return; }
            const u32 h = Fnv(dst, BarBytes);
            if (!g_bar_seen[b]) { g_bar_seen[b] = true; g_bar_hash[b] = h; return; }   /* the first look is not a change */
            if (h == g_bar_hash[b]) { return; }
            g_bar_hash[b] = h;
            const bool any = Unpremultiply(dst, BarBytes);
            armDCacheFlush(dst, BarBytes);
            g_bar_change_tick.store(any ? armGetSystemTick() : 0, std::memory_order_relaxed);
            g_bar_pub.store(di | (any ? 2u : 0u), std::memory_order_release);
            ++g_bar_copies;
        }

        /* Tesla: while it presents, its last queued buffer 10 times a second */
        constinit u32 g_tesla_gen_seen = 0;
        constinit u64 g_tesla_next_ns = 0;
        void StepTesla() {
            const u64 age = AgoNs(g_tesla_present_tick.load(std::memory_order_relaxed));
            if (age > UINT64_C(2000000000)) {
                if (g_tesla.h != svc::InvalidHandle) { Detach(g_tesla, "it stopped presenting"); }
                g_tesla_pub.store(0, std::memory_order_relaxed);
                return;
            }
            if (age > TeslaHoldNs) { return; }
            const u64 now = NowNs();
            if (now < g_tesla_next_ns) { return; }
            g_tesla_next_ns = now + UINT64_C(100000000);
            const u32 gen = g_tesla_buffers_gen.load(std::memory_order_relaxed);
            if (g_tesla.h != svc::InvalidHandle && gen != g_tesla_gen_seen) {
                /* new buffers: find them again */
                g_tesla.base = FindRegion(g_tesla.h, TeslaRegion);
                if (g_tesla.base == 0) { Detach(g_tesla, "its buffers moved out of reach"); }
            }
            g_tesla_gen_seen = gen;
            if (g_tesla.h == svc::InvalidHandle && !Attach(g_tesla, TeslaProgramId, TeslaRegion)) { return; }
            const u32 slot = g_tesla_slot.load(std::memory_order_relaxed) & 1;
            const u32 pub = g_tesla_pub.load(std::memory_order_relaxed);
            const u32 di = (pub & 2) ? ((pub & 1) ^ 1) : 0;
            u8 *dst = g_area + OffTesla[di];
            u8 *scratch = g_area + OffScratch;
            for (u32 r = 0; r < 6; ++r) {
                if (!DbgRead(g_tesla.h, scratch, g_tesla.base + slot * TeslaSrcBuf + r * TeslaSrcRow, TeslaSrcRow)) { Detach(g_tesla, "read failed"); return; }
                ConvertTeslaRow(scratch, dst + r * TeslaDstRow);
                Pump(g_tesla);
            }
            armDCacheFlush(dst, TeslaDstBuf);
            g_tesla_pub.store(di | 2u, std::memory_order_release);
            ++g_tesla_frames;
        }

        void OverlayThread(void *) {
            for (u32 a = 1; a < 256; ++a) { g_inv[a] = (255u << 16) / a; }
            for (u32 y = 0; y < 8; ++y) {
                for (u32 x = 0; x < 32; ++x) { g_src_off[y][x] = static_cast<u16>(GobOffset(x * 2, y)); }
                for (u32 x = 0; x < 16; ++x) { g_dst_off[y][x] = static_cast<u16>(GobOffset(x * 4, y)); }
            }
            g_od.name = "overlayDisp";
            g_tesla.name = "Tesla";
            u64 minute = NowNs() + UINT64_C(60000000000);
            for (u32 tick = 0;; ++tick) {
                os::SleepThread(TimeSpan::FromMilliSeconds(10));
                /* answer debug events every 10 ms */
                Pump(g_od);
                Pump(g_tesla);
                if ((tick % 10) != 0) { continue; }
                {
                    std::scoped_lock lk(g_area_lock);
                    if (!g_area_up.load(std::memory_order_relaxed) || !g_calibrated.load(std::memory_order_relaxed)) {
                        if (g_od.h != svc::InvalidHandle) { Detach(g_od, "not streaming"); }
                        if (g_tesla.h != svc::InvalidHandle) { Detach(g_tesla, "not streaming"); }
                        continue;
                    }
                    const u64 t0 = NowNs();
                    StepBar();
                    StepTesla();
                    g_work_ns += NowNs() - t0;
                }
                if (NowNs() >= minute) {
                    minute = NowNs() + UINT64_C(60000000000);
                    if (g_bar_copies != 0 || g_tesla_frames != 0) {
                        LogLine("overlays: last minute - volume bar copied %u times, Tesla %u frames, %llu ms of work",
                                g_bar_copies, g_tesla_frames, static_cast<unsigned long long>(g_work_ns / 1000000));
                    }
                    g_bar_copies = g_tesla_frames = 0;
                    g_work_ns = 0;
                }
            }
        }

    }

    void OverlayArm() {
        g_armed = FileThere(OverlaysFile);
        const bool probe = SFT_OVL_PROBE && FileThere(ProbeFile);
        if (g_armed || probe) { g_ovl_vi_mitm.store(true, std::memory_order_relaxed); }
        if (g_armed) {
            LogLine("overlays: on (overlays.txt) - the volume bar and Tesla/Ultrahand in the stream; Tesla's new vi:m sessions are wrapped (never captured)");
            SFT_ABORT_UNLESS(os::CreateThread(std::addressof(g_ovl_thread), OverlayThread, nullptr, g_ovl_stack, sizeof(g_ovl_stack), os::GetThreadPriority(os::GetCurrentThread())));
            os::SetThreadNamePointer(std::addressof(g_ovl_thread), "applet-mitm.Overlays");
            os::StartThread(std::addressof(g_ovl_thread));
            NoteThread(std::addressof(g_ovl_thread));
        } else if (probe) {
            LogLine("ovl2: Tesla's new vi:m sessions will be wrapped (counted, never captured)");
        }
    }

    bool OverlaysArmed() { return g_armed; }

    void OverlaySetCalibrated(bool ok) {
        g_calibrated.store(ok, std::memory_order_relaxed);
        if (!ok) { LogLine("overlays: OFF for this boot - the blend check failed"); }
    }

    void OverlayAreaUp(u8 *cpu, u32 dev, size_t size) {
        if (!g_armed || cpu == nullptr || dev == 0 || size < AreaNeeded) { return; }
        std::scoped_lock lk(g_area_lock);
        g_area = cpu;
        g_area_dev.store(dev, std::memory_order_relaxed);
        g_bar_pub.store(0, std::memory_order_relaxed);
        g_tesla_pub.store(0, std::memory_order_relaxed);
        g_bar_change_tick.store(0, std::memory_order_relaxed);
        g_area_up.store(true, std::memory_order_release);
    }

    void OverlayAreaDown() {
        std::scoped_lock lk(g_area_lock);
        g_area_up.store(false, std::memory_order_relaxed);
        g_area = nullptr;
        g_area_dev.store(0, std::memory_order_relaxed);
        g_bar_pub.store(0, std::memory_order_relaxed);
        g_tesla_pub.store(0, std::memory_order_relaxed);
    }

    u32 OverlayPlanes(OverlayPlane *out, u32 max) {
        if (!g_calibrated.load(std::memory_order_relaxed) || !g_area_up.load(std::memory_order_acquire)) { return 0; }
        const u32 dev = g_area_dev.load(std::memory_order_relaxed);
        if (dev == 0) { return 0; }
        u32 n = 0;
        const u32 tp = g_tesla_pub.load(std::memory_order_acquire);
        if (n < max && (tp & 2) && AgoNs(g_tesla_present_tick.load(std::memory_order_relaxed)) < TeslaHoldNs) {
            out[n++] = { dev + static_cast<u32>(OffTesla[tp & 1]), TeslaW, TeslaH, vic::PIXFMT_A8R8G8B8, 0, 0 };
            static bool said = false;
            if (!said) { said = true; LogLine("overlays: Tesla is in the stream (first frame)"); }
        }
        const u32 bp = g_bar_pub.load(std::memory_order_acquire);
        if (n < max && (bp & 2) && AgoNs(g_bar_change_tick.load(std::memory_order_relaxed)) < BarHoldNs) {
            out[n++] = { dev + static_cast<u32>(OffBar[bp & 1]), BarW, BarH, vic::PIXFMT_A8R8G8B8, 0, 0 };
            static bool said = false;
            if (!said) { said = true; LogLine("overlays: the volume bar is in the stream (first frame)"); }
        }
        return n;
    }

    void OvlNoteTeslaTransaction(u32 code, const void *in, size_t in_size) {
        g_tesla_txns.fetch_add(1, std::memory_order_relaxed);
        if (code == 7) {
            const s32 slot = ParseParcelSlot(static_cast<const u8 *>(in), in_size);
            if (slot >= 0) { g_tesla_slot.store(static_cast<u32>(slot), std::memory_order_relaxed); }
            g_tesla_present_tick.store(armGetSystemTick(), std::memory_order_relaxed);
            g_tesla_presents.fetch_add(1, std::memory_order_relaxed);
        } else if (code == 14) {
            g_tesla_buffers_gen.fetch_add(1, std::memory_order_relaxed);
        }
        if (code < 16 && g_tesla_logged[code] < 3) {
            ++g_tesla_logged[code];
            LogLine("overlays: Tesla binder code %u, %zu B in", code, in_size);
        }
    }

}
