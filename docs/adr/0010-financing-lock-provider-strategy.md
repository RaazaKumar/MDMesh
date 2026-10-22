# ADR 0010 — Financing-lock provider strategy (EMI device locker)

**Status:** Accepted (2026-10-16)

> Numbering note: the incoming roadmap asked for `0004-financing-lock-provider-strategy.md`.
> `0004` is already taken by [kotlinx.serialization](0004-kotlinx-serialization-protocol.md);
> ADR numbers in this repo are sequential, so this is **0010**.

## Context

The product intent is device financing enforcement: the lender supplies the handset, and if the
borrower stops paying, the lender locks it; on final payment it is released. Crucially, the lock must
survive the borrower wiping the device.

Two facts constrain any design:

1. **An app cannot survive its own host.** Anything the agent enforces lives inside the Android
   install. A recovery-console wipe (hardware keys) or a firmware reflash destroys it, and both
   happen outside the framework the agent can influence. This is not an implementation gap — it is
   the Android security model. Only an OEM/secure-element or account-level lock persists across it.
2. **Google prohibits doing this through the Android Management API.** The
   [AMAPI Permissible Usage policy](https://developers.google.com/android/management/permissible-usage)
   lists as *not allowed*:

   > "Device financing solutions — including those used in conjunction with hardware lease
   > arrangements or other B2C/retail scenarios — that manage, restrict, or control device usage or
   > functionality based on payment status (e.g., non-payment of Equated Monthly Installments (EMI))"

   The policy names EMI explicitly, and Google reserves the right to revoke API access for
   non-compliance. Building the lock on AMAPI/Android Enterprise would put the whole fleet at risk of
   losing the integration, including the ability to **release** devices that have been paid off.

Google's sanctioned route for this use case is a different program: **Android Device Lock**, operated
through an approved **Device Lock Controller (DLC)**. The platform surface is
[`android.devicelock.DeviceLockManager`](https://developer.android.com/reference/android/devicelock/DeviceLockManager)
(API 34), which is documented for exactly this scenario — "a financed device, where the financing
entity has the capability to lock the device in case of a missed payment". Access is not open:

- requires the `PackageManager.FEATURE_DEVICE_LOCK` system feature on the device,
- the privileged `android.Manifest.permission.MANAGE_DEVICE_LOCK_STATE` — **not grantable to an
  ordinary APK**, so compiling against it or requesting it is not evidence of capability,
- the lock app is a *kiosk app installed by the device-lock solution*; the creditor must be an
  approved device-lock solution, and `DEVICE_LOCK_ROLE_FINANCING` is an explicit role.

The same gating applies in spirit to OEM routes: eligibility, account approval and licensing decide
what can actually be enforced, not code.

## Decision

**Device-level enforcement is delegated to an approved external provider, behind an adapter. This
repository's agent is classified as *deterrence and detection*, never as *enforcement*.**

1. **Three named routes, evaluated before any integration code is written:**

   | Route | Reset-surviving? | Brand coverage | Gate |
   |---|---|---|---|
   | **A — Android Device Lock / DLC** (Google, API 34) | Yes | Mixed-brand, GMS | Google approval as a device-lock solution + `FEATURE_DEVICE_LOCK` on the SKU |
   | **B — OEM financing lock** (Samsung Knox Guard; equivalents per OEM) | Yes (strongest) | Per-OEM | Approved OEM business account; per-model/region eligibility |
   | **C — Authorized third-party provider** (aggregator with OEM/DLC contracts) | Claimed | Vendor claim | Written support matrix + our own hardware test evidence |
   | ~~D — AMAPI / Android Enterprise for payment locking~~ | — | — | **Rejected — prohibited by policy (see above)** |

   Selection is a business decision on cost, coverage and approval lead time. Do **not** default to
   D, and do not treat platform FRP tricks as a compliant substitute for an approved financing lock.

2. **Provider capability is abstracted, identity is not.** Server-side seam:

   ```
   DeviceEnforcementProvider
     ├── verifyEnrollment()
     ├── getCapabilities()
     ├── requestLock()
     ├── requestUnlock()
     └── getDeviceStatus()
   ```

   Unsupported operations are marked `unsupported`/`manual-only` explicitly. Silence is never
   success. Lock/release requests and provider confirmations are distinct states
   (`LOCK_REQUESTED` → `LOCKED_CONFIRMED`, with `FAILED`/`UNKNOWN` reachable), so a timeout can never
   be recorded as a completed lock.

3. **Existing identity stays.** The server-issued opaque device id from
   [ADR 0003](0003-server-issued-device-ids.md) remains the internal primary key. Provider
   registration ids hang off it as a separate mapping. IMEI/serial are intake/telemetry data only,
   never keys, and never read by the agent — a provider requirement for an identifier does not
   reopen ADR 0003.

4. **Loan status and device state stay separate fields.** `ENROLLED → PAYMENT_DUE → OVERDUE/GRACE →
   PAID_OFF` (plus a dispute hold) describes the money; `UNLOCKED_CONFIRMED → LOCK_REQUESTED →
   LOCKED_CONFIRMED → RELEASE_REQUESTED → RELEASE_CONFIRMED` describes the handset. Collapsing them
   is how a paid-off customer ends up with a locked phone.

5. **The release path is designed and custody-controlled before the lock path ships.** Auto-release
   on final payment must be scriptable (provider API), and the provider account's credentials need
   named custodians, escrow and a tested break-glass — the same discipline already flagged for the
   APK signing key in [ADR 0002](0002-self-signed-agent-distribution.md) and the open
   `TODO(keystore custody)` in `app/build.gradle.kts`. Losing that account permanently bricks
   financed devices, *including* ones customers already own.

6. **Feasibility gate first, code second.** No integration is written until one real,
   provider-approved device has been: enrolled → locked → wiped via recovery → still locked →
   released. Marketing claims per-brand coverage only after that matrix passes for each advertised
   brand/model/region/build.

## Consequences

- (+) The product cannot over-promise. The agent's `factoryReset` policy is documented in
  `proto/registry.md` as **deterrence only**, and code comments in
  `com.mdmesh.policy.reset.FactoryResetPolicy` repeat the limit so nobody reads it as anti-theft.
- (+) Provider choice is reversible — swapping a vendor touches one adapter, not the identity model,
  the protocol, or the agent.
- (+) Compliance risk is visible and owned rather than discovered mid-launch by Google or an OEM.
- (−) **There is a hard external dependency.** Until route A, B or C is approved, this product is an
  MDM with good audit trails, not an EMI locker. Approval lead time (weeks to months) is on the
  critical path and no amount of coding removes it.
- (−) Mixed-brand financing is materially harder than Samsung-only. If no provider approves a given
  SKU, that SKU cannot be honestly offered on EMI.
- (−) Legal/compliance work is a launch blocker, not a follow-up: borrower consent to the lock,
  disclosed release process, dispute route, and (for India) RBI Digital Lending Guidelines
  obligations around prior explicit consent and audit trail when operating in a regulated
  lender / LSP workflow. Validate with counsel.
- (−) `factoryResetProtection` remains a reserved-but-unimplemented registry row. It must not be
  implemented as a payment-status lock.

## Related

`docs/anti-theft-lock-plan.md` (threat model + phase plan) ·
`docs/device-lock/current-architecture-audit.md` (what exists today) ·
ADRs 0002 (key custody), 0003 (identity), 0005 (permission minimization), 0006/0009 (capability
handshake + contract stability — provider fields must be additive-only).
