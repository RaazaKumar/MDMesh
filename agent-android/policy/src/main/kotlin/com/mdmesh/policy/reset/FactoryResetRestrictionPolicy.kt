package com.mdmesh.policy.reset

import android.os.Build
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.UserRestrictions
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Strategy backing [FactoryResetPolicy], using the one framework lever that actually
 * exists for the Settings factory-reset path: the Device-Owner-only
 * [android.os.UserManager.DISALLOW_FACTORY_RESET] user restriction (API 27+).
 *
 * **Note on a widely-cited but non-existent API.** `DevicePolicyManager.setMasterClearEnabled`
 * is frequently recommended (including in this repo's older `docs/plan`) as the call that
 * blocks a Settings factory reset. It is **not part of the Android SDK** — verified against
 * `android-35/android.jar`, where the only factory-reset-adjacent members of
 * [android.app.admin.DevicePolicyManager] are `wipeData` / `wipeDevice` /
 * `setMaximumFailedPasswordsForWipe` / `setFactoryResetProtectionPolicy`. Code cannot be
 * written against it, so `DISALLOW_FACTORY_RESET` is the real mechanism here.
 *
 * A reset from the recovery console (hardware keys) is outside the framework and is **not**
 * blocked by this policy — see [FactoryResetPolicy].
 *
 * Supported only as Device Owner on API 27+; below that [isSupported] returns false so the
 * `factoryReset` capability is never advertised and can never be commanded.
 */
internal class FactoryResetRestrictionPolicy(
    private val handle: DpmHandle,
) : FactoryResetPolicy {

    override val capabilityKey: String = FactoryResetPolicy.CAPABILITY_KEY

    private val restrictions: Set<String> =
        UserRestrictions.forKey(FactoryResetPolicy.CAPABILITY_KEY).orEmpty()

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setEnabled(enabled: Boolean): PolicyOutcome = runCatching {
        // Feature ON (enabled=true) => reset allowed: restriction cleared.
        // OFF => restriction added, hiding the Settings reset entry for all users.
        restrictions.forEach { key ->
            if (enabled) {
                handle.dpm.clearUserRestriction(handle.admin, key)
            } else {
                handle.dpm.addUserRestriction(handle.admin, key)
            }
        }
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "factory reset setEnabled failed") }
}
