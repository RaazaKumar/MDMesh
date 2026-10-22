// Builds the Android QR provisioning payload for the MDMesh agent. Crucially it carries the
// SERVER_URL extra, so one prebuilt APK works for any deployment (the agent reads it at enrollment).
//
// Deploy-specific values default to the shipped debug build and can be overridden at web-build time:
//   VITE_AGENT_PACKAGE     applicationId of the distributed APK (default com.mdmesh.agent.debug)
//   VITE_AGENT_CHECKSUM    PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM of the signing cert
//   VITE_AGENT_APK_URL     where the APK is hosted (default <origin>/files/agent.apk)
//   VITE_PUBLIC_BASE_URL   origin embedded in the QR (default window.location.origin). A
//                          factory-reset phone cannot reach a dev machine's localhost — point this
//                          at an address the phone can reach (e.g. http://192.168.1.10:5173).

const env = import.meta.env as Record<string, string | undefined>;

const AGENT_PACKAGE = env.VITE_AGENT_PACKAGE || 'com.mdmesh.agent.debug';
const ADMIN_COMPONENT = `${AGENT_PACKAGE}/com.mdmesh.agent.admin.AdminReceiver`;
const SIGNATURE_CHECKSUM = env.VITE_AGENT_CHECKSUM || 'YMEWhg_-ydciRFgtdOZbQ-W_aPPz7orHKuqfn13Ujaw';

/** Server origin embedded in the QR payload. Production: same-origin (Caddy serves the SPA and
 *  proxies the API). Development: override with VITE_PUBLIC_BASE_URL — a factory-reset phone
 *  cannot reach the dev machine's localhost. */
export function serverBaseUrl(): string {
  const override = env.VITE_PUBLIC_BASE_URL?.trim();
  if (override) return override.replace(/\/+$/, '');
  return window.location.origin;
}

/** True when the origin embedded in the QR is a loopback address the phone cannot reach. */
export function serverUrlIsLoopback(): boolean {
  try {
    const host = new URL(serverBaseUrl()).hostname;
    return host === 'localhost' || host === '127.0.0.1' || host === '::1';
  } catch {
    return false;
  }
}

export function agentApkUrl(): string {
  // VITE_AGENT_APK_URL may be absolute (https://…) or origin-relative (/update/agent.apk → resolved
  // against this deployment's origin). Release builds set it to the supervisor's verified APK mirror.
  const u = env.VITE_AGENT_APK_URL;
  if (u) return u.startsWith('/') ? `${serverBaseUrl()}${u}` : u;
  return `${serverBaseUrl()}/files/agent.apk`;
}

export type WifiSecurity = 'WPA' | 'WEP' | 'NONE' | 'EAP';

/** The only values PROVISIONING_WIFI_SECURITY_TYPE officially accepts — anything else makes the
 *  device abort with "Couldn't connect to Wi-Fi". */
export type ProvisioningWifiSecurity = 'WPA' | 'WEP' | 'NONE' | 'IEEE 802.1X';

/** Maps any selected/stored value onto the official provisioning constants (case-insensitive):
 *  WPA / WPA2 -> "WPA", WEP -> "WEP", open/blank -> "NONE", EAP / 802.1X -> "IEEE 802.1X".
 *  Unrecognized values fall back to "WPA" when a password is present, "NONE" otherwise. */
export function toProvisioningSecurity(
  security: string | undefined,
  hasPassword: boolean,
): ProvisioningWifiSecurity {
  switch ((security ?? '').trim().toUpperCase().replace(/\s+/g, '')) {
    case 'WPA':
    case 'WPA2':
    case 'WPA/WPA2':
    case 'WPA-PSK':
    case 'WPAPSK':
    case 'WPA2-PSK':
    case 'WPA2PSK':
      return 'WPA';
    case 'WEP':
      return 'WEP';
    case 'NONE':
    case 'OPEN':
    case '':
      return 'NONE';
    case 'EAP':
    case 'IEEE802.1X':
    case '802.1X':
    case 'WPA-EAP':
    case 'WPA2-EAP':
      return 'IEEE 802.1X';
    default:
      return hasPassword ? 'WPA' : 'NONE';
  }
}

/** Optional Wi-Fi to bake into the QR so the device joins it DURING provisioning (before it downloads
 *  the agent). The password is embedded in the QR in plaintext — that's inherent to Android QR Wi-Fi. */
export interface WifiConfig {
  ssid: string;
  password?: string;
  security?: WifiSecurity;
  /** Hidden (true) vs broadcast (false) SSID; defaults to a visible network. */
  hidden?: boolean;
}

/** The provisioning JSON a fresh device scans (6-tap → QR scanner). Pass `wifi` to pre-connect it. */
export function buildProvisioningPayload(token: string, wifi?: WifiConfig): string {
  const payload: Record<string, unknown> = {
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': ADMIN_COMPONENT,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM': SIGNATURE_CHECKSUM,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION': agentApkUrl(),
    'android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE': {
      'com.mdmesh.ENROLL_TOKEN': token,
      'com.mdmesh.SERVER_URL': serverBaseUrl(),
    },
    'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': true,
    'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true,
  };
  const ssid = wifi?.ssid?.trim();
  if (ssid) {
    const password = (wifi?.password ?? '').trim();
    // Normalize to the official PROVISIONING_WIFI_SECURITY_TYPE values — a raw pass-through can
    // ship unsupported values (e.g. "EAP") which the device rejects during provisioning.
    const security = toProvisioningSecurity(wifi?.security, !!password);
    payload['android.app.extra.PROVISIONING_WIFI_SSID'] = ssid;
    payload['android.app.extra.PROVISIONING_WIFI_HIDDEN'] = wifi?.hidden === true;
    payload['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = security;
    if (security !== 'NONE' && password) {
      payload['android.app.extra.PROVISIONING_WIFI_PASSWORD'] = wifi?.password;
    }
  }
  return JSON.stringify(payload);
}
