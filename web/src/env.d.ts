/// <reference types="vite/client" />

declare const __APP_VERSION__: string;

interface ImportMetaEnv {
  /** The Firebase web app config (JSON or the console snippet). Empty: groups are off. */
  readonly VITE_FIREBASE_CONFIG?: string;
}
