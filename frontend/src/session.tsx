import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, getApiKey, setApiKey, UNAUTHORIZED_EVENT } from './api/client';
import type { DemoSession } from './api/types';

interface Session {
  apiKey: string | null;
  startDemo: () => Promise<void>;
  signOut: () => void;
}

const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [apiKey, setKey] = useState<string | null>(() => getApiKey());

  const signOut = useCallback(() => {
    setApiKey(null);
    setKey(null);
    queryClient.clear();
  }, [queryClient]);

  // The backend issues a fresh demo key on restart; a stale key yields 401 and returns us to the gate.
  useEffect(() => {
    window.addEventListener(UNAUTHORIZED_EVENT, signOut);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, signOut);
  }, [signOut]);

  const startDemo = useCallback(async () => {
    const session = await api<DemoSession>('/api/v1/demo/session', { method: 'POST' });
    setApiKey(session.apiKey);
    setKey(session.apiKey);
  }, []);

  const value = useMemo(() => ({ apiKey, startDemo, signOut }), [apiKey, startDemo, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Session {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be used inside SessionProvider');
  return ctx;
}
