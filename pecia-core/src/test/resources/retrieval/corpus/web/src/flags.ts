import flags from "../../config/feature-flags.json";

type Flag = { enabled_for: string; rollout_percent: number };

/** Returns whether a flag is on for the current visitor, using a stable bucket from 0 to 99. */
export function isEnabled(name: string, bucket = visitorBucket()): boolean {
  const flag = (flags.flags as Record<string, Flag>)[name];
  if (!flag) {
    return false;
  }
  return flag.enabled_for === "everyone" || bucket < flag.rollout_percent;
}

function visitorBucket(): number {
  const id = localStorage.getItem("visitor-id") ?? "";
  let hash = 0;
  for (const char of id) {
    hash = (hash * 31 + char.charCodeAt(0)) % 100;
  }
  return hash;
}
