# Capability & command registries (v1)

Open string registries. Adding a row is an additive (MINOR) change. Keep this file in sync
with the agent's capability advertisement and the server's command catalog.

## Capability keys

### policy
| key | meaning | min SDK | notes |
|-----|---------|---------|-------|
| `wifi` | toggle/lock Wi-Fi | 24 | |
| `bluetooth` | toggle/lock Bluetooth | 24 | BLUETOOTH_SCAN auto-grant fails if target app targetSdk<=30 |
| `gps` | location toggle | 24 | |
| `mobileData` | mobile data toggle | 24 | |
| `usbStorage` | block USB mass storage | 24 | |
| `camera` | disable camera | 24 | |
| `screenshots` | disable screenshots | 24 | |
| `factoryReset` | block factory reset from Settings (Device Owner) | 27 | `DISALLOW_FACTORY_RESET` user restriction. **Deterrence only** — no effect on a recovery-console wipe or firmware reflash. (`setMasterClearEnabled` is *not* an Android API; do not add it) |
| `kioskLockTask` | COSU lock-task kiosk | 24 | setLockTaskPackages/Features |
| `passwordComplexity` | password policy | 31 | setRequiredPasswordComplexity (setPasswordQuality deprecated @26) |
| `systemUpdatePolicy` | OS update windows | 24 | |
| `factoryResetProtection` | FRP policy | 30 | setFactoryResetProtectionPolicy |

> **Reserved rows are not promises.** A key listed here is *advertised* only when the agent
> has a working strategy for it on that device (`CapabilityRegistry.togglePolicies()`); absence is
> reported as `unsupported`, never as a failure. At the time of writing `factoryReset` is
> implemented; `passwordComplexity`, `systemUpdatePolicy` and `factoryResetProtection` are
> registered names with no strategy behind them yet. Note also that `factoryResetProtection`
> (API 31, not 30 — verify before implementing) must **not** be used for payment-status locking:
> see ADR 0010 on Google's Android Management API permissible-usage prohibition.

### appManagement
| key | meaning | notes |
|-----|---------|-------|
| `silentInstall` | PackageInstaller silent install | Device Owner only |
| `silentUninstall` | silent uninstall | Device Owner only |
| `splitApk` | split/.xapk install | |
| `fdroidCatalog` | can pull from an F-Droid repo | for the APK browser/catalog feature |

### remoteControl
Advertised as an object (`tier`, `screenCapture`, `inputInjection`, `transport`). See README.

### oem
`vendor`, `knox` (parked tier).

## Command types

| type | requiresCapability | payload (sketch) |
|------|--------------------|------------------|
| `config.sync` | — | none (triggers a full reconcile) |
| `policy.apply` | the specific policy key | `{ policy: "wifi", value: false }` |
| `app.install` | `silentInstall` | `{ url, packageName, versionCode, sha256, runAfterInstall }` |
| `app.uninstall` | `silentUninstall` | `{ packageName }` |
| `app.launch` | — | `{ packageName, activity? }` |
| `remote.startSession` | `remoteControl.tier>=view` | `{ sessionId, signaling: {...}, mode: "view"|"control" }` |
| `remote.stopSession` | — | `{ sessionId }` |
| `device.reboot` | — | none (DO) |
| `device.lock` | — | none |

Per-type payload JSON Schemas go in `proto/payloads/` as the registry grows.
