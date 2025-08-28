import React, { createContext, useContext, useRef, useEffect } from 'react';
import { connectWebSocket } from '../api';

const WebSocketContext = createContext();

export function WebSocketProvider({ children, onInit, onSpeed, onTorrent }) {
  const wsRef = useRef(null);

  useEffect(() => {
    wsRef.current = connectWebSocket({
      onInit: onInit || (() => {}),
      onSpeed: onSpeed || (() => {}),
      onTorrent: onTorrent || (() => {})
    });
    return () => {
      if (wsRef.current && wsRef.current.disconnect) wsRef.current.disconnect();
    };
  }, []);

  return (
    <WebSocketContext.Provider value={wsRef.current}>
      {children}
    </WebSocketContext.Provider>
  );
}

export function useWebSocket() {
  return useContext(WebSocketContext);
}
