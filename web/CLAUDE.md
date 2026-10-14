# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

This is the **Next.js 16 (App Router) frontend** of the AISmartStore system. It lives in the `source_v2` project root as `web/`; runtime env vars (`KEYCLOAK_ISSUER`, `NEXTAUTH_*`, `KEYCLOAK_ID/SECRET`, `NEXT_PUBLIC_KEYCLOAK_ISSUER`) are provided by the root `docker-compose*.yml`.

**Heed the imported `AGENTS.md` above**: this Next.js version diverges from training data — read the relevant guide in `node_modules/next/dist/docs/` before writing web code.

## Commands

```bash
npm run dev      # next dev (Turbopack)
npm run build    # next build — includes typecheck; configured output: "standalone" for Docker
npm run lint     # eslint
```

## Auth model (NextAuth + Keycloak)

Authentication is the main non-trivial concern here. The provider and callbacks live in `src/app/api/auth/[...nextauth]/route.ts`:

- Login uses `KeycloakProvider` (authorization code + PKCE). The `jwt` callback (fires with `account` only on initial sign-in) decodes the Keycloak access token's `realm_access.roles` and stashes the known roles onto the token; the `session` callback exposes them as `session.roles`.
- `src/lib/roles.ts` mirrors the backend's role slugs (without the backend-only `realm:` prefix) and provides `hasRole(session, role)` for UI gating.
- `src/lib/jwt.ts` is a dependency-free base64url decode of already-verified claims (do not add a JWT lib for this).
- **`src/types/next-auth.d.ts` must declare any custom session/JWT field** (`accessToken`, `idToken`, `roles`). NextAuth's types don't include them, and `next build` fails on the missing property otherwise — this is a real failure mode, not cosmetic.

## Environment gotchas

- **`NEXT_PUBLIC_*` are inlined at build time**, so `NEXT_PUBLIC_KEYCLOAK_ISSUER` is passed to the image as a Docker **build arg** (not just a runtime env var) by the root compose. A runtime-only value won't reach the browser bundle.
- **Don't change the Keycloak issuer host.** Both browser and containers must reach Keycloak via the same hostname (`keycloak.localtest.me`) or login fails with an `iss` mismatch. This is set at the compose level; the web code just reads `process.env.KEYCLOAK_ISSUER` / `NEXT_PUBLIC_KEYCLOAK_ISSUER`. See the root `docs/` (`01-sa/architecture/auth-session.md`).
