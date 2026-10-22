# Current architecture audit — device-lock readiness

<sub>[← Roadmap](../anti-theft-lock-plan.md) · [ADR 0010](../adr/0010-financing-lock-provider-strategy.md) · [Dev](../DEV.md)</sub>

Roadmap **Phase 1** deliverable: an honest inventory of what AndroidLock already does, so nothing
is redesigned that doesn't need redesigning. Every claim below was read out of the repo, not assumed.

Audit date: 2026-10-16.

---

## 1. Requested audit table, filled in

| Area | Finding | Evidence |
|---|---|---|
| **Android configuration** | `compileSdk = 35`, `minSdk = 24`, `targetSdk = 35`, Java/Kotlin target 17, core-library desugaring enabled for the API-24 floor | `agent-android/app/build.gradle.kts`; mirrored in `:policy`, `:oem`, `:kiosk`, `:remote`, `:core` module `build.gradle.kts` files |
| **Enrollment** | **Device Owner** (not device admin, not a normal app). Dev path: `adb shell dpm set-device-owner com.mdmesh.agent/.AdminReceiver`. Production path: QR provisioning bundle carrying `com.mdmesh.ENROLL_TOKEN` + `com.mdmesh.SERVER_URL`, handled in `onProfileProvisioningComplete`. AOSP-only emulator is *required* for dev because Google APIs images add a Google account and block DO | `docs/DEV.md`; `app/.../admin/AdminReceiver.kt` (`EXTRA_ENROLL_TOKEN`, `EXTRA_SERVER_URL`) |
| **Reset deterrence** | **Was absent. Now implemented** — `DISALLOW_FACTORY_RESET` user restriction, Device-Owner + API 27 gated, armed at provisioning (`onEnabled` and `onProfileProvisioningComplete`), not command-driven. `setMasterClearEnabled`, cited by `docs/plan`, **does not exist in the SDK** and cannot be implemented | `policy/.../reset/`, `policy/UserRestrictions.kt`, `app/.../AdminReceiver.kt`; verified against `android-35/android.jar` via `javap` |
| **Device identity** | Server-issued **opaque device id** + per-device secret, persisted in DataStore (`mdm_identity` / keys `server_device_id`, `device_secret`). Never IMEI/IMSI/serial — that is a deliberate, recorded decision, and the upstream Headwind design that relied on those IDs repeatedly broke | [ADR 0003](../adr/0003-server-issued-device-ids.md); `core/.../store/DeviceIdStore.kt` |
| **Reset detection** | **Partially present, not finished.** `setOrganizationId("mdmesh-fleet")` makes `getEnrollmentSpecificId` **stable across a factory reset** (API 31+), and `HardwareIdCollector` feeds it into enrollment — so the raw material exists to recognise "this hardware was wiped and re-enrolled". **No server-side event, timeline entry, or alert is emitted today.** | `app/.../AdminReceiver.kt` (`setStableOrganizationId`), `core/.../device/HardwareIdCollector.kt` |
| **Backend** | Java/Tomcat REST under `/rest`. Agent surface: `/rest/public/agent/v1/enroll`, `/checkin`, command queue; auth by per-device secret; capability tokens flattened server-side (`AgentCapabilityTokens`, incl. `oem.knox`). Postgres + Liquibase changelog. Multi-tenancy and a user/auth model already exist. **No financing, loan, lock-state, or provider domain exists at all.** | `server/.../rest/resource/*`, `common/.../util/AgentCapabilityTokens.java`, `common/.../domain/Configuration.java`, `server/src/main/resources/liquibase/db.changelog.xml` |
| **Signing / secrets custody** | Release keystore read from env (`MDM_RELEASE_STORE_FILE` etc.); nothing committed. Signing cert is load-bearing: the DO binding is tied to it, so re-signing forces a factory reset of the whole fleet. **Custody is an explicit open `TODO` — no named owner, no escrow.** Server URL is currently **baked** into both build types (`http://164.68.112.96/`), overridden at runtime by the QR `SERVER_URL` extra | `agent-android/app/build.gradle.kts` (`TODO(keystore custody)`, `MDM_BASE_URL`), `docs/adr/0002-self-signed-agent-distribution.md` |

---

## 2. Classification of existing capability

The roadmap's three categories, applied to this codebase. **Front two columns are real; the third
does not exist here and cannot be built here.**

