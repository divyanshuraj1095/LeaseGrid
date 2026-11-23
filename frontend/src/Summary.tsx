import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, type Team, type Pool, type Quota, type Lease } from "./api";
export function Countdown({ end }: { end: string }) {
  const [now, set] = useState(Date.now);
  useEffect(() => {
    const id = setInterval(() => set(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const seconds = Math.max(0, Math.ceil((Date.parse(end) - now) / 1000));
  return (
    <small>
      {seconds
        ? Math.floor(seconds / 3600) +
          "h " +
          Math.floor((seconds % 3600) / 60) +
          "m " +
          (seconds % 60) +
          "s remaining"
        : "Expired"}
    </small>
  );
}
export function Summary({
  teams,
  pools,
  leases,
}: {
  teams: Team[];
  pools: Pool[];
  leases: Lease[];
}) {
  const [team, T] = useState(teams[0]?.id || ""),
    [pool, P] = useState(pools[0]?.id || "");
  const quota = useQuery({
    queryKey: ["quota", team, pool],
    queryFn: async () =>
      (await api.get<Quota>("/teams/" + team + "/quotas/" + pool)).data,
    enabled: !!team && !!pool,
    retry: false,
  });
  return (
    <>
      <div className="columns">
        <section>
          <h3>Pool status</h3>
          {pools.slice(0, 6).map((p) => (
            <div className="health" key={p.id}>
              <span>{p.name}</span>
              <small className="badge" data-status={p.status}>
                {p.status}
              </small>
              <progress
                aria-label={p.name + " utilization"}
                value={p.allocatedCapacity}
                max={p.totalCapacity}
              />
            </div>
          ))}
          {!pools.length && <p>No visible pools.</p>}
        </section>
        <section>
          <h3>Team quota usage</h3>
          {teams.length && pools.length ? (
            <>
              <div className="toolbar">
                <select
                  aria-label="Team"
                  value={team}
                  onChange={(e) => T(e.target.value)}
                >
                  {teams.map((x) => (
                    <option value={x.id} key={x.id}>
                      {x.name}
                    </option>
                  ))}
                </select>
                <select
                  aria-label="Pool"
                  value={pool}
                  onChange={(e) => P(e.target.value)}
                >
                  {pools.map((x) => (
                    <option value={x.id} key={x.id}>
                      {x.name}
                    </option>
                  ))}
                </select>
              </div>
              {quota.data ? (
                <>
                  <progress
                    aria-label="Quota usage"
                    value={quota.data.usedUnits}
                    max={quota.data.quotaLimit || 1}
                  />
                  <p>
                    {quota.data.usedUnits} of {quota.data.quotaLimit} units
                  </p>
                </>
              ) : (
                <p>{quota.error?.message || "Loading quota…"}</p>
              )}
            </>
          ) : (
            <p>No team and pool available.</p>
          )}
        </section>
      </div>
      <section>
        <h3>Recent lease activity</h3>
        {leases.length ? (
          <table>
            <thead>
              <tr>
                {["Pool", "Units", "Status", "Issued"].map((x) => (
                  <th key={x}>{x}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {leases.slice(0, 6).map((x) => (
                <tr key={x.id}>
                  <td>
                    {pools.find((p) => p.id === x.poolId)?.name || x.poolId}
                  </td>
                  <td>{x.quantity}</td>
                  <td>
                    <span className="badge" data-status={x.status}>
                      {x.status}
                    </span>
                  </td>
                  <td>{new Date(x.issuedAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p>No leases recorded.</p>
        )}
      </section>
    </>
  );
}
