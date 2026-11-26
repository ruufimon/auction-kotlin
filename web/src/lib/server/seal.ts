import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";

/** AES-256-GCM sealing for cookie values. Tampered or foreign values open to null. */
export function sealer(secret: string) {
  const key = createHash("sha256").update(`gavel-web-session:${secret}`).digest();
  return {
    seal(value: unknown): string {
      const iv = randomBytes(12);
      const cipher = createCipheriv("aes-256-gcm", key, iv);
      const body = Buffer.concat([cipher.update(JSON.stringify(value), "utf8"), cipher.final()]);
      return Buffer.concat([iv, cipher.getAuthTag(), body]).toString("base64url");
    },
    open<T>(sealed: string | undefined): T | null {
      if (!sealed) return null;
      try {
        const raw = Buffer.from(sealed, "base64url");
        if (raw.length < 29) return null;
        const decipher = createDecipheriv("aes-256-gcm", key, raw.subarray(0, 12));
        decipher.setAuthTag(raw.subarray(12, 28));
        const text = Buffer.concat([decipher.update(raw.subarray(28)), decipher.final()]).toString("utf8");
        return JSON.parse(text) as T;
      } catch {
        return null;
      }
    },
  };
}
