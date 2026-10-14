import NextAuth from "next-auth"
import type { JWT } from "next-auth/jwt"
import KeycloakProvider from "next-auth/providers/keycloak"
import { decodeJwtPayload } from "@/lib/jwt"
import { Role } from "@/lib/roles"

const KNOWN_ROLES: string[] = Object.values(Role)

/**
 * Pull the known realm roles, login username, display name and subject off a
 * Keycloak access token. `username` must stay the actual login name
 * (`preferred_username`) - it's compared against the backend's account list
 * (`row.username`, Keycloak's `username` field) for "is this my own account"
 * checks (e.g. admin/accounts hiding self lock/disable). `displayName` is the
 * friendlier full name, for greeting UI only.
 */
function claimsFromAccessToken(
  accessToken?: string,
): Pick<JWT, "roles" | "username" | "displayName" | "sub"> {
  const claims = decodeJwtPayload<{
    realm_access?: { roles?: string[] }
    preferred_username?: string
    name?: string
    sub?: string
  }>(accessToken ?? "")
  return {
    roles: claims?.realm_access?.roles?.filter((r) => KNOWN_ROLES.includes(r)) ?? [],
    username: claims?.preferred_username,
    displayName: claims?.name || claims?.preferred_username,
    sub: claims?.sub,
  }
}

/**
 * Keycloak access tokens are short-lived (default 5 min). Exchange the refresh
 * token for a fresh access token so backend calls don't start 401ing once the
 * original token expires. On failure (e.g. the refresh token itself expired via
 * SSO idle timeout) we flag the token so the UI can force a re-login.
 */
async function refreshAccessToken(token: JWT): Promise<JWT> {
  try {
    if (!token.refreshToken) throw new Error("missing refresh token")

    const res = await fetch(
      `${process.env.KEYCLOAK_ISSUER}/protocol/openid-connect/token`,
      {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "refresh_token",
          client_id: process.env.KEYCLOAK_ID!,
          client_secret: process.env.KEYCLOAK_SECRET!,
          refresh_token: token.refreshToken,
        }),
      },
    )

    const refreshed = (await res.json()) as {
      access_token: string
      expires_in: number
      refresh_token?: string
      id_token?: string
    }
    if (!res.ok) throw refreshed

    return {
      ...token,
      accessToken: refreshed.access_token,
      idToken: refreshed.id_token ?? token.idToken,
      expiresAt: Math.floor(Date.now() / 1000) + refreshed.expires_in,
      // Keycloak rotates refresh tokens; keep the new one when present.
      refreshToken: refreshed.refresh_token ?? token.refreshToken,
      ...claimsFromAccessToken(refreshed.access_token),
      error: undefined,
    }
  } catch (error) {
    console.error("Failed to refresh Keycloak access token", error)
    // Drop the dead tokens: nothing may keep calling the backend with an
    // expired access token, and SessionErrorGuard signs the user out on
    // seeing the error flag.
    return {
      ...token,
      accessToken: undefined,
      idToken: undefined,
      refreshToken: undefined,
      expiresAt: undefined,
      error: "RefreshAccessTokenError",
    }
  }
}

const handler = NextAuth({
  providers: [
    KeycloakProvider({
      clientId: process.env.KEYCLOAK_ID!,
      clientSecret: process.env.KEYCLOAK_SECRET!,
      issuer: process.env.KEYCLOAK_ISSUER,
    })
  ],
  callbacks: {
    async jwt({ token, account }) {
      // Initial sign-in: `account` carries the freshly issued tokens.
      if (account) {
        return {
          ...token,
          accessToken: account.access_token,
          idToken: account.id_token,
          refreshToken: account.refresh_token,
          expiresAt: account.expires_at,
          ...claimsFromAccessToken(account.access_token),
        }
      }

      // Subsequent calls: reuse the access token while it's still valid
      // (30s clock-skew guard), otherwise refresh it.
      if (token.expiresAt && Date.now() < token.expiresAt * 1000 - 30_000) {
        return token
      }
      return refreshAccessToken(token)
    },
    async session({ session, token }) {
      session.accessToken = token.accessToken
      session.idToken = token.idToken
      session.roles = token.roles
      session.username = token.username
      session.displayName = token.displayName
      session.sub = token.sub
      session.error = token.error
      return session
    }
  }
})

export { handler as GET, handler as POST }
