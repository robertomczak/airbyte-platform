package authorization

import io.airbyte.api.model.generated.PermissionCheckRead
import io.airbyte.api.model.generated.PermissionType
import io.airbyte.api.model.generated.PermissionsCheckMultipleWorkspacesRequest
import io.airbyte.api.server.problems.ForbiddenProblem
import io.airbyte.commons.json.Jsons
import io.airbyte.commons.server.handlers.PermissionHandler
import io.airbyte.commons.server.support.AuthenticationHeaderResolver
import io.airbyte.commons.server.support.AuthenticationHttpHeaders.CONNECTION_ID_HEADER
import io.airbyte.commons.server.support.AuthenticationHttpHeaders.DESTINATION_ID_HEADER
import io.airbyte.commons.server.support.AuthenticationHttpHeaders.JOB_ID_HEADER
import io.airbyte.commons.server.support.AuthenticationHttpHeaders.SOURCE_ID_HEADER
import io.airbyte.commons.server.support.AuthenticationHttpHeaders.WORKSPACE_IDS_HEADER
import io.airbyte.commons.server.support.CurrentUserService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Authorization helper for the Airbyte API. Responsible for checking whether or not a user has access to the requested resources and should be called
 * for any Airbyte API endpoint that requires authorization and doesn't go through the CloudAuthenticationProvider.
 */
