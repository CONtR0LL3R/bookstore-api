# Istio Guide — from basics to interview level

A theory-first reference for the BookStore API project (`nginx frontend + Spring Boot API + PostgreSQL`, deployed on Kubernetes). Every concept below is tied back to that architecture.

---

## 1. Why a service mesh?

Kubernetes already gives you:

| Primitive | What it gives you |
|---|---|
| `Service` | Stable DNS + simple round-robin load balancing |
| `Ingress` | External entry (this repo's `k8s/ingress.yaml` routes `/api/*` → API, everything else → frontend) |
| `Deployment` | Scaling, rolling updates, self-healing |

But in a real production system with many microservices you need **cross-cutting concerns** on *every* service:

| Concern | Without a mesh | With a mesh (Istio) |
|---|---|---|
| Traffic control | Reimplement retries/timeouts/canaries in each app language | Declarative `VirtualService`/`DestinationRule` — **no app code changes** |
| Observability | Add metrics/tracing libs everywhere | Envoy emits metrics/traces/logs from the network layer automatically |
| Security | Manage TLS certs + authn/authz per app | mTLS + JWT validation + RBAC enforced **at the proxy** |

> **The mental model:** the mesh moves these concerns out of your application code and into a proxy layer. The Spring Boot controller just answers `GET /books`; it knows nothing about canaries, mTLS, or tracing.

---

## 2. The sidecar pattern

Istio injects an **Envoy proxy** as a second container in every pod:

```
 Before (raw k8s):                 After (Istio):
 ┌──────────────────────┐          ┌──────────────────────┐
 │ frontend container   │          │ frontend container   │
 │  nginx  :80          │          │  nginx :80 ──►┐      │
 └──────────────────────┘          └───────────────┼──────┘
                                                    ▼
                                              envoy sidecar
                                            (transparent proxy)
```

- **Traffic capture is transparent** — Istio uses iptables (or the Istio CNI) to redirect all in/out pod traffic through the local Envoy. Your app still calls `http://bookstore-service:80`; Envoy intercepts it. The app does not know it exists.
- After injection each pod shows `2/2 READY` (`kubectl get pods`), with the extra container named `istio-proxy`.

---

## 3. Architecture: data plane vs control plane

```
               ┌──────────────────────────────────────────┐
               │           CONTROL PLANE                  │
               │           (istiod, 1 pod)                │
               │   Pilot:  Istio config -> Envoy (xDS)    │
               │   Citadel: mTLS cert issuer (SDS)        │
               └─────────────────┬─────────┬──────────────┘
                     xDS config  │         │ certs
               ┌─────────────────▼─────────▼──────────────┐
               │             DATA PLANE                    │
               │  all sidecars + ingress/egress gateways   │
               │           (Envoy proxies)                 │
               └───────────────────────────────────────────┘
```

- **Data plane** = the Envoy proxies. They do the real work: load balancing, TLS, retries, telemetry. Every request passes through 2 proxies (source + destination).
- **Control plane** = **istiod** (single binary since Istio 1.5; merges the old Pilot, Citadel, Galley, Mixer):
  - **Pilot** — turns `VirtualService`/`DestinationRule`/etc. into Envoy config and pushes it via the **xDS protocol** (CDS, LDS, RDS, EDS, SDS).
  - **Citadel** — the CA; issues the mTLS certificates (SPIFFE identities bound to ServiceAccounts), auto-rotates them.
  - **Galley / Mixer** (pre-1.5) — config validation and policy/telemetry; both removed/merged.

> **Interview-ready:** "The control plane tells proxies *what to do* (xDS config); the data plane *does it*." If istiod dies, existing proxies keep forwarding — you just can't push changes.

---

## 4. Request flow in the bookstore (with Istio)

`POST /books` from the browser (frontend JS calls `/api/books`):

```
Hop 1:  Browser ──► istio-ingressgateway (Envoy, public LB)
            └─ Gateway rule: host bookstore.local, path / → frontend-service
Hop 2:  frontend pod's Envoy ──► frontend app (nginx)
Hop 3:  frontend JS fetch('/api/books')
            └─ frontend's Envoy intercepts transparently
            └─ VirtualService: /api/books → bookstore-service:80
            └─ backend Envoy → one of the bookstore pods :8080
Hop 4:  Spring Boot → HikariCP → postgres-headless:5432 (also proxied)
```

Every hop is between two Envoys, so mTLS and tracing cover the whole chain — **without changing a single line of the Spring Boot or nginx code**. Even the Postgres StatefulSet gets a sidecar.

---

## 5. The Istio API surface (CRDs)

| Resource | Layer | Purpose | Analogy in this project |
|---|---|---|---|
| `Gateway` | Entry (L4/L7) | Open a port on the ingress gateway; define host + TLS | replaces `k8s/ingress.yaml` |
| `VirtualService` | Routing | Rules for *which traffic goes where* (path, headers, weight) | the `/api/* → bookstore-service` rule |
| `DestinationRule` | Load balancing / policy | Subsets, LB algorithm, mTLS mode, circuit breaker | which *pods* of the API get traffic |
| `ServiceEntry` | Egress | Allow calls to services *outside* the mesh | external book-prices API |
| `Sidecar` | Scope | Limit which namespaces a sidecar may reach | advanced scoping |
| `PeerAuthentication` | Security | mTLS mode per mesh / namespace / workload | "API↔DB must be mTLS" |
| `RequestAuthentication` | Security | Validate JWTs at the proxy | "only valid tokens reach /books" |
| `AuthorizationPolicy` | Security | RBAC: who may call what | "only frontend may call the API" |

### The Golden Rule (asked in every interview)

- **VirtualService says WHAT traffic should reach a service.** It cannot select pods — that's the DestinationRule's job.
- **DestinationRule says which subsets (pod labels) and with what policies** (LB, mTLS, circuit breaker).
- A `VirtualService` referencing a `subset` **without** a matching `DestinationRule` routes to nowhere.

---

## 6. Gateway + VirtualService (replacing the nginx Ingress)

Why two resources instead of the nginx Ingress's one? Istio splits "what can come in" (Gateway) from "what happens to it" (VirtualService), so multiple teams/namespaces can share one Gateway.

```yaml
apiVersion: networking.istio.io/v1
kind: Gateway
metadata:
  name: bookstore-gateway
  namespace: default
spec:
  selector:
    istio: ingressgateway        # targets the default ingress gateway deployment
  servers:
    - port: { number: 80, name: http, protocol: HTTP }
      hosts: ["bookstore.local"]
```

```yaml
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata:
  name: bookstore
  namespace: default
spec:
  hosts: ["bookstore.local"]
  gateways: [bookstore-gateway]      # omit gateways → internal mesh traffic only
  http:
    - match:
        - uri: { prefix: /api }
      rewrite:
        uri: ""                      # strip /api so Spring sees /books
      route:
        - destination: { host: bookstore-service, port: { number: 80 } }
    - route:                          # catch-all → frontend
        - destination: { host: frontend-service, port: { number: 80 } }
```

> ⚠️ **Top gotcha:** without the `rewrite: uri: ""` your app 404s. The nginx Ingress used `rewrite-target: /$2`; Istio needs the explicit rewrite.

### Canary / weighted traffic

```yaml
http:
  - route:
      - destination: { host: bookstore-service, subset: v3 }
        weight: 90
      - destination: { host: bookstore-service, subset: v4 }
        weight: 10
```

Weights are enforced by the **source Envoy**, so per-request accuracy is exact (no DNS round-robin sloppiness). Subsets must exist in a DestinationRule.

### Fault injection, timeouts, retries, mirroring (all in the VS)

```yaml
http:
  - timeout: 3s
    retries: { attempts: 3, perTryTimeout: 1s, retryOn: connect-failure,refused-stream,5xx }
    fault:
      delay: { percentage: { value: 10 }, fixedDelay: 5s }   # simulate latency
      abort: { percentage: { value: 5 }, httpStatus: 503 }    # simulate failures
    mirror:
      host: bookstore-service
      subset: v4                                               # shadow traffic to v4
```

---

## 7. DestinationRule — subsets, LB, circuit breaking

```yaml
apiVersion: networking.istio.io/v1
kind: DestinationRule
metadata:
  name: bookstore-dr
  namespace: default
spec:
  host: bookstore-service
  trafficPolicy:
    loadBalancer:
      simple: LEAST_REQUEST            # ROUND_ROBIN | RANDOM | LEAST_REQUEST | PASSTHROUGH
    connectionPool:
      tcp: { maxConnections: 100 }     # circuit breaker cap
      http: { http1MaxPendingRequests: 10 }
    outlierDetection:                  # automatic ejection of failing pods
      consecutive5xxErrors: 3
      interval: 10s
      baseEjectionTime: 30s
  subsets:
    - name: v3
      labels: { version: v3 }          # pods must carry the version label
    - name: v4
      labels: { version: v4 }
```

- **No DestinationRule → `subset` references break.** Every subset in a VS must exist in a DR.
- Outlier detection = the mesh's circuit breaker: a pod returning 3×5xx is ejected for 30s. It *complements* the Kubernetes probes (kubelet restarts the container; Envoy just stops sending to it).

---

## 8. ServiceEntry — reaching outside the mesh

By default Istio will not forward traffic outside the mesh. To reach an external API:

```yaml
apiVersion: networking.istio.io/v1
kind: ServiceEntry
metadata:
  name: external-books
spec:
  hosts: ["api.external-prices.com"]
  location: MESH_EXTERNAL
  resolution: DNS                # Envoy resolves and balances across returned IPs
  ports:
    - number: 443
      name: https
      protocol: TLS
```

> **Interview favorite:** "Why does my pod time out calling an external API after installing Istio?" → egress is blocked; add a `ServiceEntry` or set `meshConfig.outboundTrafficPolicy.mode: ALLOW_ANY`.

---

## 9. Security — the three primitives

| Resource | Question it answers | Bookstore example |
|---|---|---|
| `PeerAuthentication` | Is the *connection* encrypted + authenticated? | "frontend→API and API→DB must be mTLS" |
| `RequestAuthentication` | Does the *request* carry a valid JWT? | "accept tokens from our OIDC issuer" |
| `AuthorizationPolicy` | Is the *caller* allowed to do this? | "only the frontend may call `/books`" |

Mental order: **transport security (Peer) → user authN (Request) → action authZ (Authorization)**.

### 9.1 PeerAuthentication (mTLS)

Each workload gets an x509 identity (from Citadel/istiod) bound to its Kubernetes ServiceAccount. Envoys present these certs to each other:

```
frontend Envoy ──TLS + client cert──► API Envoy ──TLS + client cert──► Postgres Envoy
```

```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: default
spec:
  mtls:
    mode: STRICT        # STRICT | PERMISSIVE (default) | DISABLE
```

- **PERMISSIVE** (default): accepts mTLS and plaintext — safe during rollout.
- **STRICT**: only mTLS. **Apply only after all workloads have sidecars**, or plaintext clients (curl pods, non-mesh traffic) break.
- Scoping: mesh-wide → namespace → workload (via `selector`).

### 9.2 RequestAuthentication (JWT)

The proxy validates signature, issuer, expiry **before Spring Boot sees the request**:

```yaml
apiVersion: security.istio.io/v1
kind: RequestAuthentication
metadata:
  name: bookstore-jwt
  namespace: default
spec:
  selector:
    matchLabels: { app: bookstore }
  jwtRules:
    - issuer: "https://accounts.example.com"
      jwksUri: "https://accounts.example.com/.well-known/jwks.json"
```

- It only *validates*; it does **not** deny requests without a token. Denial is `AuthorizationPolicy`'s job.
- Extracted claims become available to AuthorizationPolicy (`requestPrincipals`) or your app.

### 9.3 AuthorizationPolicy (RBAC)

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: bookstore-api-allow
  namespace: default
spec:
  selector:
    matchLabels: { app: bookstore }
  action: ALLOW          # ALLOW | DENY | AUDIT | CUSTOM
  rules:
    - from:
        - source:
            principals: ["cluster.local/ns/default/sa/frontend-sa"]
      to:
        - operation:
            methods: ["GET", "POST"]
            paths: ["/books", "/books/*"]
```

- **ALLOW policies are additive; if any ALLOW policy exists for a workload, unlisted requests are denied by default.**
- **DENY takes precedence over ALLOW.**
- Identity comes from the source Envoy's client cert (`principals`) or JWT claims (`requestPrincipals`).
- **ServiceAccounts matter:** workloads sharing the `default` ServiceAccount share the same SPIFFE identity. Give each workload its own SA for meaningful authZ.

> **Gotcha:** kubelet HTTP liveness/readiness probes get intercepted by the sidecar by default, so an AuthorizationPolicy that doesn't permit them can make pods unready. Fix: `sidecar.istio.io/rewriteAppHTTPProbers: "true"` on the deployment (probes then bypass Envoy entirely).

---

## 10. Observability

Because every request passes through Envoy, telemetry comes for free:

| Pillar | What you get | Backend |
|---|---|---|
| Metrics | RED: Requests/s, Error rate, Duration + labels (source, destination, response_code) | Prometheus + Grafana |
| Traces | One trace per request across frontend→API→DB | Jaeger / Tempo / Zipkin |
| Logs | Envoy access logs | your log stack |

- **Trace propagation gotcha:** Envoy generates `x-request-id` / `traceparent` / `b3` headers, but for a *single* distributed trace the app must propagate them to the next hop. Spring Boot Micrometer Tracing does this automatically.
- **Kiali** renders the traffic graph (`ingressgateway → frontend → api → postgres`) with per-edge metrics and lets you inspect/edit VS/DR.

### Essential debugging commands

```bash
istioctl analyze                                    # validate config before apply
istioctl proxy-status                               # control plane ↔ proxy sync health
istioctl proxy-config routes deploy/bookstore-deployment
istioctl proxy-config listeners deploy/bookstore-deployment
istioctl experimental describe pod/bookstore-deployment-<pod>
kubectl logs <pod> -c istio-proxy                    # Envoy access logs
kubectl get virtualservices,destinationrules,gateways -A
```

`istioctl proxy-status` — every proxy should show `SYNCED`; `STALE`/missing means your new config hasn't reached that proxy.

---

## 11. Istio vs alternatives & Ingress vs Gateway

| | nginx Ingress | Istio Gateway + VS |
|---|---|---|
| Scope | L4/L7 routing only | routing + retries + canary + fault injection + mTLS + telemetry |
| Traffic management | limited annotations | rich, centralized, versioned config |
| mTLS | manual | automatic (Citadel/istiod) |
| Observability | basic | full metrics/traces + Kiali |

| | Istio | Linkerd | Consul Connect |
|---|---|---|---|
| Data plane | Envoy (powerful, heavy) | dedicated Rust proxy (lightweight) | Envoy |
| Feature depth | largest (mTLS, JWT, RBAC, mirroring, fault injection) | good (mTLS, retries, canary) | mTLS + KV + service mesh |
| Complexity | highest | lowest | medium |

---

## 12. Quick interview prep

**Q: What is a service mesh?**
A proxy-based layer that handles service-to-service traffic (routing, resilience, security, observability) without changing application code.

**Q: Data plane vs control plane?**
Data plane = Envoys that forward/enforce; control plane = istiod that configures them via xDS and issues certs.

**Q: How does a request get intercepted?**
iptables/CNI redirect pod traffic through the local Envoy sidecar (transparently).

**Q: VirtualService vs DestinationRule?**
VS = which traffic goes where (routing, weights, retries, timeouts, faults); DR = how it's served (subsets, LB, mTLS, circuit breaker). VS `subset` needs a matching DR.

**Q: PERMISSIVE vs STRICT mTLS?**
PERMISSIVE accepts both; STRICT requires mTLS. Roll out PERMISSIVE → then STRICT once all clients have sidecars.

**Q: PeerAuthentication vs RequestAuthentication vs AuthorizationPolicy?**
Transport (connection) auth → user (JWT) authN → action authZ.

**Q: What happens if istiod dies?**
Existing Envoy configs keep working; you just can't push changes or rotate certs.

**Q: Why would the app 404 after Istio?**
Missing/invalid rewrite, e.g. `/api/books` forwarded un-rewritten to a service expecting `/books`.

**Q: Why can't a pod reach an external API after Istio?**
Egress blocked — add a `ServiceEntry` or set outbound traffic to `ALLOW_ANY`.

**Q: How does Istio know the identity of a workload?**
SPIFFE identity derived from the ServiceAccount, embedded in the x509 cert issued by istiod (SDS), presented during mTLS.

**Q: What is xDS?**
A family of Envoy discovery protocols (Cluster, Listener, Route, Endpoint, Secret Discovery) — how istiod configures proxies in real time.

---

*See the repo README for the base architecture; the hands-on install/verify commands and the `k8s/istio/` manifests are covered separately.*
