# LANU QUALITY11 — evidence-bound status, 2026-10-29

Status: **11 categories NOT independently accepted >=80/100**. This is
a fixed baseline ledger from [issue #177](../issues/177) and is not a score
upgrade based solely on CI or GitHub release. No real device, signer private
key or legal data is included.

| Category | Last documented score /100 | Required acceptance beyond CI |
| --- | ---: | --- |
| E2EE / identity | 73 | reviewed forward secrecy and downgrade resistance, original-identity continuity |
| Media | 70 | two-phone protected transport, large ciphertext, quota, memory, denial cases |
| Voice calling | 75 | two-device bidirectional RTP, HFP/BLE, ICE and background wake |
| Video calling | 73 | two-OEM camera permission, switch, reconnect and UI lifecycle |
| AEC / NS | 68 | actual audio path and measured suppression, no claimed ANC without proof |
| Background / FCM | 66 | two real phones, permission denial, locked screen, Doze/DND |
| Room / local protection | 68 | SQLCipher isolated restore, WAL/parity, no destructive live cutover |
| Android compatibility | 67 | API23/29/34/36 and physical Samsung A55 + Huawei/Xiaomi |
| Performance / diagnostics | 75 | real ANR/thermal/memory measurements, privacy-safe consent |
| Accessibility | 74 | TalkBack/48dp/200% font, 2 navigation modes and real devices |
| KVKK / Store | 61 | live authorized account/related-data erasure, reachable HTTPS deletion request, legal review |

## Automated acceptance boundaries

1. Each candidate must run unit/Robolectric, Android14 Room, Lint and R8 on
   **the exact final PR HEAD**. A passing CI does not certify human/device work.
2. After a reviewed merge, the trusted main SHA must pass signed OTA emulator
   preservation and published APK/SHA256/certificate read-back. A queued build
   is not a release.
3. Physical testing must report API version, device family, app build,
   documented scenarios, and sanitized proof; avoid raw caller, message,
   camera or PIN secrets in issues and logs.
4. The owner retains original protected message identity, PIN preference,
   Room database and APK signing key. No automatic data erase, key reset
   or unsupported crypto protocol migration.
5. Every category requires separately dated independent evidence; previous
   baseline scores are not overwritten without it. Numeric >=80 claims
   remain UNVERIFIED until the issue reviewer approves each category.

## Changes delivered in this hardening PR

- #224: account deletion readiness now demands three distinct proofs:
  authorization of remote account erasure, relational purge, and externally
  reachable deletion request. Synthetic URLs and a working *local* delete
  button are explicitly insufficient; no production delete executed.
- #114: the current static ECDH admission path rejects substituting a
  mismatched peer public key for a trusted ID, while preserving the v1
  ciphertext wire format. This does NOT implement Double Ratchet.
- #100: private local crash-report reader rejects JSON scalar-type coercion
  and remains offline/local only.
- #83: offline signing backup verifies ownership and does NOT falsely reject
  legitimate distinct store/key passwords. Dummy-only isolated restores prove
  exact encrypted bytes are recovered, and symlink substitution is rejected.
  The owner's actual off-device signing challenge remains UNVERIFIED.
- #177: this evidence matrix clearly identifies the 11 categories and the
  missing real-world proof instead of inventing new 80+ scores.

## Exit criteria

#224, #114, #100, #83 and #177 stay open until their own acceptance
requirements pass. In particular legal approval, remote deletion tests,
real forward-secret cryptography, physical device testing and the owner's
off-device PKCS12 recovery cannot be supplied by CI alone. This document
does not authorize publication of a new APK or the Google Play listing.
