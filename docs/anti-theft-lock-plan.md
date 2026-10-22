# Anti-theft & EMI lock plan

<sub>[← README](../README.md) · [Dev](DEV.md) · [Features](FEATURES.md) · [Agent architecture](agent-architecture.md) · [ADRs](adr)</sub>

Purpose: make it uneconomical for a borrower/thief to reclaim a financed device, without
pretending that an MDM app can do things the Android security model forbids.

> **Supersedes `docs/plan`.** That file is informal Hinglish notes and contains several claims
> that are wrong against Samsung's current documentation. See [§8](#8-corrections-to-docsplan)
> before quoting it to anyone.

> ## ⚠️ Amendment (2026-10-16) — two corrections that change the plan
>
> **1. Google's Android Management API must not be used for EMI locking.** An earlier revision of
> this document recommended `setFactoryResetProtectionPolicy` / Android Enterprise as the
> brand-agnostic lock. That was **wrong advice**. The
> [AMAPI Permissible Usage policy](https://developers.google.com/android/management/permissible-usage)
> lists as *not allowed*: "Device financing solutions … that manage, restrict, or control device
> usage or functionality based on payment status (e.g., non-payment of Equated Monthly Installments
> (EMI))". Google's sanctioned route is the separate **Android Device Lock / Device Lock Controller
> (DLC)** program, which needs approval plus the privileged `MANAGE_DEVICE_LOCK_STATE` permission
> and the `FEATURE_DEVICE_LOCK` system feature. Phase 3 below is rewritten accordingly, and
> [ADR 0010](adr/0010-financing-lock-provider-strategy.md) is the governing record.
>
> **2. `DevicePolicyManager.setMasterClearEnabled` does not exist.** It is quoted by `docs/plan`
> and by older advice here as the call that blocks a Settings factory reset. Verified against
> `android-35/android.jar`: no such member. The real lever is the Device-Owner user restriction
> `UserManager.DISALLOW_FACTORY_RESET` (`"no_factory_reset"`, API 27+), which is what Phase 1 now
> implements.

---

## 1. Threat model

Assume the device is in the hands of someone who does not want to keep paying, or who has stolen
it, and who has **unlimited physical access** and **no technical skill ceiling**. Goal is not
impossibility — it is raising cost above the value of the device, and making the lock *detectable*.

Two distinct properties, often confused:

| Property | Meaning | Who can provide it |
|---|---|---|
| **Prevention** | The user cannot trigger a reset at all | App can only cover the Settings path |
| **Persistence** | A reset that *did* happen does not yield a usable device | OEM/secure-element or Google account lock — **never an app** |

An EMI/finance product needs **Persistence**. Prevention alone is deterrence for the honest-slacker,
not for a defaulter.

---

## 2. Attack coverage

Current state vs. what each phase buys. "In-project" = achievable with code in this repo.

| Attack | Today | Phase 1 | Phase 3 (Google DLC) | Phase 4 (Knox Guard) |
|---|---|---|---|---|
| Settings → Factory reset | ❌ open | ✅ blocked (all brands) | — | — |
| Uninstall the agent | ✅ blocked (Device Owner) | ✅ | ✅ | ✅ |
| Remove device admin from Settings | ✅ blocked (DO admin, UI cannot remove) | ✅ | ✅ | ✅ |
| Data readable after reset attempt | ❌ no passcode enforced | — | ✅ tied to passcode | ✅ |
| **Recovery-mode wipe** (Vol-Up + Power) | ❌ succeeds, device clean | ❌ not blockable | ✅ device relocks at setup (GMS) | ✅ **survives** — server-side lock |
| **Firmware reflash** (Odin / MiFlash / SP Flash) | ❌ succeeds | ❌ | ⚠️ partially (OEM-dependent) | ✅ Knox Vault, strongest |
| `adb remove-active-admin` | ❌ | ❌ (AOSP cannot disable ADB) | ⚠️ needs unlock + Dev Options first | ✅ Knox `setAdbEnabled` (KPE) |

**Structurally impossible for any MDM on any brand:** blocking recovery wipe and reflash. SOTI,
Scalefusion, Microsoft Intune and Headwind all share this limit. Do not write marketing copy that
claims otherwise.

---

## 3. Governing constraints (verified in this repo)

| Constraint | Value / location | Consequence |
|---|---|---|
| Agent SDK floors | `compileSdk = 35`, `minSdk = 24`, `targetSdk = 35` — `agent-android/app/build.gradle.kts` | API 27 and API 31 paths both compile; every call needs a runtime `SDK_INT` gate |
| Policy extension seam | `TogglePolicy` + `CapabilityRegistry.togglePolicies()` + `PolicyApplyHandler` (data-driven, no `when`) | New policies = new strategy + one registry line; handler/server unchanged |
| Identity model | [ADR 0003](adr/0003-server-issued-device-ids.md) — server-issued opaque ID, never IMEI as identity | IMEI cannot be auto-collected; identity is telemetry-only |
| Distribution | [ADR 0002](adr/0002-self-signed-agent-distribution.md) — self-signed APK, fixed signing cert | Required for Knox Path B (cert registration) — see keystore custody TODO below |
| Dev emulator | `docs/DEV.md` mandates an **AOSP** image (no Google APIs) for Device Owner | **FRP and Knox are untestable locally.** Physical GMS + physical Samsung devices are mandatory |
| `passwordMode` | exists on `configurations`, travels in `SyncResponse` — **no agent consumer** | Dead field inherited from Headwind; Phase 2 activates it |
| Serial telemetry | `IdentityCollector` reports `Build.getSerial()` | Serial reaches the server today; **IMEI does not** (blocked Android 10+) |
| OEM seam | `oem.knox` token flattened server-side (`AgentCapabilityTokens`), `KnoxAdapter` is a parked stub (`isAvailable() = false`) | Capability channel warm; zero Knox code exists |
| Keystore custody | open `TODO(keystore custody)` in `app/build.gradle.kts` | Blocking for Path B: re-signing a deployed DPC requires factory-resetting the whole fleet |

---

## 4. Open decisions — blocking

These must be answered by you, not by code. Each one gates a phase.

**D1 — Which financing-lock provider do we apply to? (gates Phase 3)**
~~Add a company Google account and use FRP~~ — **withdrawn**: payment-status locking is prohibited
on the Android Management API (see the Amendment above). The Google route is now **Android Device
Lock / Device Lock Controller**, which requires being approved as a device-lock solution and a SKU
that exposes `FEATURE_DEVICE_LOCK`.
Decision needed: *apply for DLC*, *rely on Samsung Knox Guard only*, or *use an authorized
third-party aggregator (route C)* — and per-brand coverage claims only after hardware PoC.*

**D2 — Samsung account status? (gates Phase 4 vs Phase 6)**
Knox Guard requires an approved Samsung business account; Knox SDK (Path B) additionally requires
Samsung Authorized MDM Vendor status + a KPE license, and that process takes weeks.
Decision needed: *which do we own today?*

**D3 — Fleet composition. (gates the whole strategy)**
If financed hardware is Samsung-and-eligible → Phase 4 is the primary lock and Phase 3 is optional.
If financed hardware is mixed/cheap non-Samsung → Phase 3 (Google DLC) is the only cross-brand lock
available, and only on SKUs that actually ship `FEATURE_DEVICE_LOCK`.

**D4 — Reset policy: is a defaulter's recovery wipe an event we log, or an event we alarm on?**
Feeds Phase 5 (tamper detection via the factory-reset-stable enrollment ID).

---

## 5. Phase plan

Ordered so that phases with no external dependency ship first.

---

### Phase 0 — Decisions and paperwork
**Owner: you. No code. Start immediately, in parallel with Phase 1.**

1. Answer D1–D4.
2. Open/confirm the Samsung business account; ask Samsung specifically about **Knox Guard**
   eligibility and the **device identifier it needs** (IMEI vs serial, varies by SKU).
3. If Path B is later plausible: start the Authorized MDM Vendor application early, and resolve
   the `TODO(keystore custody)` — the release signing cert must be stable and registered.

**Done criteria:** D1–D4 written down; Samsung account state known; keystore custodian named.

---

### Phase 1 — Reset-path hardening (all brands, pure code, no license)
**Status: ✅ implemented 2026-10-16** (`com.mdmesh.policy.reset`).

**Goal:** no Settings-path factory reset on any enrolled device, armed at provisioning rather than
waiting for a command.

New files, mirroring the existing `camera/` package shape:

| File | Content |
|---|---|
| `agent-android/policy/src/main/kotlin/com/mdmesh/policy/reset/FactoryResetPolicy.kt` | `interface FactoryResetPolicy : TogglePolicy`; `CAPABILITY_KEY = "factoryReset"` |
| `.../reset/FactoryResetRestrictionPolicy.kt` | strategy: `setEnabled(false)` → `addUserRestriction(admin, DISALLOW_FACTORY_RESET)`; `setEnabled(true)` clears it. `isSupported()` = `isDeviceOwnerApp && SDK_INT >= 27`. Wrapped in `runCatching` → `PolicyOutcome`, never throws. **Not** `setMasterClearEnabled` — that API does not exist |
| `.../reset/FactoryResetPolicyFactory.kt` | probe; returns `null` when unsupported so the key is never advertised |

Edits:

| File | Change |
|---|---|
| `policy/CapabilityRegistry.kt` | one line in `togglePolicies()`; `supportedPolicyKeys()` picks it up automatically |
| `policy/UserRestrictions.kt` | `const val DISALLOW_FACTORY_RESET = "no_factory_reset"`; map `"factoryReset" -> setOf(DISALLOW_FACTORY_RESET)`; `minSdkForKey("factoryReset") -> 27` |
| `policy/src/test/kotlin/com/mdmesh/policy/UserRestrictionsTest.kt` | assert the 27 floor, same shape as the `bluetooth`/26 case |
| `app/.../admin/AdminReceiver.kt` | **apply at provisioning** in `onEnabled` **and** `onProfileProvisioningComplete`, alongside the existing `setStableOrganizationId` / `protectAgentProcess` baseline calls |
| `proto/registry.md` | new `factoryReset` row in the § policy table |

**Why baseline in `AdminReceiver` and not command-only:** between provisioning and the first
successful check-in the device is unprotected, and a stolen or powered-off device may never
receive the command at all. Protection must not depend on the network.

Zero changes to `PolicyApplyHandler`, the capability matrix, or the server — routing is data-driven.

**Verify:** `mvn -pl common test` unaffected; `./gradlew :policy:testDebugUnitTest` for
`UserRestrictionsTest`; `./gradlew :app:assembleDebug`; on an AOSP emulator, provision, then confirm
Settings → System → Reset shows the reset entry blocked with an error dialog, and that the
`factoryReset` key appears in the reported capability matrix.

**Done criteria:** reset from Settings blocked on a freshly provisioned emulator device with no
`policy.apply` command ever sent.

---

### Phase 2 — Passcode enforcement (activates the dead `passwordMode`)
**Goal:** data-at-rest is protected, and a reset (however achieved) leaves the device needing a
credential. Prerequisite for Phase 3 to mean anything.

1. Consume `passwordMode` from the sync response in `:core` (it already arrives in `SyncResponse`).
2. New strategy in `:policy` applying, API-gated:
   - API 29+ → `dpm.setPasswordRequirements(admin, PasswordRequirements)` (length + complexity)
   - API 24–28 → `dpm.setPasswordQuality(...)` + `dpm.setPasswordMinimumLength(...)` (deprecated, still required for minSdk 24 floor)
3. Enforce **after** the keyguard is set; do not disable the keyguard (`setKeyguardDisabled`) on
   financed devices — the existing `PolicyManager.setKeyguardDisabled` is a kiosk convenience that
   must be explicitly forbidden in this profile.
4. Reuse `DevicePasscodeResetHandler` (`resetPasswordWithToken`, API 26+) for defaulter recovery —
   it requires a reset-password token to have been provisioned; verify the enrollment path sets it.

**Server/console:** `web/src/data/configFields.ts` currently describes `passwordMode` as an
"advanced; JSON string" free-text field with no validation. Replace with a structured control
(min length, type) so the value the agent parses is always well-formed.

**Verify:** emulator, confirm a weak PIN is rejected and `dpm.getPasswordQuality` reflects the policy.

---

### Phase 3 — Google Android Device Lock / DLC (cross-brand) — *gated on D1*
**Goal:** a lock that survives a wipe, on non-Samsung hardware, **through a Google-approved
program** rather than a prohibited use of AMAPI.

> Earlier versions of this document told you to build this on
> `dpm.setFactoryResetProtectionPolicy(...)` as an ordinary Device-Owner feature. **Do not do that
> for payment-status locking.** AMAPI/Android Enterprise usage for EMI locking is expressly not
> allowed (see the Amendment). Use the sanctioned Device Lock route instead.

1. Apply to be an approved **device-lock solution** so the lender can act as a **Device Lock
   Controller (DLC)**. This is an account/partnership gate, not a code gate.
2. Confirm the target SKUs expose `PackageManager.FEATURE_DEVICE_LOCK` — the platform surface is
   `android.devicelock.DeviceLockManager` (API 34), which requires it.
3. `MANAGE_DEVICE_LOCK_STATE` is a **privileged** permission. The lock kiosk app is installed by the
   device-lock solution; the financing role is `DEVICE_LOCK_ROLE_FINANCING`. **A normal APK cannot
   simply request this** — so "the API compiled" is never evidence that the capability works.
4. Integration lands behind the `DeviceEnforcementProvider` adapter from
   [ADR 0010](adr/0010-financing-lock-provider-strategy.md), **not** in the existing `TogglePolicy`
   registry, because lock/release is provider-confirmed state, not a device toggle.
5. This agent's own identity model is unchanged: the opaque server-issued id stays the key, and the
   DLC registration reference is stored as a mapping.

**Verify:** impossible on the AOSP dev emulator (no GMS, no device-lock service). Requires an
approved provider environment **and** physical eligible hardware: enroll → lock → recovery-wipe →
confirm still locked → release → confirm usable.

---

### Phase 4 — Knox Guard for the Samsung fleet (no agent code) — *gated on D2*
**Goal:** actual persistence through recovery wipe *and* reflash. This is the real lock.

Portal-side. The app is not involved:

1. Register eligible devices on the Samsung portal (approved account).
2. Asset intake captures the identifier Samsung requires — **IMEI via `*#06#`, done by a human at
   intake**, because the agent cannot read IMEI on Android 10+ (ADR 0003 stands; do not add
   `READ_PHONE_STATE`).
3. On loss/default: mark **Report Lost/Stolen** on the portal → device locks at setup after any wipe.
4. Confirm eligibility per SKU: current Samsung documentation describes Knox Guard for supported
   Samsung devices on **Android 11 or newer**. Blanket "all Galaxy since 2016" is false.
5. Knox Guard exposes a REST API **behind the approved account** — it is *not* "no public API", and
   it is *not* simply free. Automating report-stolen later is possible without touching the agent.

**In-project work for Phase 4 (small):** device inventory must retain the identifier. Serial already
arrives via `IdentityCollector` telemetry; surface serial + a manually-entered IMEI field on the
server device row and link out to the Knox portal from device detail in `web/`.

**Verify:** one physical eligible Samsung device, registered, then wiped via recovery — it must stop
at setup. Test before committing to the fleet.

---

### Phase 5 — Tamper detection and console surfacing
**Goal:** when prevention fails, we *know*, and we have evidence.

Leverage what already exists: `AdminReceiver.setStableOrganizationId` makes
`getEnrollmentSpecificId` **stable across a factory reset** (API 31+). So:

1. Persist the enrollment-specific id per device row on the server.
2. On enrollment/check-in, if a known id returns from a device record that shows a wipe → emit a
   "device was factory reset" event with a timestamp.
3. Expose that event in the console device timeline; make it a trigger for the EMI default workflow.

This is identification, **not** locking — but it converts an undetectable theft of control into a
logged, actionable event, on every brand.

---

### Phase 6 — Knox SDK agent flavor (Path B) — *deferred until D2 resolves to approved + licensed*
Do not start before Samsung approval. `KnoxAdapter` is a 22-line parked stub; this is greenfield,
not a revive.

When licensed: `knox` Gradle product flavor; base APK stays Knox-free (Play Protect posture,
ADR 0005); `KnoxAdapter.isAvailable()` becomes a reflective runtime probe; `capability()` returns
`knox = true` — the `oem.knox` token plumbing already exists server-side and is unit-tested, so the
protocol needs no change. Unlocked extras: disabling ADB, Knox-only wipe restrictions.
Dependency: fixed, custody-controlled release signing cert registered with Samsung.

---

## 6. Verification matrix

| Check | Environment | Works? |
|---|---|---|
| `UserRestrictions` mapping + minSdk floors | JVM unit test (`:policy:testDebugUnitTest`) | ✅ CI |
| `DISALLOW_FACTORY_RESET` blocks Settings reset | AOSP emulator (DO via `dpm set-device-owner`) | ✅ local |
| Passcode policy enforced | AOSP emulator | ✅ local |
| `scripts/agent-v1-e2e.sh` capability gating | running server | ✅ local |
| FRP after recovery wipe | **physical GMS device** | ❌ never on emulator |
| Google DLC lock/release | **approved DLC + eligible hardware** | ❌ needs provider approval |
| Knox Guard after recovery wipe / reflash | **physical eligible Samsung** | ❌ needs Samsung account |
| ADB hard-off | **Samsung + KPE** only | ❌ not possible on AOSP |

---

## 7. Sequencing

```
Phase 0 (paperwork)  ─────────────────────────┐
Phase 1 (reset-path, code, all brands)  ~1 day ├──► ship to whole fleet
Phase 2 (passcode, code, all brands)    ~1 day ┘
Phase 5 (reset detection)               ~1–2 days   ← no external dependency
Phase 3 (Google DLC)  ← needs D1 approval + eligible physical hardware
Phase 4 (Knox Guard)  ← needs D2 approval; ~90% inventory data already exists
Phase 6 (Knox SDK)    ← needs KPE license + keystore custody resolved
```

Ship 1, 2 and 5 regardless of D1–D4: they protect every device already enrolled and need nothing
from Samsung or Google.

---

## 8. Corrections to `docs/plan`

`docs/plan` is still in the repo and still wrong. Do not build from it. Superseded as follows:

| `docs/plan` claim | Reality |
|---|---|
| L10 — Knox Portal / Guard account is **free** | Requires a Samsung business account with approval; Knox SDK needs a paid/licensed KPE agreement |
| L16 — "portal ka **koi public API nahi** hai" | **Wrong.** Knox Guard has a REST API, gated on the approved account |
| L11 — "**2016+** ke almost saare Galaxy support karte hain" | Current docs describe supported Samsung devices on **Android 11+**; per-SKU confirmation required |
| L35 — `setMasterClearEnabled(false)` blocks factory reset, "sab devices par" | **That API does not exist** in the Android SDK (verified against `android-35/android.jar`) — code calling it will not compile. The real lever is `DISALLOW_FACTORY_RESET`, and even that blocks **only the Settings path**, needs Device Owner + API 27+, and does nothing against recovery or reflash |
| L30 — mentions Knox Guard alongside "FRP hi option hai" | FRP (`setFactoryResetProtectionPolicy`, API 31) is a platform capability, but using it for **payment-status locking is prohibited** under Google's AMAPI permissible-usage policy. The sanctioned cross-brand route is **Android Device Lock / Device Lock Controller** — see ADR 0010 |
| L14 — "Serial already report hota hai" | **Correct** — `IdentityCollector` reports `Build.getSerial()`; confirmed, and Phase 4 builds on it |

**Self-correction — an error in the first revision of this very document:** it recommended
`setFactoryResetProtectionPolicy` (API 31) as a brand-agnostic financing lock. That is prohibited
usage under Google's AMAPI permissible-usage policy, which names EMI explicitly. Phase 3 is now the
approved **Android Device Lock / DLC** route. Anyone reading an older copy of this file should treat
that recommendation as withdrawn.

Recommend: delete `docs/plan` or replace its body with a one-line pointer to this document.

---

## 9. Explicitly out of scope (never achievable by this app)

- Blocking a recovery-mode wipe by hardware keys.
- Blocking a full firmware reinstall.
- Guaranteeing an app-level lock that outlives the OS install on non-Samsung, non-GMS hardware.
- Reading IMEI from the agent (ADR 0003, Android 10+).

If the product must promise "cannot be reset", the only honest answer is **Samsung Knox Guard on
eligible Samsung hardware** — a procurement decision, not a code decision.
