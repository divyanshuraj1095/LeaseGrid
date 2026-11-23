import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Layers3, ArrowRight } from "lucide-react";
import { api } from "./api";
export function Auth({ register }: { register: boolean }) {
  const [error, set] = useState(""),
    [busy, wait] = useState(false),
    nav = useNavigate();
  return (
    <main className="auth">
      <div>
        <div className="brand">
          <Layers3 /> LeaseGrid
        </div>
        <p className="tag">INFRASTRUCTURE CONSOLE</p>
        <h1>{register ? "Create your account" : "Welcome back"}</h1>
        <p>
          {register
            ? "Connect to your team’s infrastructure workspace."
            : "Sign in to manage resource pools and leases."}
        </p>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const data = Object.fromEntries(new FormData(e.currentTarget));
            wait(true);
            set("");
            try {
              if (register) await api.post("/auth/register", data);
              const r = await api.post("/auth/login", {
                email: data.email,
                password: data.password,
              });
              sessionStorage.token = r.data.accessToken;
              nav("/overview", { replace: true });
            } catch (e) {
              set((e as Error).message);
            } finally {
              wait(false);
            }
          }}
        >
          {register && (
            <label>
              Display name
              <input name="displayName" required maxLength={120} />
            </label>
          )}
          <label>
            Email address
            <input type="email" name="email" required autoComplete="email" />
          </label>
          <label>
            Password
            <input
              name="password"
              type="password"
              required
              minLength={register ? 8 : 1}
              maxLength={72}
              autoComplete={register ? "new-password" : "current-password"}
            />
          </label>
          {error && <p role="alert">{error}</p>}
          <button disabled={busy}>
            {busy ? "Connecting…" : register ? "Create account" : "Sign in"}
            <ArrowRight size={16} />
          </button>
        </form>
        <Link to={register ? "/login" : "/register"}>
          {register ? "Sign in to existing account" : "Create an account"}
        </Link>
        <hr />
        <Link to="/lab">Explore the concurrency simulator →</Link>
      </div>
    </main>
  );
}