@Singleton
class AirbyteApiAuthorizationHelper(
  private val authorizationHeaderResolver: AuthenticationHeaderResolver,
  private val permissionHandler: PermissionHandler,
  private val currentUserService: CurrentUserService,
) {
  private fun resolveIdsToWorkspaceIds(
    ids: List<String>,
    scope: Scope,
  ): List<UUID>? {
    val properties =
      when (scope) {
        Scope.WORKSPACE -> {
          buildPropertiesMapForWorkspaces(ids)
        }
        Scope.WORKSPACES -> {
          buildPropertiesMapForWorkspaces(ids)
        }
        Scope.SOURCE -> {
          buildPropertiesMapForSource(ids.first())
        }
        Scope.DESTINATION -> {
          buildPropertiesMapForDestination(ids.first())
        }
        Scope.CONNECTION -> {
          buildPropertiesMapForConnection(ids.first())
        }
        Scope.JOB -> {
          buildPropertiesMapForJob(ids.first())
        }
      }
    return authorizationHeaderResolver.resolveWorkspace(properties)
  }

  /**
   * Given a scoped ID, confirm that the current user has the given permission type.
   *
   * @param id - The ID we are checking permissions for
   * @param scope - The scope of the ID
   * @param permissionTypes - the set of permissions needed to access the resource(s).
   * If the user has any of the permissions, the check will pass.
   *
   * @throws ForbiddenProblem - If the user does not have the required permissions
   */
  fun checkWorkspacePermissions(
    id: String,
    scope: Scope,
    permissionTypes: Set<PermissionType>,
  ) {
    checkWorkspacePermissions(listOf(id), scope, currentUserService.currentUser.userId, permissionTypes)
  }

  /**
   * Given a list of scoped IDs and a user ID, confirm that the indicated user
   * has the given permission type.
   *
   * @param ids - The Ids we are checking permissions for
   * @param scope - The scope of the Ids
   * @param userId - The ID of the user we are checking permissions for
   * @param permissionType - the permission needed to access the resource(s)
   *
   * @throws ForbiddenProblem - If the user does not have the required permissions
   */
  fun checkWorkspacePermissions(
    ids: List<String>,
    scope: Scope,
    userId: UUID,
    permissionType: PermissionType,
  ) {
    checkWorkspacePermissions(ids, scope, userId, setOf(permissionType))
  }

  /**
   * Given a list of scoped IDs, confirm that the current user has the
   * given workspace permission type.
   *
   * @param ids - The Ids we are checking permissions for
   * @param scope - The scope of the Ids
   * @param permissionTypes - the set of permissions needed to access the resource(s).
   * If the user has any of the permissions, the check will pass.
   *
   * @throws ForbiddenProblem - If the user does not have the required permissions
   */
  fun checkWorkspacePermissions(
    ids: List<String>,
    scope: Scope,
    permissionTypes: Set<PermissionType>,
  ) {
    checkWorkspacePermissions(ids, scope, currentUserService.currentUser.userId, permissionTypes)
  }

  /**
   * Given a list of scoped IDs, confirm that the current user has the
   * given workspace permission type.
   *
   * @param ids - The Ids we are checking permissions for
   * @param scope - The scope of the Ids
   * @param userId - The ID of the user we are checking permissions for
   * @param permissionTypes - the set of permissions needed to access the resource(s).
   * If the user has any of the permissions, the check will pass.
   *
   * @throws ForbiddenProblem - If the user does not have the required permissions
   */
  fun checkWorkspacePermissions(
    ids: List<String>,
    scope: Scope,
    userId: UUID,
    permissionTypes: Set<PermissionType>,
  ) {
    logger.debug { "Checking workspace permissions for $ids in scope [${scope.name}]." }
    if (ids.isEmpty() && scope != Scope.WORKSPACES) {
      throw ForbiddenProblem("No Ids provided for scope: ${scope.name}.")
    }
    if (ids.isEmpty() && scope == Scope.WORKSPACES) {
      logger.debug { "Empty workspaceIds, controller endpoint will pull all permissioned workspaces." }
      return
    }

    if (permissionHandler.isUserInstanceAdmin(userId)) {
      logger.debug { "User $userId is an instance admin, short circuiting auth check." }
      return
    }

    // The Ids we are checking permissions for
    val resolvedWorkspaceIds = resolveIdsToWorkspaceIds(ids, scope)

    if (resolvedWorkspaceIds.isNullOrEmpty()) {
      throw ForbiddenProblem("Unable to resolve to a workspace for $ids in scope [${scope.name}].")
    }

    if (!checkIfAnyPermissionGranted(resolvedWorkspaceIds, userId, permissionTypes)) {
      throw ForbiddenProblem("User does not have the required permissions to access the resource(s) $ids of type [${scope.name}].")
    }
  }

  private fun checkIfAnyPermissionGranted(
    resolvedWorkspaceIds: List<UUID>,
    userId: UUID,
    permissionTypes: Set<PermissionType>,
  ): Boolean {
    for (permissionType in permissionTypes) {
      val request = PermissionsCheckMultipleWorkspacesRequest()
      request.permissionType = permissionType
      request.userId = userId
      request.workspaceIds = resolvedWorkspaceIds

      val permissionCheckRead = permissionHandler.permissionsCheckMultipleWorkspaces(request)

      if (permissionCheckRead.status == PermissionCheckRead.StatusEnum.SUCCEEDED) {
        return true
      }
    }
    return false
  }

  private fun buildPropertiesMapForConnection(id: String): Map<String, String> {
    return mapOf(Scope.CONNECTION.mappedHeaderProperty to id)
  }

  private fun buildPropertiesMapForSource(id: String): Map<String, String> {
    return mapOf(Scope.SOURCE.mappedHeaderProperty to id)
  }

  private fun buildPropertiesMapForDestination(id: String): Map<String, String> {
    return mapOf(Scope.DESTINATION.mappedHeaderProperty to id)
  }

  private fun buildPropertiesMapForWorkspaces(ids: List<String>): Map<String, String> {
    return mapOf(Scope.WORKSPACES.mappedHeaderProperty to Jsons.serialize(ids))
  }

  private fun buildPropertiesMapForJob(id: String): Map<String, String> {
    return mapOf(Scope.JOB.mappedHeaderProperty to Jsons.serialize(id))
  }
}

enum class Scope(val mappedHeaderProperty: String) {
  WORKSPACE(WORKSPACE_IDS_HEADER),
  WORKSPACES(WORKSPACE_IDS_HEADER),
  CONNECTION(CONNECTION_ID_HEADER),
  SOURCE(SOURCE_ID_HEADER),
  DESTINATION(DESTINATION_ID_HEADER),
  JOB(JOB_ID_HEADER),
}
