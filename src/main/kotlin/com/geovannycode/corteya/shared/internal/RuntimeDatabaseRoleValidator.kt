package com.geovannycode.corteya.shared.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Refuse a runtime credential that could silently bypass the database's isolation. */
@Component
class RuntimeDatabaseRoleValidator(private val jdbc: JdbcTemplate) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val safe = jdbc.queryForObject("""select current_user = 'corteya_app' and not rolsuper and not rolbypassrls
            and not pg_has_role(current_user, 'corteya_owner', 'MEMBER')
            and not exists(select 1 from pg_tables where schemaname = 'public' and tableowner = current_user)
            from pg_roles where rolname = current_user""", Boolean::class.java)
        check(safe == true) { "Runtime debe usar corteya_app sin ownership, superuser, BYPASSRLS ni membresía en corteya_owner" }
    }
}
