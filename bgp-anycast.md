# Anycast and ASNs

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

Routers across the internet use BGP to pick the "closest" node — mostly by **local policy** and **AS-path length** (see [BGP best path selection](bgp.md#best-path-selection)). BGP does **not** measure hop count, latency or congestion.

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

| Type    | Allocated by                | Used for                                                    | Range                                          |
| ------- | --------------------------- | ----------------------------------------------------------- | ---------------------------------------------- |
| Public  | RIRs (ARIN, RIPE, APNIC, …) | Connecting to the global internet                           | 1–64,495 (plus 4-byte ASNs up to ~4.2 billion) |
| Private | —                           | Internal BGP inside organizations (not advertised globally) | 64,512–65,534 and 4,200,000,000–4,294,967,294  |

## BGP

How BGP works (sessions, messages, attributes, best-path selection), how Linux carries it (netlink, `proto bgp`, ECMP, TCP-MD5/AO, GTSM, VRFs) and its use cases are in [BGP](bgp.md).

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
