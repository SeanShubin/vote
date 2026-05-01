package com.seanshubin.vote.domain

data class UserRolePermissions(
    val userName: String,
    val role: Role,
    val permissions: List<Permission>,
) {
    fun canChangeRole(target: UserRole, newRole: Role): RoleChangeResult {
        if (Permission.MANAGE_USERS !in permissions) {
            return RoleChangeResult.Denied("must have ${Permission.MANAGE_USERS} permission")
        }
        if (userName == target.userName) {
            return RoleChangeResult.Denied("may not change role of self")
        }
        // Ownership handoff: an OWNER with TRANSFER_OWNER may promote another user to OWNER.
        // The service layer is responsible for atomically demoting the caller in the same transaction.
        if (Permission.TRANSFER_OWNER in permissions && role == Role.OWNER && newRole == Role.OWNER) {
            return RoleChangeResult.Ok
        }
        if (role <= newRole) {
            return RoleChangeResult.Denied("may only assign lesser roles")
        }
        if (role <= target.role) {
            return RoleChangeResult.Denied("may only modify users with lesser roles")
        }
        return RoleChangeResult.Ok
    }

    fun listedRolesFor(target: UserRole): List<Role> =
        Role.entries.filter { candidate ->
            candidate == target.role || canChangeRole(target, candidate) is RoleChangeResult.Ok
        }
}

sealed interface RoleChangeResult {
    data object Ok : RoleChangeResult
    data class Denied(val reason: String) : RoleChangeResult
}
