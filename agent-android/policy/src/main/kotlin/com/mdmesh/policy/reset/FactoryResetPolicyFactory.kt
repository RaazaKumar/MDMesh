package com.mdmesh.policy.reset

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.wifi.DpmHandle

/**
 * Selects the [FactoryResetPolicy] strategy for the current device. Returns `null`
 * when no strategy is supported (not Device Owner, or API < 27), in which case the
 * `factoryReset` capability is never advertised and can never be commanded.
 */
object FactoryResetPolicyFactory {

    fun create(handle: DpmHandle): FactoryResetPolicy? =
        listOf(FactoryResetRestrictionPolicy(handle)).firstOrNull { it.isSupported() }

    /**
     * Arm the Settings-reset block at Device-Owner activation.
     *
     * Kept separate from the generic `policy.apply` path on purpose: protection must
     * not depend on the server managing to reach the device. A phone that is
     * provisioned and then powered off / left offline would otherwise sit fully
     * resettable until its first successful check-in.
     *
     * Idempotent and non-throwing — safe to call from every admin lifecycle callback.
     */
    fun armAtProvisioning(handle: DpmHandle): PolicyOutcome =
        create(handle)?.setEnabled(false) ?: PolicyOutcome.Unsupported
}
