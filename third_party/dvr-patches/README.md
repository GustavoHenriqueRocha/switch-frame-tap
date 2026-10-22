# dvr-patches (bundled)

IPS patches for the console's `am` system module by **exelix11**, from
https://github.com/exelix11/dvr-patches (BSD 3-Clause, see `LICENSE`). They
make every game behave as if it allows video recording, so the console's own
recorder runs for games that turn it off (Super Smash Bros. Ultimate) and their
sound reaches Switch Frame Tap through grc:d.

- Version: release `fw-23.0.1` (tag commit `3aa2977c2b58c0e385830b710f0af3b5c0bae36f`),
  one `.ips` per `am` build id, firmware 11.0 to 23.0.1.
- The release zip ships them **off**, in `config/switch-frame-tap/dvr-patches/`,
  where Atmosphère does not look. "Sound in no-record games" in the manager app
  or the overlay copies them to `atmosphere/exefs_patches/switch-frame-tap-sound/`
  (on) or deletes that copy (off); a restart applies it. While on, the
  sysmodule refreshes that copy from the bundled files at boot, so an update
  of Switch Frame Tap brings new patches along.

**After a firmware update** that changes `am`: download the new `dvr-patches.zip`
from their releases, replace the `.ips` files here with the ones in its
`atmosphere/exefs_patches/am/`, update the version above, and release.
