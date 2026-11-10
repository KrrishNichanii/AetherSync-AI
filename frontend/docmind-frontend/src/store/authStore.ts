import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import type { UserDto } from '../types';
import { authApi } from '../services/authApi';
import { conversationApi } from '../services/conversationApi';
import { useDocumentStore } from './documentStore';
import { useConversationStore } from './conversationStore';

interface AuthState {
  user: UserDto | null;
  token: string | null;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, email: string, password: string) => Promise<UserDto>;
  logout: () => void;
  /** Clears the expired/invalid session but keeps the user's locally stored chats. */
  expireSession: () => void;
}

/** True when the JWT's `exp` claim is in the past (or the token is malformed). */
export function isTokenExpired(token: string, skewSeconds = 15): boolean {
  try {
    const payload = token.split('.')[1];
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
    return typeof json.exp !== 'number' || json.exp * 1000 <= Date.now() + skewSeconds * 1000;
  } catch {
    return true;
  }
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      user: null,
      token: null,
      isLoading: false,

      login: async (username: string, password: string) => {
        set({ isLoading: true });
        try {
          const response = await authApi.login({ username, password });
          set({
            user: response.user,
            token: response.accessToken,
            isLoading: false,
          });
        } catch (error) {
          set({ isLoading: false });
          throw error;
        }
      },

      register: async (username: string, email: string, password: string) => {
        set({ isLoading: true });
        try {
          const userDto = await authApi.register({ username, email, password });
          set({ isLoading: false });
          return userDto;
        } catch (error) {
          set({ isLoading: false });
          throw error;
        }
      },

      logout: () => {
        const currentUser = get().user;
        if (currentUser?.id) {
          conversationApi.clearUserData(currentUser.id);
        }
        // Also clear any docmind:* keys in localStorage
        try {
          const keysToRemove: string[] = [];
          for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (key && (key.startsWith('docmind:') || key.startsWith('docmind-chats-') || key.startsWith('docmind-pref-'))) {
              keysToRemove.push(key);
            }
          }
          keysToRemove.forEach((k) => localStorage.removeItem(k));
        } catch (e) {
          console.error('Error clearing localStorage on logout', e);
        }

        // Reset stores
        useDocumentStore.getState().reset();
        useConversationStore.getState().reset();

        set({ user: null, token: null, isLoading: false });
      },

      expireSession: () => {
        // Unlike logout(), do NOT wipe localStorage chat history: it is keyed per user
        // and reappears after signing in again.
        useDocumentStore.getState().reset();
        useConversationStore.getState().reset();
        set({ user: null, token: null, isLoading: false });
      },
    }),
    {
      name: 'docmind-auth',
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({ user: state.user, token: state.token }),
    }
  )
);
