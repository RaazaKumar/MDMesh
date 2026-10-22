package com.mdmesh.policy.reset

import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy

/**
 * Capability-abstracted control over whether the user may factory-reset the device
 * from the Settings app.
 *
 * Semantics follow the module-wide convention: `setEnabled(true)` *allows* the
 * feature (reset possible, restrictions cleared); `setEnabled(false)` *blocks* it.
 * Callers never think about the underlying inversion.
 *
 * SCOPE LIMIT — read before using this as "anti-theft": the restriction behind this
 * policy only closes the **Settings** path. A reset triggered from the recovery
 * console (hardware keys) or by reflashing firmware is outside the Android
 * framework and cannot be blocked by any Device-Owner app, on any brand. Real
 * reset-surviving enforcement lives at the OEM/Google layer (Knox Guard, Device
 * Lock Controller). See `docs/anti-theft-lock-plan.md` and ADR 0010.
 */
interface FactoryResetPolicy : TogglePolicy {

    override fun setEnabled(enabled: Boolean): PolicyOutcome

    companion object {
        const val CAPABILITY_KEY = "factoryReset"
    }
}
