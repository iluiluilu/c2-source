import "next-auth"
import "next-auth/jwt"

declare module "next-auth" {
  interface Session {
    accessToken?: string
    idToken?: string
    roles?: string[]
    /** Keycloak login username (`preferred_username`) - use for identity checks. */
    username?: string
    /** Full display name (`name` claim, falls back to username) - greeting UI only. */
    displayName?: string
    /** Keycloak `sub` claim - the same id the backend uses as userSub (e.g. group_member.user_sub). */
    sub?: string
    error?: "RefreshAccessTokenError"
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    accessToken?: string
    idToken?: string
    refreshToken?: string
    /** Access-token expiry, epoch seconds. */
    expiresAt?: number
    roles?: string[]
    /** Keycloak login username (`preferred_username`) - use for identity checks. */
    username?: string
    /** Full display name (`name` claim, falls back to username) - greeting UI only. */
    displayName?: string
    /** Keycloak `sub` claim - the same id the backend uses as userSub (e.g. group_member.user_sub). */
    sub?: string
    error?: "RefreshAccessTokenError"
  }
}
