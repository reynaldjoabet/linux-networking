# Fly.io

## Overview

- **Anycast + BGP edge** puts a single IP (v6/v4) on the global internet; traffic is steered to the closest region where your app has capacity. See [BGP, ASNs and Anycast](bgp-anycast.md).
- **TLS terminates at the edge** (Fly Proxy) and requests are forwarded over Fly's private backbone.
- **Private org network ("6PN"):** every org gets an IPv6 mesh built with WireGuard tunnels; apps see each other on a private network by default and talk over `.internal` DNS or 6PN addresses with no public exposure.

Fly.io runs data centers (called **regions**) all over the world: `iad` (Virginia), `lhr` (London), `sin` (Singapore), etc. Each app instance you deploy (a Firecracker microVM, a **Machine**) gets:

- a public address (for internet access), and
- a private address inside Fly's `fdaa::/16` IPv6 network.

### Anycast IPs and BGP

When you deploy an app:

- Fly assigns it a public IPv4 and IPv6 address — an **anycast** address.
- Fly announces that IP from many edge locations around the world using BGP, peering with ISPs, IXPs (Internet Exchange Points) and cloud providers to keep routing fast and local.
- A user connecting to your app's IP (say `203.0.113.42`) is routed by BGP to the closest running instance.

Fly's data centers are called **Points of Presence (PoPs)** — e.g. `iad` (Virginia), `nrt` (Tokyo), `syd` (Sydney), `fra` (Frankfurt). In every PoP where your app runs, Fly's edge routers advertise the same prefix:

```text
Fly edge router (Tokyo):
  announce 203.0.113.42/32 to local ISP peers

Fly edge router (Frankfurt):
  announce 203.0.113.42/32 to local peers
```

Scaling to a new region:

```sh
fly scale count 1 --region lax
```

Fly starts announcing your anycast IP from LAX, and users on the US West Coast are routed there — no DNS or IP changes needed. A single IP, no complicated DNS setup.

```text
         +------------------+                   +------------------+
         |   User in Tokyo  |                   |   User in NYC    |
         |  IP: 203.0.113.X |                   |  IP: 203.0.113.X |
         +--------+---------+                   +--------+---------+
                  |                                        |
                  ▼                                        ▼
      ┌─────────────────────┐               ┌─────────────────────┐
      │ Fly.io Anycast Edge │               │ Fly.io Anycast Edge │
      │       (Tokyo)       │──────────────▶│       (Virginia)    │
      └─────────────────────┘               └─────────────────────┘
                  │                                        │
        ┌─────────┴──────────┐                 ┌───────────┴─────────┐
        ▼                    ▼                 ▼                     ▼
   [App Instance]      [App Instance]     [App Instance]       [App Instance]
      Tokyo                 IAD               Frankfurt             Sydney
   (fly deploy)        (fly deploy)      (optional region)    (optional region)

             ─────── All share the SAME Anycast IP ───────
```

### Lifecycle of a request

1. Someone visits `https://your-app.fly.dev`; the request hits the closest Fly edge server.
2. It goes through the **Fly Proxy**, which knows which Machines belong to which apps and handles load balancing.
3. The proxy forwards the request over a secure WireGuard tunnel to a Machine belonging to your app.

There are two basic ways to talk to a process in a Fly Machine:

- **Via Fly Proxy** — load balancing; what you need for any public web service.
- **Over the WireGuard IPv6 private network (6PN)** the app belongs to — useful e.g. for private supporting services.

## The private network (6PN)

- Every Fly organization gets a private IPv6 network, the **6PN**, completely isolated from the public internet.
- All Machines get an address on it. All Fly apps, in all regions, get private IPv6 addresses in the same `fdaa::/16` range.
- The network is **global** and scoped to the organization (account/team): a database in `ord` (Chicago) and a web service in `cdg` (Paris) in the same org communicate privately without traversing the public internet. Deploy 10 apps under the same org and they all join the same mesh.
- You can still restrict connectivity at the app level with firewall rules or by controlling which IP ranges may connect.
- By default all of an org's apps share one private network, but you can create apps on **separate** private networks to isolate them — e.g. if you run separate apps for your customers.

```sh
fly launch --name redis-app --region fra
fly launch --name api-app --region ord
```

App A (Redis) in Frankfurt and App B (API) in Chicago can now talk privately.

### Fly Global Private Network routing

All private addresses — in any region — talk over Fly's encrypted backbone, the **Fly Global Private Network (GPN)**. Fly maintains internal routers that know how to reach every app's private IP. When an instance in Singapore (`fdaa:1:2::10`) sends to an app in Frankfurt (`fdaa:1:3::20`):

