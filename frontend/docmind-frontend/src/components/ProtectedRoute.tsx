import React, { useEffect } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { isTokenExpired, useAuthStore } from '../store/authStore';

interface ProtectedRouteProps {
  children?: React.ReactNode;
}

const ProtectedRoute: React.FC<ProtectedRouteProps> = ({ children }) => {
  const token = useAuthStore((state) => state.token);
  const expireSession = useAuthStore((state) => state.expireSession);
  const expired = token ? isTokenExpired(token) : false;

  // A token persisted from an earlier visit may have expired; clear it so the login
  // page does not bounce straight back here.
  useEffect(() => {
    if (expired) expireSession();
  }, [expired, expireSession]);

  if (!token || expired) {
    return <Navigate to={expired ? '/login?expired=1' : '/login'} replace />;
  }

  return children ? <>{children}</> : <Outlet />;
};

export default ProtectedRoute;
