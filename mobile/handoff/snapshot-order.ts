import type {Snapshot} from './types';

/** Full snapshots share this ordering across SSE, polling and mutation refreshes. */
export function selectSnapshot(previous: Snapshot | null, incoming: Snapshot, tripId: string): Snapshot | null {
 if (incoming.room.roomId !== tripId || !Number.isSafeInteger(incoming.eventId) || incoming.eventId < 0 || !Number.isFinite(Date.parse(incoming.serverTime))) return previous;
 if (!previous || previous.room.roomId !== tripId) return incoming;
 if (previous.room.status === 'closed' && incoming.room.status !== 'closed') return previous;
 if (incoming.eventId < previous.eventId) return previous;
 if (incoming.eventId === previous.eventId) {
  const before = Date.parse(previous.serverTime), after = Date.parse(incoming.serverTime);
  if (after < before) return previous;
  // Python timestamps include microseconds, which Date.parse truncates to milliseconds.
  const fraction = (time: string) => (time.match(/\.(\d+)/)?.[1] || '').padEnd(9, '0');
  if (after === before && fraction(incoming.serverTime) < fraction(previous.serverTime)) return previous;
 }
 return incoming;
}
