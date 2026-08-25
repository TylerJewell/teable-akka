import { IdPrefix } from '@teable/core';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Doc } from 'sharedb/lib/client';
import type { IInstanceState } from './reducer';

export interface IUseInstancesProps<T, R> {
  collection: string;
  initData?: T[];
  factory: (data: T, doc?: Doc<T>) => R;
  queryParams: unknown;
}

/**
 * Stream-fed replacement for the original's ShareDB-backed subscription.
 *
 * The original opens a SockJS connection and drives every collection through ShareDB
 * documents and operational transform. This port has no operation log to transform, so the
 * transport is server-sent events over the table's own stream: the first render comes from
 * the server-rendered `initData`, the collection is loaded once over the same snapshot
 * routes the original's client already calls, and a frame on the stream re-reads it.
 *
 * There is no adapter between the component and the stream. Deleting the EventSource below
 * leaves the view showing whatever it loaded once and never updating, which is RENDERING.md
 * R4's test for whether a front end was rewired or merely wrapped.
 *
 * What this gives up against the original is which cells changed: a frame says the table
 * moved, not what moved, so a subscriber re-reads the collection. That is a divergence and
 * is declared as one.
 */
export function useInstances<T, R extends { id: string }>({
  collection,
  factory,
  queryParams,
  initData,
}: IUseInstancesProps<T, R>): IInstanceState<R> {
  const [prefix, tableId] = collection.split('_');
  const [state, setState] = useState<IInstanceState<R>>({
    instances: initData ? initData.map((data) => factory(data)) : [],
    extra: undefined,
  });
  const factoryRef = useRef(factory);
  factoryRef.current = factory;

  const queryKey = useMemo(() => JSON.stringify(queryParams ?? null), [queryParams]);

  const load = useCallback(async () => {
    if (!tableId) {
      return;
    }
    const kind =
      prefix === IdPrefix.Record ? 'record' : prefix === IdPrefix.Field ? 'field' : 'view';
    const base = `/api/share/${tableId}/socket/${kind}`;

    let ids: string[];
    let extra: unknown;
    if (kind === 'record') {
      const response = await fetch(`${base}/doc-ids`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: queryKey === 'null' ? '{}' : queryKey,
      });
      if (!response.ok) return;
      const body = await response.json();
      ids = body.ids ?? [];
      extra = body.extra;
    } else {
      const response = await fetch(`${base}/doc-ids`);
      if (!response.ok) return;
      ids = (await response.json()).ids ?? [];
    }

    if (!ids.length) {
      setState({ instances: [], extra });
      return;
    }

    let snapshots: { id: string; data: T }[];
    if (kind === 'record') {
      const response = await fetch(`${base}/snapshot-bulk`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids }),
      });
      if (!response.ok) return;
      snapshots = await response.json();
    } else {
      const query = ids.map((id) => `ids[]=${encodeURIComponent(id)}`).join('&');
      const response = await fetch(`${base}/snapshot-bulk?${query}`);
      if (!response.ok) return;
      snapshots = await response.json();
    }

    const byId = new Map(snapshots.map((snapshot) => [snapshot.id, snapshot.data]));
    const ordered = ids
      .map((id) => byId.get(id))
      .filter((data): data is T => data !== undefined)
      .map((data) => factoryRef.current(data));
    setState({ instances: ordered, extra });
  }, [prefix, tableId, queryKey]);

  useEffect(() => {
    let closed = false;
    // The frame most recently acted on. A stream that has been idle for a while is closed
    // by something between here and the service, and the browser reopens it -- whose first
    // frame is current state, identical to the last one seen. Re-reading on it turns every
    // reconnection into a request, which is a poll wearing a stream's clothes: measured at
    // one round of six requests every ten seconds with nobody touching the page.
    let lastFrame: string | null = null;
    void load();
    if (!tableId) {
      return;
    }
    const stream = new EventSource(`/api/share/${tableId}/stream`);
    stream.onmessage = (event) => {
      if (closed || event.data === lastFrame) {
        return;
      }
      lastFrame = event.data;
      void load();
    };
    return () => {
      closed = true;
      stream.close();
    };
  }, [load, tableId]);

  return state;
}
