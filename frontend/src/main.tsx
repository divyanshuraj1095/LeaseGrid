import { Summary } from "./Summary";
import { Skeleton } from "./components/ui/skeleton";
import { createRoot } from "react-dom/client";
import { useState, useEffect } from "react";
import {
  BrowserRouter,
  NavLink,
  Routes,
  Route,
  Navigate,
  useLocation,
  Link,
} from "react-router-dom";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import {
  Layers3,
  LayoutDashboard,
  Database,
  Timer,
  Users,
  Gauge,
  FlaskConical,
  Settings,
  Menu,
  Sun,
  Activity,
  Box,
  ArrowUpRight,
  RefreshCw,
} from "lucide-react";
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
} from "recharts";
import {
  api,
  catalog,
  type User,
  type Pool,
  type Team,
  type Lease,
} from "./api";
import { Auth } from "./Auth";
import { Manage } from "./Manage";
import { Lab } from "./Lab";
import "./style.css";
const client = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 30000 } },
});
const routes = [
  ["overview", "Overview", LayoutDashboard],
  ["pools", "Resource Pools", Database],
  ["leases", "Active Leases", Timer],
  ["teams", "Teams", Users],
  ["quotas", "Quota Management", Gauge],
  ["lab", "Concurrency Lab", FlaskConical],
  ["settings", "Settings", Settings],
] as const;
function Workspace() {
  const page = useLocation().pathname.slice(1) || "overview",
    session = !!sessionStorage.token,
    [collapsed, C] = useState(false),
    [light, H] = useState(localStorage.theme === "light");
  useEffect(() => {
    document.documentElement.classList.toggle("light", light);
    localStorage.theme = light ? "light" : "dark";
  }, [light]);
  const me = useQuery({
    queryKey: ["me"],
    queryFn: async () => (await api.get<User>("/auth/me")).data,
    enabled: session,
    refetchInterval: 60000,
  });
  const enabled = !!me.data && !["lab", "settings"].includes(page);
  const pq = useQuery({
      queryKey: ["pools"],
      queryFn: () => catalog<Pool>("/pools"),
      enabled,
    }),
    tq = useQuery({
      queryKey: ["teams"],
      queryFn: () => catalog<Team>("/teams"),
      enabled,
    }),
    lq = useQuery({
      queryKey: ["leases"],
      queryFn: () => catalog<Lease>("/leases"),
      enabled,
    });
  const pools = pq.data || [],
    teams = tq.data || [],
    leases = lq.data || [],
    total = pools.reduce((v, p) => v + p.totalCapacity, 0),
    used = pools.reduce((v, p) => v + p.allocatedCapacity, 0);
  const logout = () => {
    sessionStorage.removeItem("token");
    location.replace("/login");
  };
  useEffect(() => {
    if (!session) return;
    try {
      const exp = JSON.parse(
        atob(
          sessionStorage.token
            .split(".")[1]
            .replace(/-/g, "+")
            .replace(/_/g, "/"),
        ),
      ).exp;
      const t = setTimeout(logout, Math.max(0, exp * 1000 - Date.now()));
      return () => clearTimeout(t);
    } catch {
      logout();
    }
  }, [session]);
  if (!session && page !== "lab") return <Navigate to="/login" replace />;
  const current = routes.find((r) => r[0] === page);
  if (!current) return <Navigate to="/overview" replace />;
  const error = me.error || (enabled && (pq.error || lq.error || tq.error)),
    title = current[1];
  return (
    <div className={collapsed ? "shell compact" : "shell"}>
      <aside>
        <div className="brand">
          <Layers3 />
          <b>LeaseGrid</b>
        </div>
        <small>WORKSPACE</small>
        <nav>
          {routes
            .filter((r) => session || r[0] === "lab")
            .map(([path, label, Icon]) => (
              <NavLink title={label} to={"/" + path} key={path}>
                <Icon size={18} />
                <span>{label}</span>
              </NavLink>
            ))}
        </nav>
        <footer>
          <Activity size={16} /> Infrastructure console
        </footer>
      </aside>
      <div className="content">
        <header>
          <button
            className="icon"
            aria-label="Toggle sidebar"
            onClick={() => C(!collapsed)}
          >
            <Menu size={18} />
          </button>
          <span>Workspace　/　{title}</span>
          <div className="spacer" />
          <small>
            {me.error
              ? "Connection issue"
              : me.data
                ? "● Connected"
                : session
                  ? "Connecting…"
                  : "Local session"}
          </small>
          <button
            className="icon"
            aria-label="Toggle theme"
            onClick={() => H(!light)}
          >
            <Sun size={18} />
          </button>
          {session ? (
            <details>
              <summary>{me.data?.displayName || "Account"}</summary>
              <button onClick={logout}>Log out</button>
            </details>
          ) : (
            <Link to="/login">Sign in</Link>
          )}
        </header>
        <main>
          <div className="eyebrow">
            <i /> WORKSPACE CONSOLE
          </div>
          <h1>{page === "overview" ? "Infrastructure overview" : title}</h1>
          <p>
            {
              (
                {
                  overview:
                    "Capacity, utilization, and lease activity across your visible infrastructure.",
                  pools:
                    "Inspect capacity and manage available infrastructure.",
                  leases: "Track allocations and manage time-bound access.",
                  teams: "Manage workspace teams and membership.",
                  quotas: "Inspect and manage per-team resource limits.",
                  settings: "Manage your console preferences.",
                } as Record<string, string>
              )[page]
            }
          </p>
          {error ? (
            <section role="alert">
              {error.message}{" "}
              <button onClick={() => client.invalidateQueries()}>Retry</button>
            </section>
          ) : page === "lab" ? (
            <Lab />
          ) : !me.data || (enabled && [pq, tq, lq].some((q) => q.isPending)) ? (
            <section role="status">
              Loading workspace?
              <Skeleton className="h-24" />
            </section>
          ) : page === "settings" ? (
            <section>
              <h2>{me.data.displayName}</h2>
              <p>
                {me.data.email} · {me.data.systemRole}
              </p>
              <button onClick={() => H(!light)}>
                Switch to {light ? "dark" : "light"} mode
              </button>
            </section>
          ) : page === "overview" ? (
            <>
              <div className="toolbar">
                <Link className="primary" to="/leases">
                  Manage leases <ArrowUpRight size={16} />
                </Link>
                <button
                  className="secondary"
                  onClick={() => client.invalidateQueries()}
                >
                  <RefreshCw size={15} />
                  Refresh
                </button>
              </div>
              <div className="grid">
                {[
                  ["Resource pools", pools.length, Database],
                  [
                    "Active leases",
                    leases.filter(
                      (x) =>
                        x.status === "ACTIVE" &&
                        Date.parse(x.expiresAt) > Date.now(),
                    ).length,
                    Timer,
                  ],
                  ["Available capacity", total - used, Box],
                  [
                    "Utilization",
                    total ? Math.round((100 * used) / total) + "%" : "—",
                    Gauge,
                  ],
                ].map(([k, v, I]) => {
                  const Icon = I as typeof Box;
                  return (
                    <article key={String(k)}>
                      <Icon className="metricIcon" />
                      <small>{String(k)}</small>
                      <strong>{String(v)}</strong>
                    </article>
                  );
                })}
              </div>
              <div className="columns">
                <section>
                  <h3>Capacity by pool</h3>
                  <p>Allocated and available units</p>
                  {pools.length ? (
                    <ResponsiveContainer width="100%" height={220}>
                      <BarChart data={pools}>
                        <XAxis dataKey="code" />
                        <YAxis />
                        <Tooltip />
                        <Bar
                          dataKey="allocatedCapacity"
                          stackId="a"
                          fill="#3b82f6"
                        />
                        <Bar
                          dataKey="availableCapacity"
                          stackId="a"
                          fill="#39b9ab"
                        />
                      </BarChart>
                    </ResponsiveContainer>
                  ) : (
                    <p>No resource pools visible.</p>
                  )}
                </section>
                <section className="labCard">
                  <FlaskConical />
                  <small>INTERACTIVE LAB</small>
                  <h2>Concurrency Lab</h2>
                  <p>
                    Explore what happens when requests compete for the same
                    capacity.
                  </p>
                  <Link to="/lab">Open simulator →</Link>
                </section>
              </div>
              <Summary teams={teams} pools={pools} leases={leases} />
            </>
          ) : (
            <Manage
              key={page}
              page={page}
              user={me.data}
              pools={pools}
              teams={teams}
              leases={leases}
            />
          )}
        </main>
      </div>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(
  <QueryClientProvider client={client}>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Auth key="login" register={false} />} />
        <Route path="/register" element={<Auth key="register" register />} />
        <Route path="*" element={<Workspace />} />
      </Routes>
    </BrowserRouter>
  </QueryClientProvider>,
);
