import fs from "node:fs";
import path from "node:path";
import { CFG, PREFIX } from "./config.mjs";

export function personalEnv(source = process.env) {
  // The APK manages this versioned executable. agy 1.5.13 expects the literal "true".
  const env = { ...source, HOME: CFG.home, AGY_CLI_DISABLE_AUTO_UPDATE: "true" };
  for (const key of ["GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_GEMINI_BASE_URL", "GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_GENAI_USE_VERTEXAI", "AGY_ADC_AUTH", "CCAPP_KEY_FILE", "CCAPP_TOKEN_FILE"]) delete env[key];
  const cert = path.join(PREFIX, "etc", "tls", "cert.pem");
  if (PREFIX && fs.existsSync(cert)) env.SSL_CERT_FILE ||= cert;
  return env;
}