- it's routed via Fly's private routers,
- it stays on Fly's network the whole time,
- it never needs NAT, public IPs, or external firewalls.

This is essentially BGP + internal overlay routing, handled automatically by Fly.

```text
               🌍 Fly.io Global Private Network
┌──────────────────────────────────────────────────────────────┐
│ fdaa::/16  (Encrypted IPv6-only internal backbone)           │
│                                                              │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐  │
│  │ iad (USA)    │────▶│ lhr (UK)     │────▶│ sin (SG)     │  │
│  │ App A        │     │              │     │ App B        │  │
│  │ fdaa:1:2::10 │     │              │     │ fdaa:1:3::20 │  │
│  └──────────────┘     └──────────────┘     └──────────────┘  │
│         ▲                                          │         │
│         │                                          │         │
│    WireGuard VPN                          Internal app calls │
│  (your laptop joins the same fdaa::/16 network)              │
└──────────────────────────────────────────────────────────────┘
```

### Address–key binding: no spoofing

In Fly's internal control plane, every microVM, gateway and WireGuard peer gets a unique private IP inside `fdaa::/16`, **cryptographically bound to a specific WireGuard public key**. When packets arrive at any Fly router or gateway:

- the packet's origin is validated against its key;
- the source IP must match the key's IP binding;
- if not, the packet is dropped.

Having private IPs is a great security feature: if services don't need to be public, why expose them? And unlike private IPv4 addresses (reused everywhere), these private IPv6 addresses are globally unique — see [IP addressing](ip-addressing.md#ipv6-unique-local-addresses-ula).

### WireGuard under the hood

Fly.io runs its private org-level network on WireGuard tunnels. Apps need to talk privately across regions, and you need to manage them securely from your laptop or servers — all secure, fast and automated, without setting up VPNs manually.

- The 6PN is WireGuard tunnels between: your client (laptop or CI/CD via `flyctl`), the edge proxies in Fly regions, and the hypervisors hosting your Machines.
- Each Fly host/edge server runs a WireGuard endpoint; each customer machine (laptop, CI/CD, …) can be a peer. This creates a mesh of secure tunnels across Fly's global backbone.
- WireGuard provides strong encryption (ChaCha20-Poly1305), lightweight tunneling with very low overhead, and automatic key rotation (Fly handles ephemeral peer keys).
- Traffic is encrypted end-to-end, even where it crosses the public internet between Fly data centers. E.g. a Redis in Amsterdam and a web app in Singapore each get a private IPv6 address, and their traffic flows over WireGuard inside Fly's backbone.

See [WireGuard](wireguard.md).

## Internal DNS

Fly provides a "magic" DNS service. Every app in the same org can reach others over the private network using internal names:

| Name                                   | Resolves to                                                    |
| -------------------------------------- | -------------------------------------------------------------- |
| `<app-name>.internal`                  | All instances of that app (6PN addresses)                      |
| `top2.nearest.of.<app-name>.internal`  | The two closest instances to the caller                        |
| `<region>.<app-name>.internal`         | Instances in one region (e.g. `fra.api.internal`)              |
| `<instance-id>.vm.<app-name>.internal` | A specific Machine's 6PN address (direct, bypassing the proxy) |
| `<app-name>.flycast`                   | The app's Flycast address (proxy-managed)                      |

> The original notes listed `<app-name>.internal` twice — once as "all instances" and once as "two closest instances"; the latter is Fly's `top2.nearest.of.` prefix. They also said `appname.internal` resolves to the Flycast address; per Fly's docs, Flycast is reached via `<app>.flycast`, while `.internal` returns Machine 6PN addresses.

- Each app has its own DNS namespace (`api.internal`, `db.internal`, …): `api.internal` only resolves to the API Machines, `db.internal` only to the Postgres Machines.
- Public anycast DNS (`.fly.dev`) is for external traffic; private `.internal` DNS is for org-only service-to-service calls.
- You can always "drop down" to direct Machine DNS to bypass the proxy.

## Flycast

**Anycast** is when the same IP address is announced from multiple locations; routers forward a client's packet to the "nearest" one (per BGP and routing policy). This gives one stable address for a distributed service, proximity-based routing, and built-in resilience — if one location goes down, traffic goes elsewhere.

Flycast applies the idea privately:

- A Flycast address is a private IP that exists everywhere inside your org's 6PN mesh.
- You don't connect to a specific Machine's IPv6 address; you connect to this shared, anycast-like endpoint.
- The Fly Proxy in your nearest region picks up the connection and forwards it to a healthy Machine — applying load balancing, health checks, or autostart.

