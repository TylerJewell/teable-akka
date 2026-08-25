import { useMemo } from 'react';
import type { Connection } from 'sharedb/lib/client';

export function getWsPath() {
  return `${window.location.origin}/socket`;
}

/**
 * The original opens a SockJS connection here and hands it to ShareDB. This port feeds every
 * view from a server-sent event stream instead (see `useInstances`), so nothing opens a
 * socket and nothing retries one -- the original's handshake retries every second or two
 * while a socket is unavailable, which is the repeat traffic RENDERING.md R1.1 forbids.
 *
 * `connected` is reported true because there is nothing that can be disconnected: the
 * stream's own reconnection is the browser's, and a view's freshness is the stream's
 * business rather than this hook's.
 */
export const useConnection = () => {
  return useMemo(() => ({ connection: undefined as unknown as Connection, connected: true }), []);
};
