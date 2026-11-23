# LeaseGrid console
Use Node 24 and run `npm ci` followed by `npm run dev` in this directory. For Vercel choose frontend as the root, Vite as the preset, npm run build as the build command and dist as output. Set VITE_API_BASE_URL to https://leasegrid.onrender.com before building.

## Cross-origin access
Spring Security allows `http://localhost:5173` by default on the API. For Vercel, set the Render backend variable `LEASEGRID_CORS_ORIGINS` to a comma-separated list such as `http://localhost:5173,https://your-project.vercel.app`. The list replaces the default. Use exact origins without trailing slashes. Deploy the updated backend before testing cross-origin sign-in.

The simulator at /lab works without a backend session. Requests model an initially empty active pool with one team, valid durations, distinct idempotency keys and queueing disabled. Results illustrate serialized admission decisions. Production performance and concurrency are not measured.
