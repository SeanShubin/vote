package com.seanshubin.vote.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UserRolePermissionsTest {

    private fun caller(role: Role, permissions: List<Permission>): UserRolePermissions =
        UserRolePermissions(userName = "caller", role = role, permissions = permissions)

    private fun target(name: String = "target", role: Role = Role.USER): UserRole =
        UserRole(name, role)

    @Test
    fun `denies caller without MANAGE_USERS`() {
        val caller = caller(Role.OBSERVER, listOf(Permission.VIEW_APPLICATION))
        val result = caller.canChangeRole(target(), Role.VOTER)
        val denied = assertIs<RoleChangeResult.Denied>(result)
        assertEquals("must have ${Permission.MANAGE_USERS} permission", denied.reason)
    }

    @Test
    fun `denies self-edit`() {
        val caller = caller(Role.ADMIN, listOf(Permission.MANAGE_USERS))
        val result = caller.canChangeRole(UserRole("caller", Role.ADMIN), Role.USER)
        val denied = assertIs<RoleChangeResult.Denied>(result)
        assertEquals("may not change role of self", denied.reason)
    }

    @Test
    fun `denies promoting target to or above caller's role`() {
        val caller = caller(Role.ADMIN, listOf(Permission.MANAGE_USERS))
        val sameLevel = caller.canChangeRole(target(role = Role.USER), Role.ADMIN)
        val above = caller.canChangeRole(target(role = Role.USER), Role.AUDITOR)

        assertIs<RoleChangeResult.Denied>(sameLevel)
        assertEquals("may only assign lesser roles", (sameLevel as RoleChangeResult.Denied).reason)
        assertIs<RoleChangeResult.Denied>(above)
    }

    @Test
    fun `denies modifying target whose role is at or above caller's`() {
        val caller = caller(Role.ADMIN, listOf(Permission.MANAGE_USERS))
        val peer = caller.canChangeRole(target("peer", Role.ADMIN), Role.USER)
        val superior = caller.canChangeRole(target("auditor", Role.AUDITOR), Role.USER)

        assertIs<RoleChangeResult.Denied>(peer)
        assertEquals("may only modify users with lesser roles", (peer as RoleChangeResult.Denied).reason)
        assertIs<RoleChangeResult.Denied>(superior)
    }

    @Test
    fun `allows admin promoting user to lesser role`() {
        val caller = caller(Role.ADMIN, listOf(Permission.MANAGE_USERS))
        val result = caller.canChangeRole(target("bob", Role.USER), Role.VOTER)
        assertIs<RoleChangeResult.Ok>(result)
    }

    @Test
    fun `allows owner with TRANSFER_OWNER to promote another user to OWNER`() {
        val caller = caller(
            Role.OWNER,
            listOf(Permission.MANAGE_USERS, Permission.TRANSFER_OWNER),
        )
        val result = caller.canChangeRole(target("heir", Role.USER), Role.OWNER)
        assertIs<RoleChangeResult.Ok>(result)
    }

    @Test
    fun `denies owner without TRANSFER_OWNER from promoting to OWNER`() {
        // Sanity check: the special case requires both OWNER role and TRANSFER_OWNER permission.
        val caller = caller(Role.OWNER, listOf(Permission.MANAGE_USERS))
        val result = caller.canChangeRole(target("heir", Role.USER), Role.OWNER)
        assertIs<RoleChangeResult.Denied>(result)
    }

    @Test
    fun `denies non-owner with TRANSFER_OWNER from promoting to OWNER`() {
        // The TRANSFER_OWNER bypass only fires when the caller is the current OWNER.
        val caller = caller(
            Role.AUDITOR,
            listOf(Permission.MANAGE_USERS, Permission.TRANSFER_OWNER),
        )
        val result = caller.canChangeRole(target("heir", Role.USER), Role.OWNER)
        assertIs<RoleChangeResult.Denied>(result)
    }

    @Test
    fun `listedRolesFor includes target current role plus assignable roles`() {
        val caller = caller(Role.ADMIN, listOf(Permission.MANAGE_USERS))
        val target = UserRole("bob", Role.USER)
        val listed = caller.listedRolesFor(target)
        // ADMIN can assign anything strictly less than ADMIN: NO_ACCESS, OBSERVER, VOTER, USER.
        // The target's current role (USER) is also included.
        assertEquals(
            listOf(Role.NO_ACCESS, Role.OBSERVER, Role.VOTER, Role.USER),
            listed,
        )
    }

    @Test
    fun `listedRolesFor of peer returns only peer's current role`() {
        // ADMIN viewing another ADMIN: can't change the peer at all,
        // so the only role offered is the one already assigned.
        val caller = caller(Role.ADMIN, listOf(Permission.MANAGE_USERS))
        val target = UserRole("eve", Role.ADMIN)
        assertEquals(listOf(Role.ADMIN), caller.listedRolesFor(target))
    }

    @Test
    fun `listedRolesFor owner with TRANSFER_OWNER includes OWNER for any subordinate`() {
        val caller = caller(
            Role.OWNER,
            listOf(Permission.MANAGE_USERS, Permission.TRANSFER_OWNER),
        )
        val target = UserRole("heir", Role.AUDITOR)
        val listed = caller.listedRolesFor(target)
        // Every role at or below OWNER is reachable, plus OWNER via the handoff escape hatch.
        assertEquals(Role.entries.toList(), listed)
    }
}
