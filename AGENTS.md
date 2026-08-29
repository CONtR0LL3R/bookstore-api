# Notes for AI assistants working in this repo

## How to explain things to this user
- Prefers **simple language + real-life analogies** (guards/doorman, restaurant kitchen).
- One concept at a time; avoid jargon-dense tables unless asked.

## Active course: Istio fault injection (hands-on)
Teaching vehicle: `bookstore-chart/templates/istio-config.yaml`
(dev-only template, gated by `.Values.bookstore_v2`; deployed via Argo CD, auto-sync on, tracked branch: `argo`)

### Progress
1. ✅ **Lesson 1 — abort**: `abort: 100% → httpStatus: 503` on gateway VirtualService.
   Learned: fault runs BEFORE routing (weights ignored); enforced by ingress-gateway Envoy,
   Spring pods receive nothing; Kiali edge turns red while destination node stays green.
2. ✅ **Lesson 2 — delay**: `delay: 100%, fixedDelay: 5s` — **currently LIVE in the file**.
   Learned: slow ≠ failing (200 OK but ~5s); proved scope by bypassing gateway
   (direct call to `bookstore-service.dev.svc.cluster.local/books` ≈ 0.05s);
   bonus: pairing `timeout: 2s` with 5s delay yields 504s (slow dependency + timeout pattern).
3. ✅ **Lesson 3 — percentage fault**: `delay: percentage.value: 20, fixedDelay: 5s` — realistic flakiness.
   Learned: percentage = *probability per request*, not a count; single request proves nothing —
   you must sample MANY requests and tally the timing distribution (e.g. 200–300 → expect ≈20%
   in the 5s class, rest ≈0s). Verified via a PowerShell loop capturing `%{time_total}`.
   Also: `curl.exe -w "%{time_total}"` prints timing; `-o NUL` drops the body; `-s` silences progress.
4. ✅ **Lesson 4 — header-triggered chaos**: two-rule split on `match: headers.x-chaos: exact: "on"`.
   Learned: this is the *deterministic* chaos switch (vs Lesson 3's randomness). Rule 1 fires the
   5s fault ONLY when header `x-chaos: on`; **rule order matters** — Istio evaluates top-to-bottom,
   so the chaos rule must come FIRST, otherwise a fallback route above it swallows every request.
   No-header requests fall through to Rule 2 (plain route, no fault) at line ~61.
5. Optional **Lesson 5**: move fault behind Helm `.Values.faultInjection.*` (env-scoped chaos).

### Setup facts (verified in this cluster)
- Test command: `curl -H "Host: dev.bookstore.local" http://127.0.0.1/books -w "%{http_code} %{time_total}s\n"`
  (Since `dev.bookstore.local` is mapped in the hosts file, `curl http://dev.bookstore.local/books
  -w "%{http_code} %{time_total}s\n"` works too — Host header is then sent implicitly. The
  `-H "Host:..."` + raw-IP form is only needed on boxes without that DNS entry.)
- Chaos test: add `-H "x-chaos: on"` to trigger the delay deterministically.
- Gateway LB external IP resolves to `127.0.0.1`.
- Argo CD apps: `bookstore-dev|staging|qa|prod` in `argocd` namespace; only dev renders istio-config.yaml.
- Incident history: pushing a fault as its own `http:` rule (without `route:`) was rejected by
  Istio's validating webhook ("HTTP route, redirect or direct_response is required") → app stuck
  OutOfSync until fix pushed. Good teaching example of admission-time validation.

### Mental models taught (user found these helpful)
- Guards analogy: doorman = ingress-gateway Envoy; bodyguards = sidecar Envoys.
- Gateway + VirtualService notes both address the doorman; DestinationRule briefs everyone.
- istiod = HQ that translates YAML → per-proxy Envoy config via xDS (gateway VS → gateway only;
  mesh VS → caller sidecars).
