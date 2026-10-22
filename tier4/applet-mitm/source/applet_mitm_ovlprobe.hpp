/*
 * v0.7.14 overlay probe (applet_mitm_ovlprobe.cpp): research only. Test builds
 * set SFT_OVL_PROBE=1; then sdmc:/config/switch-frame-tap/probe-ovl.txt turns
 * it on (and probe-blend.txt the VIC blend card, applet_mitm_nv.cpp).
 */
#pragma once
#include <stratosphere.hpp>
#include <atomic>

#ifndef SFT_OVL_PROBE
#define SFT_OVL_PROBE 1
#endif

namespace ams::mitm::applet {

    void OvlProbeStart();

    /* ovl2: the Tesla / Ultrahand overlay loader (nx-ovlloader). Its first
     * vi:m session opens at boot, before this module; each overlay it loads
     * opens a new one. With the probe on, vi:m wraps those (ShouldMitm) and
     * the binder transactions are counted here - never the capture's. */
    constexpr u64 TeslaProgramId = UINT64_C(0x420000000007E51A);
    extern std::atomic<bool> g_ovl_vi_mitm;
    /* at startup, before vi:m is registered: probe-ovl.txt -> g_ovl_vi_mitm */
    void OvlProbeArm();
    void OvlNoteTeslaTransaction(u32 code, const void *in, size_t in_size);

}
