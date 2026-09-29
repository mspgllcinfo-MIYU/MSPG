import { createRemoteJWKSet, jwtVerify } from "jose";

const FIREBASE_JWKS_URL =
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

let jwks: ReturnType<typeof createRemoteJWKSet> | undefined;

function getJwks() {
  if (!jwks) {
    jwks = createRemoteJWKSet(new URL(FIREBASE_JWKS_URL));
  }
  return jwks;
}

/**
 * Verifies a Firebase Authentication ID token per
 * https://firebase.google.com/docs/auth/admin/verify-id-tokens#verify_id_tokens_using_a_third-party_jwt_library
 * Returns the Firebase uid (the token's `sub` claim) on success.
 */
export async function verifyFirebaseIdToken(
  idToken: string,
  projectId: string,
): Promise<string> {
  const { payload } = await jwtVerify(idToken, getJwks(), {
    issuer: `https://securetoken.google.com/${projectId}`,
    audience: projectId,
  });

  if (typeof payload.sub !== "string" || payload.sub.length === 0) {
    throw new Error("invalid sub claim");
  }

  const now = Math.floor(Date.now() / 1000);
  if (typeof payload.auth_time !== "number" || payload.auth_time > now) {
    throw new Error("invalid auth_time claim");
  }

  return payload.sub;
}
