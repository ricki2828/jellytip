export interface AlternateChannel {
  id: string;
  name: string;
}

/** Only surface a live stall while playback is active and the page is foregrounded. */
export function shouldSurfaceLiveStall(now: number, lastMove: number, threshold: number, paused: boolean, hidden: boolean, quietUntil: number): boolean {
  return !paused && !hidden && now - lastMove >= threshold && now > quietUntil;
}

/** When switching, retain the other candidates and put the channel we left last. */
export function alternatesAfterSwitch(alternates: AlternateChannel[], current: AlternateChannel, selectedId: string): AlternateChannel[] {
  return [...alternates.filter((channel) => channel.id !== selectedId && channel.id !== current.id), current];
}
