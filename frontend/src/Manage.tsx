import { Countdown } from "./Summary";
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api,
  catalog,
  type User,
  type Team,
  type Pool,
  type Lease,
  type Quota,
} from "./api";
type Props = {
  page: string;
  user: User;
  teams: Team[];
  pools: Pool[];
  leases: Lease[];
};
export function Manage({ page, user, teams, pools, leases }: Props) {
  const admin = user.systemRole === "ADMIN",
    [team, T] = useState(teams[0]?.id || ""),
    [pool, P] = useState(pools[0]?.id || ""),
    [op, O] = useState(""),
    [lease, L] = useState(""),
    [search, S] = useState(""),
    [key, K] = useState(() => crypto.randomUUID()),
    [notice, N] = useState(""),
    client = useQueryClient();
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    if (op) dialog.current?.showModal();
  }, [op]);
  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => N(""), 4500);
    return () => clearTimeout(t);
  }, [notice]);
  const members = useQuery({
    queryKey: ["members", team],
    queryFn: () =>
      catalog<{ userId: string; teamRole: string }>(
        "/teams/" + team + "/members",
      ),
    enabled: page === "teams" && !!team,
  });
  const quota = useQuery({
    queryKey: ["quota", team, pool],
    queryFn: async () =>
      (await api.get<Quota>("/teams/" + team + "/quotas/" + pool)).data,
    enabled: page === "quotas" && !!team && !!pool,
    retry: false,
  });
  const mutation = useMutation({
    mutationFn: async (d: Record<string, FormDataEntryValue>) => {
      switch (op) {
        case "Create pool":
          return api.post("/pools", d);
        case "Edit pool":
          return api.patch("/pools/" + pool, d);
        case "Create team":
          return api.post("/teams", d);
        case "Add member":
          return api.post("/teams/" + team + "/members", d);
        case "Set quota":
          return api.put("/teams/" + team + "/quotas/" + pool, d);
        case "Allocate":
          return api.post(
            "/allocations",
            {
              teamId: team,
              poolId: pool,
              quantity: Number(d.quantity),
              durationSeconds: Number(d.durationSeconds),
              queueIfUnavailable: false,
            },
            { headers: { "Idempotency-Key": key } },
          );
        case "Release":
          return api.post("/leases/" + lease + "/release");
        case "Renew":
          return api.post("/leases/" + lease + "/renew", {
            requestedExpiresAt: new Date(String(d.expiry)).toISOString(),
          });
      }
    },
    onSuccess: () => {
      N(op + " completed");
      O("");
      K(crypto.randomUUID());
      client.invalidateQueries();
    },
  });
  const action = (name: string) => (
    <button
      disabled={mutation.isPending}
      onClick={() => {
        O(name);
        N("");
        mutation.reset();
      }}
    >
      {name}
    </button>
  );
  const field = (
    name: string,
    title: string,
    type = "text",
    min?: number,
    max?: number,
    value?: string | number,
  ) => (
    <label>
      {title}
      <input
        required
        name={name}
        type={type}
        min={min}
        max={max}
        defaultValue={value}
      />
    </label>
  );
  const select = (
    label: string,
    v: string,
    fn: (s: string) => void,
    items: Team[],
  ) => (
    <label>
      {label}
      <select value={v} onChange={(e) => fn(e.target.value)}>
        {items.map((x) => (
          <option key={x.id} value={x.id}>
            {x.name}
          </option>
        ))}
      </select>
    </label>
  );
  const selected = pools.find((x) => x.id === pool);
  return (
    <>
      {notice && <p role="status">{notice}</p>}
      {page === "pools" && (
        <>
          <div className="toolbar">
            <input
              aria-label="Search resource pools"
              placeholder="Search resource pools…"
              value={search}
              onChange={(e) => S(e.target.value)}
            />
            {admin && action("Create pool")}
          </div>
          <section>
            <table>
              <thead>
                <tr>
                  {[
                    "Pool",
                    "Capacity",
                    "Allocated",
                    "Available",
                    "Utilization",
                  ].map((k) => (
                    <th key={k}>{k}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {pools
                  .filter((x) =>
                    (x.name + x.code)
                      .toLowerCase()
                      .includes(search.toLowerCase()),
                  )
                  .map((x) => (
                    <tr key={x.id}>
                      <td>
                        <button className="link" onClick={() => P(x.id)}>
                          {x.name}
                        </button>
                      </td>
                      <td>{x.totalCapacity}</td>
                      <td>{x.allocatedCapacity}</td>
                      <td>{x.availableCapacity}</td>
                      <td>
                        <progress
                          value={x.allocatedCapacity}
                          max={x.totalCapacity}
                        />
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
            {!pools.length && <p>No visible pools.</p>}
          </section>
          {selected && (
            <section>
              <small>POOL DETAILS</small>
              <h2>{selected.name}</h2>
              <p>
                {selected.code} · {selected.resourceType} · {selected.status} ·
                Maximum lease {selected.maxLeaseSeconds}s
              </p>
              {admin && action("Edit pool")}
            </section>
          )}
        </>
      )}
      {["teams", "quotas"].includes(page) && (
        <div className="toolbar">
          {select("Team", team, T, teams)}
          {page !== "teams" && select("Resource pool", pool, P, pools)}
        </div>
      )}
      {page === "teams" && (
        <section>
          <h3>Team membership</h3>
          {members.error?.message}
          {members.data?.map((m) => (
            <p key={m.userId}>
              {m.userId} · {m.teamRole}
            </p>
          ))}
          {admin && action("Create team")}
          {team &&
            (admin ||
              members.data?.some(
                (m) => m.userId === user.id && m.teamRole !== "MEMBER",
              )) &&
            action("Add member")}
        </section>
      )}
      {page === "quotas" && (
        <section>
          <h3>Team allocation limit</h3>
          {quota.data ? (
            <>
              <strong>
                {quota.data.usedUnits} / {quota.data.quotaLimit}
              </strong>
              <progress
                value={quota.data.usedUnits}
                max={quota.data.quotaLimit || 1}
              />
              <p>
                {quota.data.remainingUnits} units remaining{" "}
                {quota.data.usedUnits >= quota.data.quotaLimit * 0.8
                  ? "· Near or at limit"
                  : ""}
              </p>
            </>
          ) : (
            <p>
              {quota.error?.message ||
                "Select a team and resource pool to inspect quota."}
            </p>
          )}
          {admin && team && pool && action("Set quota")}
        </section>
      )}
      {page === "leases" && (
        <>
          {team && pool && action("Allocate")}
          <section>
            <h3>Lease inventory</h3>
            {!leases.length && <p>No visible leases.</p>}
            {leases.map((x) => (
              <article key={x.id}>
                <small className="badge" data-status={x.status}>
                  {x.status}
                </small>
                {x.status === "ACTIVE" && <Countdown end={x.expiresAt} />}
                <h3>
                  {pools.find((p) => p.id === x.poolId)?.name || x.poolId}
                </h3>
                <p>
                  {x.quantity} units · Expires{" "}
                  {new Date(x.expiresAt).toLocaleString()}
                </p>
                {x.status === "ACTIVE" && (admin || x.ownerId === user.id) && (
                  <span onClick={() => L(x.id)}>
                    {action("Renew")}
                    {action("Release")}
                  </span>
                )}
              </article>
            ))}
          </section>
        </>
      )}
      {op && (
        <dialog
          ref={dialog}
          aria-label={op}
          onCancel={(e) => {
            if (mutation.isPending) e.preventDefault();
            else O("");
          }}
        >
          <form
            key={op}
            onSubmit={(e) => {
              e.preventDefault();
              mutation.mutate(
                Object.fromEntries(new FormData(e.currentTarget)),
              );
            }}
          >
            <h3>{op}</h3>
            {op === "Allocate" && (
              <>
                {select("Team", team, T, teams)}
                {select("Pool", pool, P, pools)}
              </>
            )}
            {op.includes("pool") && (
              <>
                {field(
                  "name",
                  "Pool name",
                  "text",
                  undefined,
                  undefined,
                  op === "Edit pool" ? selected?.name : "",
                )}
                {field(
                  "totalCapacity",
                  "Total capacity",
                  "number",
                  1,
                  undefined,
                  op === "Edit pool" ? selected?.totalCapacity : 100,
                )}
                {field(
                  "maxLeaseSeconds",
                  "Maximum lease (seconds)",
                  "number",
                  60,
                  604800,
                  op === "Edit pool" ? selected?.maxLeaseSeconds : 3600,
                )}
                {op === "Create pool" ? (
                  <>
                    {field("code", "Pool code")}
                    <label>
                      Resource type
                      <select name="resourceType">
                        {["GPU", "CPU_SLOT", "TEST_ENV", "GENERIC"].map((x) => (
                          <option key={x}>{x}</option>
                        ))}
                      </select>
                    </label>
                  </>
                ) : (
                  <label>
                    Status
                    <select name="status" defaultValue={selected?.status}>
                      {["ACTIVE", "DRAINING", "DISABLED"].map((x) => (
                        <option key={x}>{x}</option>
                      ))}
                    </select>
                  </label>
                )}
              </>
            )}
            {op === "Create team" && field("name", "Team name")}
            {op === "Add member" && (
              <>
                {field("userId", "User UUID")}
                <label>
                  Team role
                  <select name="teamRole">
                    {["MEMBER", "ADMIN", "OWNER"].map((x) => (
                      <option key={x}>{x}</option>
                    ))}
                  </select>
                </label>
              </>
            )}
            {op === "Set quota" &&
              field("quotaLimit", "Quota limit", "number", 0)}
            {op === "Allocate" && (
              <>
                {field("quantity", "Requested units", "number", 1)}
                {field(
                  "durationSeconds",
                  "Duration (seconds)",
                  "number",
                  60,
                  selected?.maxLeaseSeconds || 604800,
                )}
                <label>
                  Idempotency key
                  <input
                    required
                    value={key}
                    onChange={(e) => K(e.target.value as typeof key)}
                  />
                </label>
              </>
            )}
            {op === "Renew" &&
              field("expiry", "Requested expiration", "datetime-local")}
            {op === "Release" && (
              <p>Release lease {lease} and return its units to the pool?</p>
            )}
            {mutation.error && <p role="alert">{mutation.error.message}</p>}
            <button disabled={mutation.isPending}>
              Confirm {op.toLowerCase()}
            </button>
            <button
              type="button"
              disabled={mutation.isPending}
              onClick={() => O("")}
            >
              Cancel
            </button>
          </form>
        </dialog>
      )}
    </>
  );
}
