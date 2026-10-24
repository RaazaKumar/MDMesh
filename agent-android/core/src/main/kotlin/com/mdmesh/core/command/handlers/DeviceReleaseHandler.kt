package com.mdmesh.core.command.handlers

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.store.EnrollTokenStore
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * `device.release` — Releases the device from MDMesh management.
 * Permanently removes all user restrictions (factory reset, safe boot, etc.),
 * drops Device Owner and Active Admin privileges, and clears local credentials.
 */
class DeviceReleaseHandler(
    private val context: Context,
    private val handle: DpmHandle,
    private val deviceIdStore: DeviceIdStore,
) : CommandHandler {

    override val type: String = DeviceAction.RELEASE

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        Log.i(TAG, "device.release received — releasing device...")
        executeRelease(context, handle, deviceIdStore)
        CommandResults.done(command)
    }.getOrElse {
        Log.e(TAG, "device.release failed", it)
        CommandResults.failed(command, it.message ?: "release failed")
    }

    companion object {
        private const val TAG = "DeviceRelease"

        fun executeRelease(
            context: Context,
            handle: DpmHandle,
            deviceIdStore: DeviceIdStore? = null,
        ) {
            val dpm = handle.dpm
            val admin = handle.admin

            // 1. Remove all security restrictions so user has full control
            val restrictions = listOf(
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_REMOVE_USER,
                UserManager.DISALLOW_MODIFY_ACCOUNTS,
                UserManager.DISALLOW_USB_FILE_TRANSFER,
                UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                UserManager.DISALLOW_CONFIG_BLUETOOTH,
                UserManager.DISALLOW_CONFIG_WIFI,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            )
            for (res in restrictions) {
                runCatching { dpm.clearUserRestriction(admin, res) }
            }

            // 2. Allow user to uninstall or clear app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { dpm.setUserControlDisabledPackages(admin, emptyList()) }
            }

            // 3. Clear kiosk / lock-task
            runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }

            // 4. Clear keyguard restrictions
            runCatching { dpm.setKeyguardDisabledFeatures(admin, 0) }

            // 5. Clear stored credentials in background
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { deviceIdStore?.clear() }
                runCatching { EnrollTokenStore(context).clear() }
            }

            // 6. Clear Device Owner
            runCatching {
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.clearDeviceOwnerApp(context.packageName)
                }
            }

            // 7. Remove active admin
            runCatching {
                if (dpm.isAdminActive(admin)) {
                    dpm.removeActiveAdmin(admin)
                }
            }

            Log.i(TAG, "Device release complete! Restrictions, Device Owner, and Admin cleared.")
        }
    }
}