So: same address, multiple possible destinations; nearest entry point chosen automatically.

**Fly Postgres** uses Flycast internally: the cluster exposes Flycast addresses (like `pg.internal`) for connections, so apps connect without the DB having a public IP; the proxy ensures you always hit a healthy node and can scale/replace nodes as needed.

## Fly Proxy

All services reachable from the public internet via an app's global anycast address are routed by Fly Proxy. An app with HTTP and HTTPS services defined is allocated public IPs automatically on the first `fly deploy` (check with `fly ips list`).

### App-scoped registry

Each app has its own set of Machines. `fly deploy` or `fly machine create` registers Machines in Fly's control plane under that app only. The proxy keeps, per app, a mapping of Machine 6PN addresses, health status, region/host info, and config (scale-to-zero eligibility, concurrency limits). So `my-api` and `my-worker` have independent Machine pools that the proxy never mixes up.

```text
           +-------------+
Client --->| api.fly.dev |--- Anycast ---> Nearest Fly Proxy
           +-------------+                   |
                                              v
                                     [ api app Machines ]
                                            |
                      +---------------------+---------------------+
                      |                                           |
             api.internal (Flycast)                       db.internal (Flycast)
                      |                                           |
            Worker Machines ------------------> [ api app ] ----> [ db app ]
                              (6PN traffic, all proxy-managed)
```

```text
          Client (Internet)
                |
         api.fly.dev (Anycast)
                |
        Nearest Fly Proxy
                |
        [ api app Machines ]
         |              |
  api.internal      fra.api.internal
   (any region)      (only FRA)

         Worker Machines
          |          |
   api.internal   fra.db.internal ---> [ db app FRA ]
          |
   mad.db.internal ------------------> [ db app MAD ]
                     (leader writes)
```

## Connecting your machine (`flyctl wireguard create`)

`flyctl wireguard create` generates WireGuard keys and a ready-to-use config, adding your laptop (or server, or Kubernetes cluster) as a peer in the org's network — like another site in a site-to-site VPN. That peer can reach any app in the org's 6PN.

What it sets up:

- A tunnel interface (e.g. `wg-fly`) on your host.
- A private IPv6 address for your machine in the same range as your Fly apps.
- Routes so packets for the Fly private network go through the tunnel.
- WireGuard encrypts those packets and sends them to a Fly WireGuard endpoint (a public IP).

On your host you now have a normal interface (e.g. `eth0`, public IP `203.0.113.55`) and a WireGuard interface (e.g. `wg-fly`, internal IP `fdaa:1:2::3`):

```text
default via 203.0.113.1 dev eth0
fdaa::/16 dev wg-fly proto kernel
```

- Normal internet traffic goes out `eth0` (default route).
- Traffic to the Fly internal network (`fdaa::/16`) goes through `wg-fly`; `eth0` carries the resulting UDP packets to the Fly WireGuard endpoint's public IP (e.g. `147.75.x.x`).

The path of a packet to `my-app.internal`:

1. **Local dev / client → Fly:** encrypted and tunneled over WireGuard to the nearest Fly edge.
2. **Fly edge proxy:** decrypts it, sees the destination (a Machine or a Flycast address), and forwards it directly to a Machine's private IPv6 (6PN direct), or — for a Flycast address — applies load balancing, health checks or autostart before choosing a Machine.
3. **Inside Fly's backbone:** if the target Machine is in another region, the request crosses Fly's private backbone, still encapsulated in WireGuard between edges and hosts.

## Orchestration

Orchestrators link clusters of worker servers together and offer an API to run jobs on them. Kubernetes is the Kleenex of orchestrators; Fly uses HashiCorp **Nomad**.

## Production workflow with `flyctl`

`fly` and `flyctl` are the same binary:

```console
$ which flyctl
/opt/homebrew/bin/flyctl
$ which fly
/opt/homebrew/bin/fly

$ fly version
fly v0.3.202 darwin/arm64 Commit: d170dd15b783eafe37a895494716d0414c4ed4c2 BuildDate: 2025-10-23T13:41:02Z
$ flyctl version
flyctl v0.3.202 darwin/arm64 Commit: d170dd15b783eafe37a895494716d0414c4ed4c2 BuildDate: 2025-10-23T13:41:02Z

## or compare file hashes
$ shasum -a 256 /opt/homebrew/bin/fly /opt/homebrew/bin/flyctl
5a0d9e7e4765fd3d9112c10bb5a09ab15b01774368d7524009af1baf99aae9b0  /opt/homebrew/bin/fly
5a0d9e7e4765fd3d9112c10bb5a09ab15b01774368d7524009af1baf99aae9b0  /opt/homebrew/bin/flyctl
```

