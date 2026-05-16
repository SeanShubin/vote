package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.QueryLoader
import com.seanshubin.vote.contract.SystemSettings
import com.seanshubin.vote.domain.FeatureFlag
import java.sql.Connection

class MySqlSystemSettings(
    private val connection: Connection,
    private val queryLoader: QueryLoader,
) : SystemSettings {
    override fun isEnabled(flag: FeatureFlag): Boolean =
        listAll()[flag] ?: flag.defaultEnabled

    override fun setEnabled(flag: FeatureFlag, enabled: Boolean) {
        val sql = queryLoader.load("feature-flags-upsert")
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, flag.name)
            stmt.setBoolean(2, enabled)
            stmt.executeUpdate()
        }
    }

    override fun listAll(): Map<FeatureFlag, Boolean> {
        val sql = queryLoader.load("feature-flags-select-all")
        val stored = mutableMapOf<String, Boolean>()
        connection.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                stored[rs.getString("flag_name")] = rs.getBoolean("enabled")
            }
        }
        // Resolve against the enum so unknown stored flags (e.g. retired
        // ones still in the table) are skipped and every current flag has
        // a value — defaults filling the gaps.
        return FeatureFlag.entries.associateWith { flag ->
            stored[flag.name] ?: flag.defaultEnabled
        }
    }
}
