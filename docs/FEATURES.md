# MDMesh (AndroidLock) — Feature Report

> A complete inventory of what this platform can do, generated from the current codebase.
> Last updated: 13 Oct 2026.

**MDMesh** is a Mobile Device Management (MDM) platform for Android, built as a modern
re-architecture of Headwind MDM: a from-scratch Kotlin agent, a React control panel, and a
Java/Tomcat server speaking a versioned, capability-gated JSON protocol (`proto/`).

---

## 1. Device Enrollment & Provisioning

| Feature | Details |
|---|---|
| QR-based Device Owner provisioning | Android Enterprise 6-tap QR flow; the console generates the QR (Enroll page) |
| Wi-Fi auto-join during provisioning | SSID / password / security baked into the QR (values normalized to `WPA` / `WEP` / `NONE`) |
| APK integrity pinning | `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` — the device only installs an agent APK whose signing cert matches the console's checksum |
| One-time enrollment tokens | Single-use `enrollToken`; consumed atomically on `POST /rest/public/agent/v1/enroll` |
| Server-issued device IDs | Opaque UUID assigned by the server — never IMEI/serial (privacy-safe, ADR-0003) |
| Per-device secret | Bearer secret issued at enrollment; stored server-side only as a SHA-256 hash |

## 2. Remote Device Actions (console → device)

All actions flow through an **opaque command queue** with lifecycle
`pending → delivered → done / failed / unsupported / expired`, per-command capability gating,
and full history per device.

- **Safe:** Set lock-screen message · Send alert · Ring device (30 s locate tone) · Stop ringing ·
  Connectivity mode (Battery-saver / Always-on) · Location mode (Battery-saver / Accurate) ·
  Wi-Fi on/off · Camera disable · Screenshots block · Bluetooth / USB-storage policy
- **Disruptive:** Lock device · Reboot device · Enter kiosk · Exit kiosk
- **Destructive:** Reset passcode · Factory reset (wipe, type-to-confirm)

Agent-side handlers implementing these (module `agent-android/core/.../command/handlers`):
`DeviceLock`, `DeviceReboot`, `DeviceRing`, `DeviceRingStop`, `DeviceAlert`,
`DeviceLockscreenMessage`, `DevicePasscodeReset`, `DeviceWipe`, `DevicePowerMode`,
`DeviceLocationMode`, `PolicyApply`, `AppInstall`, `AppUninstall`, `AppScan`, `AppIcons`,
`ConfigSync`, `KioskEnter`, `KioskExit`.

## 3. Kiosk / COSU Mode

The core "device lock" capability:

- Lock-task pinning to **one app or a whitelist**, chosen from a live scan of the device
- Agent registers itself as HOME → kiosk **survives reboot**; auto re-entry on boot
- Crash-loop guard: repeated launcher crashes drop kiosk and show a recovery screen
- Managed home grid, PIN exit options, theme, status-bar blocking
- Remote release via `kiosk.exit`; on-device exit only per configured exit mode
- **Bulk kiosk-enter** across many selected devices

## 4. App Management

- Silent install / uninstall (Device Owner privilege)
- "Install config apps" — push every app of a configuration in one action
- App scan (installed-package inventory) and icon fetching
- **Bulk app install from the Library** across selected devices
- F-Droid repository integration; APK file hosting and an update channel
- Configuration files / icon pack management

## 5. Live Connectivity & Sync

- **WebSocket wake channel** (`/agent/ws/{deviceId}`) — "Sync now" and queued commands land in seconds
- Adaptive power mode: socket held only while screen is on or charging;
  **15-minute WorkManager floor** as the low-power backstop
- Capability handshake on every check-in — the server never sends a command the device can't honour
  (open string registries per `proto/registry.md`; protocol versioning per ADR-0006)
- MQTT push + legacy long-polling retained for the old Headwind launcher path

## 6. Monitoring & Data

- Live device state: battery / charging, locked, kiosk-active, agent version, power mode
- **GPS tracking** with a deduped breadcrumb trail (`device_location`; fused accuracy, map view)
- Device detail tabs: Control / Telemetry / Events / Location
- Command history with per-command result detail
- Hardware info reporting (manufacturer, model, serial, Android version)
- Legacy plugin surfaces: device logs, usage stats, audit trail, messaging, device info export

## 7. Web Console (React + Vite)

- **Overview** dashboard · **Devices** list with multi-select + bulk actions · **Device detail**
- **Configurations** (profiles, policies, files, icons) · **Apps** library · **Enroll** (QR + Wi-Fi
  form + raw payload preview) · **Settings**
- Dark / light theme, toasts, LAN-aware QR base URL (`VITE_PUBLIC_BASE_URL`)

## 8. Server & Security

- Java / Tomcat REST API (`/rest/public/agent/v1/*`, `/rest/private/*`), Guice + MyBatis + Liquibase
- Auth: session + JWT module (`jwt/`), MD5-hashed password transit, forced first-login password
  reset, 2FA fields, signup & password-recovery flows
- Multi-tenancy: customers, user roles (Super-Admin / Admin), device groups
- Staged **rollout** management for agent/APK fleet updates
- Per-device secret required on every check-in; constant-time verification on the wake socket
- Optional secure enrollment signing + IP filtering (legacy `SECURE_ENROLLMENT` path)

## 9. Deployment & Infrastructure

- Docker Compose stacks: `docker-compose.dev.yml` (Postgres 14 + Tomcat),
  `docker-compose.release.yml`, `docker-compose.domain.yml` (+ Caddy TLS front)
- **Supervisor** (port 9000): deployment recovery + rollback
- One-command installs: `setup.sh`, `install-native.sh`, `quickstart.sh`
- SQL seeds localized EN / RU / BR; email templates per locale
- Helper scripts: `iptables-tomcat.sh`, `letsencrypt-ssl.sh`
- OEM layer with Samsung KNOX detection (`agent-android/oem`)
- Licensing & re-brand model (ADR-0008)

## 10. Verification Status (dev stack, 13 Oct 2026)

| Area | Status |
|---|---|
| QR provisioning → Device Owner → agent install | ✅ Verified |
| Enrollment (`enroll` endpoint, device UUID issued & persisted) | ✅ Verified |
| Device online in console with telemetry + GPS | ✅ Verified |
| Command queue: lock / alert / ring / ringStop / lockscreenMessage / powerMode / locationMode delivered, executed, acked `done` | ✅ Verified |
| Instant wake via WebSocket through dev proxy | ✅ Verified |
| Cleartext-HTTP dev mode (LAN, no TLS) | ✅ Verified |
| Kiosk enter/exit, wipe, app install, bulk operations | ⬜ Implemented, not yet field-tested |

---

*See also: `README.md`, `docs/agent-architecture.md`, `docs/adr/` (design decisions),
`proto/endpoints.md` + `proto/registry.md` (wire contract), `DEPLOY.md` (production install).*
