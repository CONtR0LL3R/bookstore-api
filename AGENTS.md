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
3. ⏭ **Next — Lesson 3**: percentage-based fault (`percentage.value: 20`) — realistic flakiness;
   verify by counting failures over ~20 requests.
4. Pending **Lesson 4**: header-triggered chaos (`match:` on `x-chaos: on`, two-rule split).
5. Optional **Lesson 5**: move fault behind Helm `.Values.faultInjection.*` (env-scoped chaos).

### Setup facts (verified in this cluster)
- Test command: `curl -H "Host: dev.bookstore.local" http://127.0.0.1/books -w "%{http_code} %{time_total}s\n"`
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
