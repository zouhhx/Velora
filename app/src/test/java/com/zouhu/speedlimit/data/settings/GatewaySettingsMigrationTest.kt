package com.zouhu.speedlimit.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GatewaySettingsMigrationTest {
    @Test fun `legacy credential is removed and old real provider is migrated to remote without token`() = runTest {
        val key = stringPreferencesKey("ak")
        val mode = stringPreferencesKey("mode")
        val threshold = intPreferencesKey("threshold")
        val old = mutablePreferencesOf(key to "fake-legacy-value", mode to "baidu", threshold to 15)
        val migration = GatewaySettingsMigration()
        assertTrue(migration.shouldMigrate(old))
        val migrated = migration.migrate(old)
        assertFalse(migrated.contains(key))
        assertEquals("remote", migrated[mode])
        assertEquals(15, migrated[threshold])
        assertNull(migrated[stringPreferencesKey("gateway_token")])
        assertFalse(migration.shouldMigrate(migrated))
    }

    @Test fun `legacy simulation is migrated to direct and clean installation needs no migration`() = runTest {
        val migration = GatewaySettingsMigration()
        val mode = stringPreferencesKey("mode")
        val old = mutablePreferencesOf(stringPreferencesKey("ak") to "fake-unused-value", mode to "simulation")
        assertTrue(migration.shouldMigrate(old))
        assertEquals("direct", migration.migrate(old)[mode])
        assertFalse(migration.shouldMigrate(emptyPreferences()))
        assertEquals("", AppSettings().gatewayToken)
        assertFalse(AppSettings(gatewayToken = "never-print-me").toString().contains("never-print-me"))
    }
}
