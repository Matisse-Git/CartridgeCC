package dev.cartridge.environment

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Optional HOME candidate; only Android's user-facing chooser may make it the default. */
class HomeRegistration(private val context: Context) {
    private val component get() = ComponentName(context.packageName, "dev.cartridge.CartridgeHome")
    fun enabled() = context.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    fun enable(value: Boolean) {
        context.packageManager.setComponentEnabledSetting(component,
            if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }
    fun requestIntent(): Intent {
        check(enabled())
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !roles.isRoleHeld(RoleManager.ROLE_HOME))
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }
}
