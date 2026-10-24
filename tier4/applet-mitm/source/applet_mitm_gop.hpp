/* fork (melhorias-switch): how many frames between keyframes this frame -
 * the stream loop's choice, kept apart so run_pc_tests.sh can check it.
 *
 *   network, to a viewer that asked for a keyframe once (it can ask again):
 *       60 wired / 240 Wi-Fi (v0.7.13, exp/net-ethernet)
 *   USB, live, not the webcam: usb_keyframe_interval (default 240, at most
 *       250) when it is LONGER than keyframe_interval - never shorter, and
 *       0 turns it off. A viewer that needs one asks (SFTK: a new decoder, a
 *       recording, v0.7.13+); one that (re)joins gets one from the >500 ms
 *       USB gap (M86b); a frame the console fails to encode forces one too.
 *   anything else: keyframe_interval as set (0: every frame an IDR) */
#pragma once
#include <cstdint>

namespace ams::mitm::applet::keyframe {

    constexpr uint32_t UsbGopMax = 250;

    constexpr uint32_t Choose(bool net, bool live, bool uvc, uint32_t gop, bool viewer_can_ask, bool wired, uint32_t usb_gop) {
        if (gop == 0) { return 0; }
        if (net) { return viewer_can_ask ? (wired ? 60u : 240u) : gop; }
        const uint32_t u = usb_gop > UsbGopMax ? UsbGopMax : usb_gop;
        return (live && !uvc && u > gop) ? u : gop;
    }

}
