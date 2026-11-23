import { useEffect, useState } from "react";
import { FlaskConical, Play } from "lucide-react";
export function Lab() {
  const [config, set] = useState([32, 24, 16, 3]),
    [run, start] = useState<number[] | null>(null),
    [step, tick] = useState(0);
  useEffect(() => {
    if (!run || step >= run[2]) return;
    const t = setTimeout(() => tick(step + 1), 110);
    return () => clearTimeout(t);
  }, [run, step]);
  const [cap, quota, count, units] = run || config,
    success = Math.min(step, Math.floor(Math.min(cap, quota) / units)),
    used = success * units,
    quotaHit = quota - used < units;
  return (
    <>
      <p className="tag">
        <FlaskConical size={14} /> CLIENT-SIDE SIMULATION
      </p>
      <h2>Allocation simulator</h2>
      <p>
        Watch concurrent requests compete for a shared pool. This local model
        checks team quota before capacity and sends no API traffic.
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          tick(0);
          start([...config]);
        }}
      >
        <div className="grid">
          {[
            "Pool capacity",
            "Team quota",
            "Concurrent requests",
            "Units per request",
          ].map((s, i) => (
            <label key={s}>
              {s}
              <input
                type="number"
                min="1"
                max={i === 2 ? 100 : 100000}
                required
                value={config[i] || ""}
                onChange={(e) =>
                  set(
                    config.map((n, j) =>
                      i === j ? Number(e.target.value) : n,
                    ),
                  )
                }
              />
            </label>
          ))}
        </div>
        <button>
          <Play size={15} />
          Run simulation
        </button>
      </form>
      <section>
        <h3>Request flow</h3>
        <div className="flow">
          {Array.from({ length: Math.min(100, Math.max(0, count)) }, (_, i) => (
            <i
              key={i}
              title={"Request " + (i + 1)}
              style={{
                background:
                  i >= step
                    ? "var(--line)"
                    : i < success
                      ? "#34d399"
                      : "#fb7185",
              }}
            />
          ))}
        </div>
        <div className="grid">
          {Object.entries({
            Successful: success,
            "Quota rejections": quotaHit ? step - success : 0,
            "Capacity rejections": quotaHit ? 0 : step - success,
            "Unexpected errors": 0,
            "Allocated units": used,
          }).map(([k, v]) => (
            <article key={k}>
              <small>{k}</small>
              <strong>{v}</strong>
            </article>
          ))}
        </div>
      </section>
    </>
  );
}