| Category | What AndroidLock has | Status |
|---|---|---|
| **Deterrence** (stop a casual Settings wipe) | `factoryReset` (`DISALLOW_FACTORY_RESET`), kiosk lock-task pinning, `DISALLOW_CREATE_WINDOWS` overlay block during kiosk, status-bar/keyguard lockdown, camera / Bluetooth / USB-storage / Wi-Fi restriction policies, USB file-transfer block | ✅ implemented; `factoryReset` added by this workstream |
| **Detection** (know what happened) | per-device secret + server-issued id, GPS/telemetry reporting, command history, WebSocket wake + poll check-in, boot persistence, `setUserControlDisabledPackages` (agent exempted from OEM force-stop / app-sleep), reset-stable enrollment-specific id | ⚠️ signals exist; **no reset/tamper event is produced from them yet** |
| **Enforcement** (lock survives a wipe) | — | ❌ **Not implementable in an app.** Requires Google Device Lock Controller or an OEM lock (Knox Guard). See [ADR 0010](../adr/0010-financing-lock-provider-strategy.md) |

Also **not** deterrence and often mistaken for it: `device.wipe` (`DeviceWipeHandler`,
`wipeDevice` on API 34+ / `wipeData` below). That is a *lender-initiated destructive action*. It
deletes the customer's data and deletes the agent along with it — it does **not** protect the
device from the borrower, and after it runs you have lost control permanently.

---

## 3. Gaps to close, by category

### 3.1 Deterrence — small, code-only
- **Passcode is not enforced.** `configurations.passwordMode` exists in the DB, is written by the
  server (`SyncResource`, `ConfigurationMapper.xml`) and ships in `SyncResponse` — and **the agent
  never reads it**. Dead field inherited from Headwind. Until fixed, a wiped-or-restarted device has
  no credential barrier and data-at-rest is not protected.
- Registry rows that are named but have **no strategy**, therefore never advertised: `gps`,
  `mobileData`, `kioskLockTask` (handled outside the toggle registry), `passwordComplexity`,
  `systemUpdatePolicy`, `factoryResetProtection`. Advertising comes solely from
  `CapabilityRegistry.supportedPolicyKeys()`, so an unregistered key is silently `unsupported` —
  safe by design, but it means those rows are not features yet.

### 3.2 Detection — small, mostly server-side
- Emit a **"device was factory reset" event** by comparing the reset-stable enrollment-specific id
  against the stored device record; surface it in the console timeline; make it a trigger for a
  delinquency workflow.
- Detect the agent being disabled/reinstalled, and (once Phase 3/4 land) detect removal of the
  provider account that carries the lock.

### 3.3 Enforcement — blocked on providers, not on code
- Route A (Google DLC) needs partner approval + SKUs exposing `FEATURE_DEVICE_LOCK`; `MANAGE_DEVICE_LOCK_STATE`
  is privileged and cannot be granted to this APK.
- Route B (Samsung Knox Guard) needs an approved Samsung account; per-model/region eligibility.
- **Nothing to write until one approved device completes enroll → lock → recovery-wipe → still
  locked → release.**

### 3.4 Financing domain — greenfield
Nothing exists for: loan/tenant mapping, lock-state machine, release queue, or an
`DeviceEnforcementProvider` adapter. The existing `plugins/audit` module is a reasonable base for
the append-only action log rather than starting fresh.

---

## 4. What must NOT be changed

Recorded explicitly because the roadmap asks for additive work:

1. **Identity stays server-issued and opaque.** A provider demanding an IMEI/serial mapping does not
   reopen ADR 0003. Store the provider reference *beside* the AndroidLock device id.
2. **The capability handshake and `/agent/v1` wire contract stay additive-only**
   ([ADR 0009](../adr/0009-agent-v1-contract-stability.md)) — provider fields optional and defaulted,
   never required, because field devices run old APKs and cannot be force-updated.
3. **Permission minimization holds** ([ADR 0005](../adr/0005-play-protect-permission-minimization.md)).
   Adding `READ_PHONE_STATE` to grab an IMEI is the wrong fix; it breaks the Play-Protect posture
   that the parked `KnoxAdapter` comment exists to protect.
4. **Base APK stays Knox/vendor-free.** Vendor capability goes behind a flavor + runtime probe, as
   `:oem`'s `OemAdapter` seam already prescribes.

---

## 5. Immediate follow-ups (ordered)

1. Wire `passwordMode` → passcode enforcement (API-gated `setPasswordRequirements` / legacy
   `setPasswordQuality` + `setPasswordMinimumLength`). Deterrence + a precondition for any lock tier.
2. Reset-detection event from the enrollment-specific id; console timeline entry.
3. Provider applications (roadmap Phase 0) — the long pole; start now, in parallel.
4. Only then: `DeviceEnforcementProvider` interface + financing/enforcement state machine + audit
   records, against a provider whose behaviour has been proven on real hardware.