### Staging and production

Use **separate apps** for staging and prod so artifact promotion is explicit and safe.

```sh
fly launch --name myapp-staging --region sin   # interactive for staging
fly launch --name myapp-prod    --region sin   # production app
```

Deploy to staging and run smoke tests:

```sh
fly deploy --app myapp-staging --image registry.fly.io/myorg/myapp:$SHA --remote-only
# run automated smoke tests against staging URL
```

Canary to prod — scale to one instance, run live checks, then increase:

```sh
# step 1: deploy single-instance canary
fly deploy --app myapp-prod --image registry.fly.io/myorg/myapp:$SHA --remote-only
fly scale count 1 --app myapp-prod   # ensure only 1 instance runs (instance=canary)

# wait, run canary smoke tests, monitor logs/metrics:
fly logs --app myapp-prod --instance <id-if-needed>   # stream canary logs

# step 2: promote to full production (scale up gradually)
fly scale count 3 --app myapp-prod    # prod fleet -> 3
fly scale count 10 --app myapp-prod   # scale to desired capacity
```

### Persistent storage

Volumes are region-local:

```sh
fly volumes create prod-data-vol --size 200 --region sin
# mount declared in fly.toml under [mounts] so deploy attaches the volume
```

### Secrets, tokens and least-privilege CI

```sh
# create a token for CI with only the necessary scopes
fly tokens create --name ci-deploy-token
# store the returned token in GitHub Actions Secrets as FLY_API_TOKEN

# app secrets (rotate safely)
fly secrets set REDIS_URL="redis://..." STRIPE_SECRET="sk_live_..."
```

Store keys in a cloud KMS / HashiCorp Vault.

### TLS, custom domains and static IPs

```sh
# create a cert for your app (Fly can manage Let's Encrypt certs)
fly certs create example.com --app myapp-prod
# configure DNS to point to Fly (usually CNAME to app.fly.dev or use allocated IP)

# allocate static anycast IPs
fly ips allocate v4 --region sin
fly ips list --app myapp-prod
```

Use static IPs if you need fixed IP allowlists (firewalls, partner APIs).

<details>
<summary><code>fly help</code></summary>

```text
Deploying apps & machines
  apps         Manage apps.
  deploy       Deploy Fly applications
  launch       Create and configure a new app from source code or a Docker image
  machine      Manage Fly Machines.
  status       Show app status

Configuration & scaling
  certs        Manage certificates
  config       Manage an app's configuration
  image        Manage app image
  ips          Manage IP addresses for apps
  scale        Scale app resources
  secrets      Manage application secrets with the set and unset commands.
  volumes      Manage Fly Volumes.

Monitoring & managing things
  checks       Manage health checks
  console      Run a console in a new or existing machine
  dashboard    Open web browser on Fly Web UI for this app
  dig          Make DNS requests against Fly.io's internal DNS server
  incidents    Show incidents
  logs         View app logs
  mcp          flyctl Model Context Protocol.
  ping         Test connectivity with ICMP ping messages
  proxy        Proxies connections to a Fly Machine.
  releases     List app releases
  services     Show the application's services
  sftp         Get or put files from a remote VM.
  ssh          Use SSH to log into or run commands on Machines
  wireguard    Commands that manage WireGuard peer connections

Databases & extensions
  consul       Enable and manage Consul clusters
  extensions   Extensions are additional functionality that can be added to your Fly apps
  litefs-cloud LiteFS Cloud management commands
  mpg          Manage Managed Postgres clusters.
  mysql        Provision and manage MySQL database clusters
  postgres     Unmanaged Postgres cluster commands
  redis        Launch and manage Redis databases managed by Upstash.com
  storage      Provision and manage Tigris object storage buckets

Access control
  auth         Manage authentication
  orgs         Commands for managing Fly organizations
  tokens       Manage Fly.io API tokens

Help & troubleshooting
  docs         View Fly documentation
  doctor       The DOCTOR command allows you to debug your Fly environment
  platform     Fly platform information

Additional Commands:
  agent        Commands that manage the Fly agent, a background process that manages flyctl wireguard connections
  completion   Generate the autocompletion script for the specified shell
  help         Help about any command
  jobs         Show jobs at Fly.io
  settings     Manage flyctl settings
  synthetics   Synthetic monitoring
  version      Show version information for the flyctl command
```

</details>

## Further reading

- [Demystifying VPNs for web devs (video)](https://www.youtube.com/watch?v=9VYwzmOjGkU)
