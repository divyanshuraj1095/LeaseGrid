# LeaseGrid console
Use Node 24 and run `npm ci` followed by `npm run dev` in this directory. For Vercel choose frontend as the root, Vite as the preset, npm run build as the build command and dist as output. Set VITE_API_BASE_URL to https://leasegrid.onrender.com before building.

## Cross-origin access
The API defaults to the local Vite and production Vercel origins. On Render, set `LEASEGRID_CORS_ORIGINS=http://localhost:5173,https://lease-grid-seven.vercel.app` and redeploy. This variable replaces the entire default list. Use exact origins without trailing slashes. The frontend uses bearer tokens and does not require cross-origin cookies.

The simulator at /lab works without a backend session. Requests model an initially empty active pool with one team, valid durations, distinct idempotency keys and queueing disabled. Results illustrate serialized admission decisions. Production performance and concurrency are not measured.
