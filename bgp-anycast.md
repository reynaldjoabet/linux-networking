# BGP, ASNs and Anycast

## What is Anycast?

- With **unicast**, one IP = one server/location.
- With **anycast**, one IP = many servers around the world: the same address is announced from multiple locations, and routers forward a client's packet to the "nearest" one (per BGP and routing policy) — the lowest-latency server that advertises that IP.

This gives you:

- one stable address for a distributed service;
- proximity-based routing (you hit the nearest endpoint);
- built-in resilience — if one location goes down, traffic goes elsewhere.

When your DNS points `api.domain.com` → `203.0.113.1` (an anycast IP):

- a user in Tokyo is routed to the Tokyo edge POP;
- a user in Paris is routed to the Paris edge POP;
- same hostname, same IP, different physical edges.

> **Anycast** → multiple POPs advertise the same prefix from the same ASN, and BGP routes traffic to the closest one.

### Example: anycast DNS

A DNS provider (e.g. Cloudflare) announces the same IP address (like `1.1.1.1`) from multiple locations around the world using BGP. Each server node is part of an autonomous system (AS) or peers with ISPs, and the same prefix (e.g. `1.1.1.0/24`) is advertised from many places.

Routers across the internet use BGP to pick the "closest" node — mostly by **local policy** and **AS-path length** (see [how BGP picks a path](#how-bgp-picks-a-path)). BGP does **not** measure hop count, latency or congestion.

So a query to `1.1.1.1` goes to the node closest in **BGP terms**, not necessarily geographic distance.

```text
You               ┌────────────┐
┌─────┐    BGP    │ Anycast DNS│  <- Also running DNS software
│ PC  │──────────▶│  IP (e.g.  │
└─────┘           │  8.8.8.8)  │
                  └────────────┘
              /        |         \
        Node A     Node B      Node C
      (New York) (Frankfurt) (Tokyo)
```

## How CDNs use it

- **Edge network**
  - CDNs advertise their anycast IPs from hundreds of data centers worldwide.
  - BGP ensures each client hits the "closest" edge — network-wise, not just geographically.
- **Latency reduction**
  - The TLS handshake happens at the nearest edge.
  - For static content, the edge may serve from cache immediately.
  - For dynamic content/APIs, the edge forwards via the CDN's optimized backbone to your origin.
- **Failover and resilience**
  - If one POP goes down, BGP routes traffic to the next best POP automatically.
  - This gives built-in DDoS resilience and high availability.

## What is an ASN?

- The internet is made up of thousands of independent networks: ISPs, CDNs, cloud providers, universities, enterprises.
- Each is an **Autonomous System (AS)**: a group of IP prefixes under a single routing policy.
- An **Autonomous System Number (ASN)** is the unique identifier for that network in BGP (Border Gateway Protocol).

### How it works

- When two networks connect, they use BGP to exchange routes.
- The ASN is announced along with the IP prefix, so other routers know which network a route belongs to:

  ```text
  Prefix: 203.0.113.0/24
  Origin ASN: 65000
  ```

- Routers look at the **AS-path** (the chain of ASNs a route passed through) to decide the best path.

### Types of ASNs

| Type    | Allocated by                     | Used for                                         | Range                                               |
| ------- | -------------------------------- | ------------------------------------------------ | --------------------------------------------------- |
| Public  | RIRs (ARIN, RIPE, APNIC, …)      | Connecting to the global internet                | 1–64,495 (plus 4-byte ASNs up to ~4.2 billion)      |
| Private | —                                | Internal BGP inside organizations (not advertised globally) | 64,512–65,534 and 4,200,000,000–4,294,967,294 |

## BGP basics

**BGP** (Border Gateway Protocol, BGP-4, RFC 4271) is a **path-vector** routing protocol. Routers ("speakers") open a TCP session on port **179** with each neighbor ("peer") and exchange reachability: *"prefix X is reachable via me, through this AS path."* It's built for scale and policy rather than fast convergence: it carries ~1M IPv4 routes for the whole internet and lets each network decide which routes to accept, prefer and re-advertise.

| Flavor   | Between                              | Typical use                                              |
| -------- | ------------------------------------ | -------------------------------------------------------- |
| **eBGP** | Routers in **different** ASes        | ISP peering, transit, cloud/on-prem links, data center leaf↔spine (each switch its own AS) |
| **iBGP** | Routers in the **same** AS           | Distributing externally learned routes inside one network (needs full mesh, route reflectors or confederations) |

**MP-BGP** (multiprotocol extensions) lets the same sessions carry more than IPv4 unicast: IPv6, VPN routes (VPNv4/v6), EVPN, multicast and FlowSpec. That's why BGP shows up in so many places below.

### How BGP picks a path

When several routes to the same prefix exist, BGP compares attributes in order (simplified; Cisco/Juniper add a few vendor steps):

1. Highest **weight** (Cisco-local) / highest **LOCAL_PREF** — "I prefer this exit" (policy inside your AS).
2. Locally originated routes.
3. Shortest **AS_PATH**.
4. Lowest **ORIGIN** type (IGP < EGP < incomplete).
5. Lowest **MED** — a neighbor's hint for which of its entry points to use.
6. **eBGP over iBGP**.
7. Lowest IGP cost to the next hop ("hot-potato" routing).
8. Tie-breakers: oldest route, lowest router ID, lowest neighbor address.

Equal routes can be used together with **ECMP** (`maximum-paths` / multipath). **Longest prefix match** still wins over all of this: a `/24` beats a `/16` regardless of attributes — the basis of both traffic engineering and hijacks.

Traffic-engineering knobs follow from this list: **LOCAL_PREF** controls how traffic **leaves** your network; **AS-path prepending**, **MED**, more-specific prefixes and **communities** influence how traffic **enters** it.

## BGP use cases

### On the internet

1. **Interconnecting autonomous systems.** BGP is the protocol that glues the internet together: every ISP, cloud, CDN, university and large enterprise announces its prefixes to neighbors and learns everyone else's.
2. **Transit and peering.**
   - *Transit:* you pay an upstream ISP to carry your traffic to the whole internet (they send you a full table or a default route).
   - *Peering:* two networks exchange traffic for their own customers directly, often settlement-free, privately or at an **IXP** (Internet Exchange Point) via a **route server**.
3. **Multihoming.** An organization with its own ASN and provider-independent prefix connects to two or more ISPs and announces the same prefix to both: if one ISP fails, BGP withdraws that path and traffic shifts to the other. Combined with LOCAL_PREF/prepending it also gives **inbound/outbound load balancing** across links.
4. **Anycast.** The same prefix is announced from many locations and BGP delivers each client to the nearest one (above): DNS (root servers, `1.1.1.1`, `8.8.8.8`), CDNs, edge platforms like [Fly.io](fly.md), NTP, and DDoS absorption — attack traffic spreads across all sites instead of hitting one.
5. **DDoS mitigation.**
   - **RTBH** (Remote Triggered Black Hole): announce the attacked `/32` with a blackhole community; upstreams drop traffic to it at their edge (sacrificing the target to save the rest).
   - **BGP FlowSpec** (RFC 8955): distribute fine-grained filter rules (match src/dst/port/protocol → drop or rate-limit) to edge routers via BGP.
   - **Scrubbing services** (Cloudflare Magic Transit, Akamai Prolexic, …): during an attack the scrubbing provider announces your prefix (often a more specific one), pulls the traffic in, cleans it, and tunnels clean traffic back to you (GRE).
6. **Traffic engineering.** Steering which links carry which traffic: AS-path prepending, MED, selective or more-specific announcements, and **BGP communities** (tags such as "don't advertise to Europe" or "set LOCAL_PREF 80 at your side" that upstreams act on).
7. **Route security.** Because BGP trusts announcements by default, it is the subject of **hijacks** and **route leaks** (e.g. the 2008 Pakistan Telecom/YouTube hijack, the 2021 Facebook outage where its prefixes were withdrawn). Defenses: **RPKI** route-origin validation (the `rpki-ov-state: valid` you see in `whois` output below), IRR filtering, prefix limits, and **BGPsec/ASPA**.
8. **Internet measurement and monitoring.** Route collectors (RIPE RIS, RouteViews), looking glasses and services like BGPStream watch the global table to detect outages, hijacks and leaks.

### Inside service-provider networks

9. **MPLS L3VPN** (RFC 4364). Providers sell customers private WANs: MP-BGP carries each customer's routes as VPNv4/v6 routes, kept separate by **route distinguishers** and **route targets** in per-customer **VRFs**. Customer sites may also run eBGP to the provider edge (PE↔CE).
10. **L2VPN / VPLS / EVPN over MPLS.** BGP signals layer 2 services (MAC addresses, Ethernet segments) between provider edges.
11. **Segment routing / BGP-LU.** Labeled unicast and SR-TE policies distributed via BGP for traffic engineering across large backbones.
12. **iBGP route distribution.** Inside a large AS, route reflectors spread external routes to all edge routers so each can make consistent exit decisions.

### Inside data centers

13. **BGP as the data center IGP** (RFC 7938). Large Clos (leaf-spine) fabrics run **eBGP everywhere** instead of OSPF/IS-IS: each leaf (or rack) gets its own private ASN, every link is an eBGP session, and ECMP spreads load across spines. Simple, scales to huge fabrics, failure isolation is good. Often **BGP unnumbered** (peering over IPv6 link-local addresses) to avoid assigning IPs per link.
14. **EVPN-VXLAN.** The modern data center overlay: MP-BGP **EVPN** is the control plane that distributes MAC/IP → VTEP mappings between switches, replacing flood-and-learn. Enables multi-tenancy, ARP suppression, anycast gateways and active-active multihoming. See [VXLAN: control plane](vxlan.md#control-plane-flood-and-learn-vs-evpn).
15. **Routing on the host.** Servers themselves run BGP (FRR, BIRD, GoBGP) to advertise their own service IPs or container subnets to the top-of-rack switch: no L2 between rack and server, no bridging, easy failover.
16. **Service IPs and load balancing.** Load balancers or service nodes announce a **VIP** via BGP; multiple nodes announcing the same VIP + switch ECMP = anycast load balancing inside the DC (e.g. Google Maglev, Facebook Katran, ExaBGP health-check-driven announcements). Withdrawing the route on a failed health check removes the node.
17. **Bare-metal / on-prem Kubernetes LoadBalancers.** **MetalLB** (BGP mode), **kube-vip**, Cilium and Calico announce `Service` type `LoadBalancer` IPs to the network so external clients can reach them.

### Kubernetes and containers

18. **Pod networking without overlays.** **Calico** runs BIRD on every node and advertises each node's pod CIDR blocks via BGP — either node-to-node mesh or to route reflectors / ToR switches — making pod IPs natively routable. Those are the `proto bird` routes in [Kubernetes: the host's routing table](kubernetes.md#the-hosts-routing-table). Cilium (BGP control plane) and kube-router do the same.
19. **Advertising Service ClusterIPs/ExternalIPs** directly to the physical network (Calico, kube-router).

### Cloud and hybrid

20. **Connecting on-prem to the cloud.** Dedicated links (AWS **Direct Connect**, Azure **ExpressRoute**, GCP **Cloud Interconnect**) and site-to-site **IPsec VPNs** run eBGP between your router and the cloud gateway to exchange routes dynamically and fail over between links.
21. **Cloud transit and SD-WAN.** AWS Transit Gateway Connect, Azure Virtual WAN, and SD-WAN appliances peer over BGP to propagate routes between VPCs/VNets, branches and data centers.
22. **Injecting routes into cloud fabrics.** **Azure Route Server** and **GCP Cloud Router** let network virtual appliances (firewalls, SD-WAN boxes) peer with the cloud over BGP and program the VNet/VPC route tables — see [Going beyond 8 peers in Azure Route Server](https://blog.cloudtrooper.net/2025/09/22/going-beyond-8-peers-in-azure-route-server/).
23. **Bring Your Own IP (BYOIP).** Clouds announce your own prefix from their network via BGP.
24. **Global edge platforms.** Fly.io, Cloudflare, Vercel and others use anycast BGP to bring users to the nearest PoP, and BGP peering with ISPs/IXPs to keep that path short — see [Fly.io](fly.md#anycast-ips-and-bgp).

### Enterprise and campus

25. **WAN and branch routing.** Enterprises run BGP to their MPLS provider, between regions, and on SD-WAN overlays; iBGP or eBGP with private ASNs separates sites and policy domains.
26. **Firewall and policy routing at the edge.** Redistributing default routes, filtering what each site may advertise, and failing over between primary/backup internet circuits.

### Software you'll meet

| Software   | Typical use                                                    |
| ---------- | -------------------------------------------------------------- |
| FRRouting  | General Linux routing suite (`bgpd`); SONiC, Cumulus, host routing |
| BIRD       | IXP route servers, Calico's per-node BGP daemon                |
| GoBGP      | Programmable BGP in Go; MetalLB, Cilium                        |
| ExaBGP     | Scriptable announcer for VIPs, health-checked anycast, FlowSpec |
| OpenBGPD   | OpenBSD's BGP daemon                                           |

## Looking up an IP's origin ASN

```sh
whois 1.1.1.1 | grep origin

origin:         AS13335
```

```sh
whois -h whois.radb.net 1.1.1.0/24

route:          1.1.1.0/24
origin:         AS13335
descr:          APNIC Research and Development
                6 Cordelia St
mnt-by:         MAINT-APNICRANDNET
last-modified:  2023-04-26T02:42:44Z
source:         APNIC
rpki-ov-state:  valid

route:          1.1.1.0/24
descr:          QRATOR via EMIX
origin:         AS13335
mnt-by:         MAINT-AS8966
changed:        noc@emix.net.ae 20230803
source:         RADB
last-modified:  2023-11-13T16:17:56Z
rpki-ov-state:  valid
```

```sh
whois -h whois.cymru.com " -v 1.1.1.1"

AS      | IP               | BGP Prefix          | CC | Registry | Allocated  | AS Name
13335   | 1.1.1.1          | 1.1.1.0/24          | AU | apnic    | 2011-08-11 | CLOUDFLARENET, US
```
