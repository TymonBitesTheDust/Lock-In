import { UNKNOWN_BOOT, type TimePoint } from '../src/core/timer';

export const MINUTE = 60_000;

/** A controllable device clock: sleep, wall-clock jumps and restarts on demand. Same as the Kotlin FakeClock. */
export class FakeClock {
  constructor(
    private elapsedMs = 1_000_000,
    private wallMs = 1_760_000_000_000,
    private bootCount = 7,
  ) {}

  now = (): TimePoint => ({ elapsedMs: this.elapsedMs, wallMs: this.wallMs, bootCount: this.bootCount });

  advance(ms: number) {
    this.elapsedMs += ms;
    this.wallMs += ms;
  }

  advanceMinutes(minutes: number) {
    this.advance(minutes * MINUTE);
  }

  jumpWallClock(deltaMs: number) {
    this.wallMs += deltaMs;
  }

  reboot(offForMs: number, uptimeAfterBootMs = 30_000, countBoots = true) {
    this.wallMs += offForMs + uptimeAfterBootMs;
    this.elapsedMs = uptimeAfterBootMs;
    this.bootCount = countBoots ? this.bootCount + 1 : UNKNOWN_BOOT;
  }

  forgetBootCount() {
    this.bootCount = UNKNOWN_BOOT;
  }
}
