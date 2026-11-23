import axios from "axios";
export interface Pool {
  id: string;
  name: string;
  code: string;
  status: string;
  resourceType: string;
  totalCapacity: number;
  allocatedCapacity: number;
  availableCapacity: number;
  maxLeaseSeconds: number;
}
export interface Team {
  id: string;
  name: string;
}
export interface Lease {
  id: string;
  ownerId: string;
  poolId: string;
  teamId: string;
  quantity: number;
  status: string;
  issuedAt: string;
  expiresAt: string;
}
export interface User {
  id: string;
  displayName: string;
  email: string;
  systemRole: string;
}
export interface Quota {
  quotaLimit: number;
  usedUnits: number;
  remainingUnits: number;
}
export const api = axios.create({
  baseURL:
    (
      import.meta.env.VITE_API_BASE_URL || "https://leasegrid.onrender.com"
    ).replace(/\/$/, "") + "/api/v1",
  timeout: 45000,
});
api.interceptors.request.use((c) => {
  const token = sessionStorage.token;
  if (token && !c.url?.match(/auth\/(login|register)/))
    c.headers.Authorization = "Bearer " + token;
  return c;
});
api.interceptors.response.use(
  (r) => r,
  (e) => {
    if (e.response?.status === 401 && e.config?.headers?.Authorization) {
      sessionStorage.removeItem("token");
      location.replace("/login");
    }
    return Promise.reject(
      new Error(
        e.response?.data?.detail ||
          e.response?.data?.code ||
          (e.response
            ? "Request failed (" + e.response.status + ")"
            : e.code === "ECONNABORTED" || e.code === "ETIMEDOUT"
              ? "The API timed out after 45 seconds. Try again shortly."
              : "The browser could not read the API response. Check your network connection and the backend CORS settings for " + location.origin + "."),
      ),
    );
  },
);
export async function catalog<T>(path: string) {
  const rows: T[] = [];
  let cursor: string | undefined;
  do {
    const { data }: { data: { items: T[]; nextCursor?: string } } =
      await api.get(path, { params: { limit: 50, cursor } });
    rows.push(...data.items);
    cursor = data.nextCursor;
  } while (cursor);
  return rows;
}
