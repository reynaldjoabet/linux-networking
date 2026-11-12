# BGP

**BGP** (Border Gateway Protocol, BGP-4, [RFC 4271](https://www.rfc-editor.org/rfc/rfc4271)) is the routing protocol that connects the internet's networks to each other, and increasingly the protocol inside data centers, clouds and Kubernetes clusters.

On Linux, BGP is split in two:

- **The protocol** (sessions, messages, best-path decisions) runs in a **userspace daemon**: FRRouting, BIRD, GoBGP, ExaBGP. The kernel has no BGP implementation.
- **The forwarding** happens in the **kernel**: the daemon installs its chosen routes into the kernel routing table over netlink, and the kernel forwards packets with them.

The kernel references below are from the Linux source tree (6.19-rc8), as `path:line`.

See also: [Anycast and ASNs](bgp-anycast.md), [routing](routing.md), [Kubernetes (Calico)](kubernetes.md#bgp-peering), [VXLAN/EVPN](vxlan.md#control-plane-flood-and-learn-vs-evpn).

## The big picture

```text
        peer router (another AS)
                 │
                 │  TCP port 179: OPEN / UPDATE / KEEPALIVE / NOTIFICATION
                 ▼
┌──────────────────────────── userspace ────────────────────────────┐
│  BGP daemon (FRR bgpd, BIRD, GoBGP)                                │
│                                                                    │
│   Adj-RIB-In ──import policy──▶ Loc-RIB ──export policy──▶ Adj-RIB-Out
│   (routes per peer)            (best path per prefix)   (what we advertise)
│                                    │                               │
│                                    │ install best routes           │
└────────────────────────────────────┼───────────────────────────────┘
                                     │ netlink: RTM_NEWROUTE, rtm_protocol = RTPROT_BGP (186)
┌────────────────────────────────────▼──────────────── kernel ──────┐
│  FIB (fib_trie)  ──longest prefix match──▶  ip_forward() ──▶ out  │
│  nexthop objects, ECMP hashing, VRFs, MPLS                         │
└────────────────────────────────────────────────────────────────────┘
```

- **Control plane:** decides *which* routes exist. That's BGP.
- **Data plane:** moves packets using those routes. That's the kernel (or a switch ASIC the kernel programs).

`ip route` shows only the result of BGP's work: routes tagged `proto bgp` (FRR) or `proto bird` (BIRD).

## Part 1: the protocol

### Autonomous systems

An **autonomous system (AS)** is a network under one routing policy, identified by an **ASN**. BGP exchanges routes **between** ASes (eBGP) and distributes them **inside** one (iBGP). See [What is an ASN](bgp-anycast.md#what-is-an-asn) for ASN ranges.

BGP is a **path-vector** protocol: every route carries the list of ASes it passed through (`AS_PATH`). That list provides both loop prevention (a router rejects a route containing its own ASN) and a coarse distance metric (fewer ASes is better).

| Flavor   | Between                       | Typical use                                                                                |
| -------- | ----------------------------- | ------------------------------------------------------------------------------------------ |
| **eBGP** | Routers in **different** ASes | ISP peering, transit, cloud/on-prem links, data center leaf↔spine (each switch its own AS) |
| **iBGP** | Routers in the **same** AS    | Distributing externally learned routes inside one network                                  |

Key behavioural differences:

- **TTL:** eBGP peers are normally directly connected, so daemons send eBGP with **TTL 1** (`ebgp-multihop` raises it). iBGP peers can be many hops apart, often peering between loopback addresses.
- **NEXT_HOP:** eBGP sets `NEXT_HOP` to itself. iBGP passes it through unchanged, so routers often configure `next-hop-self`.
- **iBGP split horizon:** a route learned from one iBGP peer is **not** advertised to another iBGP peer (inside one AS, `AS_PATH` doesn't grow, so it can't detect loops). This forces either a full mesh of iBGP sessions, **route reflectors** ([RFC 4456](https://www.rfc-editor.org/rfc/rfc4456)), or **confederations** ([RFC 5065](https://www.rfc-editor.org/rfc/rfc5065)).

### Sessions: BGP runs over TCP

BGP has no transport of its own: peers open a normal **TCP connection to port 179**. TCP gives it reliability and ordering, so BGP only sends **changes** (incremental updates), never periodic full tables.

Session states (the finite state machine):

```text
Idle ──▶ Connect ──▶ OpenSent ──▶ OpenConfirm ──▶ Established
            │  ▲                                     │
            ▼  │                                     │ NOTIFICATION / hold timer expires
          Active ◀────────────── (TCP failed) ◀──────┘──▶ Idle
```

| State           | Meaning                                                                                          |
| --------------- | ------------------------------------------------------------------------------------------------ |
| Idle            | Not trying, or waiting before retrying                                                           |
| Connect         | TCP connection in progress                                                                       |
| Active          | TCP failed; listening and retrying (a session stuck in *Active* usually means TCP can't connect) |
| OpenSent        | OPEN sent, waiting for the peer's OPEN                                                           |
| OpenConfirm     | OPENs exchanged, waiting for the first KEEPALIVE                                                 |
| **Established** | Up: routes are exchanged                                                                         |

Timers: each side sends a **KEEPALIVE** every *keepalive* seconds; if nothing arrives within the **hold time**, the session is torn down and all its routes are withdrawn. Classic defaults are 60 / 180 s; data center profiles use 3 / 9 s, and **BFD** ([RFC 5880](https://www.rfc-editor.org/rfc/rfc5880)) detects failures in milliseconds.

### Messages

Every message starts with a 19-byte header:

```text
 0                   1                   2                   3
+---------------------------------------------------------------+
|                    Marker (16 bytes, all 0xff)                 |
+-------------------------------+---------------+---------------+
|          Length (2)           |   Type (1)    |
+-------------------------------+---------------+
```

| Type | Message           | Purpose                                                                                                                  |
| ---- | ----------------- | ------------------------------------------------------------------------------------------------------------------------ |
| 1    | **OPEN**          | Start a session: version (4), my ASN, hold time, BGP identifier (router ID, an IPv4-like 32-bit value), **capabilities** |
| 2    | **UPDATE**        | Withdraw routes and/or advertise routes with their path attributes                                                       |
| 3    | **NOTIFICATION**  | Report an error, then close the session                                                                                  |
| 4    | **KEEPALIVE**     | "Still here" (header only, 19 bytes)                                                                                     |
| 5    | **ROUTE-REFRESH** | Ask the peer to resend its routes (after a policy change)                                                                |

**Capabilities** negotiated in OPEN decide what the session can do. Important ones:

- **Multiprotocol (MP-BGP, [RFC 4760](https://www.rfc-editor.org/rfc/rfc4760))**: carry address families other than IPv4 unicast.
- **4-byte ASNs** ([RFC 6793](https://www.rfc-editor.org/rfc/rfc6793)).
- **Route refresh**, **graceful restart** (keep forwarding while the daemon restarts), **ADD-PATH** (advertise more than one path per prefix), **extended messages** (beyond the 4096-byte limit).

**UPDATE** layout:

```text
+-----------------------------------------------+
| Withdrawn Routes Length (2)                   |
| Withdrawn Routes (prefixes being removed)     |
| Total Path Attribute Length (2)               |
| Path Attributes (apply to all NLRI below)     |
| NLRI (prefixes being advertised)              |
+-----------------------------------------------+
```

One UPDATE can advertise many prefixes that share the same attributes. IPv6 and other families use the `MP_REACH_NLRI` / `MP_UNREACH_NLRI` attributes instead of the classic NLRI field.

### Address families (AFI/SAFI)

MP-BGP identifies what's being carried with an **AFI** (address family) and **SAFI** (subsequent family):

| AFI/SAFI | Family                        | Used for                         |
| -------- | ----------------------------- | -------------------------------- |
| 1/1      | IPv4 unicast                  | The internet, data centers       |
| 2/1      | IPv6 unicast                  | Same, for IPv6                   |
| 1/4      | IPv4 labeled unicast (BGP-LU) | MPLS label distribution          |
| 1/128    | VPNv4                         | MPLS L3VPN                       |
| 25/70    | L2VPN EVPN                    | EVPN-VXLAN, EVPN-MPLS            |
| 1/133    | IPv4 FlowSpec                 | Distributing filter rules (DDoS) |

### Path attributes

| Code   | Attribute                          | Kind                     | Meaning                                                |
| ------ | ---------------------------------- | ------------------------ | ------------------------------------------------------ |
| 1      | `ORIGIN`                           | well-known mandatory     | How the route entered BGP: IGP, EGP, incomplete        |
| 2      | `AS_PATH`                          | well-known mandatory     | ASes the route passed through                          |
| 3      | `NEXT_HOP`                         | well-known mandatory     | Address to forward to                                  |
| 4      | `MULTI_EXIT_DISC` (MED)            | optional non-transitive  | A neighbor's hint for which of its entry points to use |
| 5      | `LOCAL_PREF`                       | well-known (iBGP only)   | Preference inside your AS; higher wins                 |
| 6      | `ATOMIC_AGGREGATE`                 | well-known discretionary | Route was summarised and lost detail                   |
| 7      | `AGGREGATOR`                       | optional transitive      | Who summarised it                                      |
| 8      | `COMMUNITIES`                      | optional transitive      | Tags (`65000:100`) that policies match on              |
| 9, 10  | `ORIGINATOR_ID`, `CLUSTER_LIST`    | optional non-transitive  | Loop prevention for route reflectors                   |
| 14, 15 | `MP_REACH_NLRI`, `MP_UNREACH_NLRI` | optional non-transitive  | Multiprotocol advertise/withdraw                       |
| 16     | Extended communities               | optional transitive      | Route targets for VPNs/EVPN, and more                  |
| 32     | Large communities                  | optional transitive      | 12-byte communities for 4-byte ASNs                    |

*Transitive* attributes are passed along even by routers that don't understand them; *non-transitive* ones are dropped at the next AS.

### RIBs and policy

A BGP speaker keeps three logical tables (**RIB** = routing information base):

1. **Adj-RIB-In** (one per peer): everything the peer advertised, before policy.
2. **Loc-RIB**: after **import policy** (accept, reject, modify attributes), the best path per prefix.
3. **Adj-RIB-Out** (one per peer): what passes **export policy** and is advertised to that peer.

Policy is where BGP's real power lies: filtering prefixes, setting `LOCAL_PREF`, matching communities, prepending `AS_PATH`. Since [RFC 8212](https://www.rfc-editor.org/rfc/rfc8212), eBGP sessions should accept and send **nothing** until a policy explicitly allows it, which is why modern daemons require `import`/`export` filters.

Only the best routes in the Loc-RIB are handed to the kernel. The kernel never sees alternatives, attributes or policy: just `prefix → next hop(s)`.

### Best path selection

When several routes to the same prefix exist, BGP compares them in order (simplified; implementations add a few vendor steps):

1. Highest **weight** (Cisco-local) / highest **LOCAL_PREF**: "I prefer this exit" (policy inside your AS).
2. Locally originated routes.
3. Shortest **AS_PATH**.
4. Lowest **ORIGIN** (IGP < EGP < incomplete).
5. Lowest **MED**.
6. **eBGP over iBGP**.
7. Lowest IGP cost to the next hop ("hot-potato" routing).
8. Tie-breakers: oldest route, lowest router ID, lowest neighbor address.

Equal routes can be installed together as **ECMP** (`maximum-paths` / multipath). BGP never measures latency, bandwidth or congestion.

**Longest prefix match beats all of this.** BGP chooses the best path *per prefix*, but the kernel forwards using the *most specific* prefix: a `/24` wins over a `/16` regardless of attributes. That's the basis of both traffic engineering and hijacks.

Traffic-engineering knobs follow from the list: **LOCAL_PREF** controls how traffic **leaves** your network; **AS-path prepending**, **MED**, more-specific prefixes and **communities** influence how traffic **enters** it.

## Part 2: how Linux carries BGP

### 1. The session is an ordinary TCP socket

The daemon calls `socket()`, `bind()` to port 179, `listen()` and `connect()` like any server. Nothing in the kernel's TCP code is BGP-specific; you can see the sessions with:

```console
$ ss -tnp 'sport = :179 or dport = :179'
State  Recv-Q Send-Q Local Address:Port  Peer Address:Port  Process
ESTAB  0      0      192.0.2.0:179       192.0.2.1:41234    users:(("bird",pid=812,fd=9))
```

What the kernel **does** provide are socket options that exist mainly for BGP: authentication and TTL security.

### 2. Protecting the session: TCP-MD5 and TCP-AO

A BGP session is a long-lived TCP connection between known peers. An attacker who can inject a spoofed TCP RST or a forged UPDATE could tear down or poison it. The fix is to sign every TCP segment with a shared key.

**TCP-MD5** ([RFC 2385](https://www.rfc-editor.org/rfc/rfc2385), "Protection of BGP Sessions via the TCP MD5 Signature Option"), set per peer with `setsockopt(TCP_MD5SIG)`:

```c
/* include/uapi/linux/tcp.h:369 */
struct tcp_md5sig {
	struct __kernel_sockaddr_storage tcpm_addr;	/* address associated */
	__u8	tcpm_flags;				/* extension flags */
	__u8	tcpm_prefixlen;				/* address prefix */
	__u16	tcpm_keylen;				/* key length */
	int	tcpm_ifindex;				/* device index for scope */
	__u8	tcpm_key[TCP_MD5SIG_MAXKEYLEN];		/* key (binary) */
};
```

`tcpm_prefixlen` (with `TCP_MD5SIG_EXT`) lets one key cover a whole range of peers, and `tcpm_ifindex` scopes a key to a VRF.

**TCP-AO** ([RFC 5925](https://www.rfc-editor.org/rfc/rfc5925)) is MD5's successor: stronger algorithms (HMAC-SHA, CMAC-AES), multiple keys with **key rotation** without dropping the session, and per-direction key IDs. Set with `setsockopt(TCP_AO_ADD_KEY)`:

```c
/* include/uapi/linux/tcp.h:396 */
struct tcp_ao_add { /* setsockopt(TCP_AO_ADD_KEY) */
	struct __kernel_sockaddr_storage addr;	/* peer's address for the key */
	char	alg_name[64];		/* crypto hash algorithm to use */
	__s32	ifindex;		/* L3 dev index for VRF */
	__u32   set_current	:1,	/* set key as Current_key at once */
		set_rnext	:1,	/* request it from peer with RNext_key */
		reserved	:30;	/* must be 0 */
	...
	__u8	sndid;			/* SendID for outgoing segments */
	__u8	rcvid;			/* RecvID to match for incoming seg */
	...
};
```

The kernel's own design document says BGP is the intended user (`Documentation/networking/tcp_ao.rst:252`):

> One also should keep in mind that mainly intended users are BGP processes, not any random applications, which means that compared to IPsec tunnels, no transparency is really needed and modern BGP daemons already have `setsockopt()s` for TCP-MD5 support.

and (`tcp_ao.rst:401`):

> For listening sockets the picture is different: BGP server may want to receive both TCP-AO and (deprecated) TCP-MD5 clients.

Counters for failed signatures (`net/ipv4/proc.c:239`, `:303`):

```console
$ nstat -az | grep -E 'TCPMD5|TCPAO'
TcpExtTCPMD5NotFound      0     # segment had no signature, but a key is configured
TcpExtTCPMD5Unexpected    0     # segment signed, but no key configured
TcpExtTCPMD5Failure       0     # wrong signature
TcpExtTCPAOGood           1532
TcpExtTCPAOBad            0
TcpExtTCPAOKeyNotFound    0
```

### 3. TTL security (GTSM) with `IP_MINTTL`

The **Generalized TTL Security Mechanism** ([RFC 5082](https://www.rfc-editor.org/rfc/rfc5082)) uses TTL as cheap protection for directly connected eBGP peers:

- Both peers send with **TTL 255**.
- Each accepts only packets arriving with **TTL ≥ 254**.
- Every router decrements TTL, so a packet from more than one hop away can't arrive with 254 or more. Remote attackers are dropped before TCP even processes the segment.

The daemon sets `setsockopt(IP_MINTTL, 254)` (`include/uapi/linux/in.h:128`). The kernel enforces it in the TCP receive path, before any connection processing (`net/ipv4/tcp_ipv4.c:2283`):

```c
process:
	if (static_branch_unlikely(&ip4_min_ttl)) {
		/* min_ttl can be changed concurrently from do_ip_setsockopt() */
		if (unlikely(iph->ttl < READ_ONCE(inet_sk(sk)->min_ttl))) {
			__NET_INC_STATS(net, LINUX_MIB_TCPMINTTLDROP);
			drop_reason = SKB_DROP_REASON_TCP_MINTTL;
			goto discard_and_relse;
		}
	}
```

The `static_branch_unlikely` makes this check free for systems that never set `IP_MINTTL`. Drops are counted as `TcpExtTCPMinTTLDrop` (`net/ipv4/proc.c:247`). In daemons this is `ttl-security hops 1` (FRR) or `ttl security yes` (BIRD).

### 4. Installing routes: netlink and `RTPROT_BGP`

The daemon installs its best paths by sending **`RTM_NEWROUTE`** messages on a netlink socket, exactly what `ip route add` does. Each message carries a `struct rtmsg` (`include/uapi/linux/rtnetlink.h:244`):

```c
struct rtmsg {
	unsigned char		rtm_family;
	unsigned char		rtm_dst_len;	/* prefix length, e.g. 24 */
	unsigned char		rtm_src_len;
	unsigned char		rtm_tos;

	unsigned char		rtm_table;	/* Routing table id */
	unsigned char		rtm_protocol;	/* Routing protocol; see below	*/
	unsigned char		rtm_scope;	/* See below */
	unsigned char		rtm_type;	/* See below	*/

	unsigned		rtm_flags;
};
```

followed by attributes: `RTA_DST` (prefix), `RTA_GATEWAY` (next hop), `RTA_OIF` (interface), `RTA_PRIORITY` (metric), `RTA_MULTIPATH` (ECMP next hops) or `RTA_NH_ID` (a nexthop object).

**`rtm_protocol` says who installed the route.** BGP has its own value (`rtnetlink.h:291`):

```c
/* Values of protocol >= RTPROT_STATIC are not interpreted by kernel;
   they are just passed from user and back as is.
   It will be used by hypothetical multiple routing daemons.
   Note that protocol values should be standardized in order to
   avoid conflicts.
 */
...
#define RTPROT_ZEBRA		11	/* Zebra */
#define RTPROT_BIRD		12	/* BIRD */
...
#define RTPROT_BGP		186	/* BGP Routes */
#define RTPROT_ISIS		187	/* ISIS Routes */
#define RTPROT_OSPF		188	/* OSPF Routes */
```

The kernel doesn't act on the value; it's a label so daemons can find and clean up their own routes, and so you can filter:

```console
$ ip route show proto bgp          # FRR tags routes with 186 ("bgp")
10.2.0.0/24 nhid 31 via 192.0.2.1 dev eth1 proto bgp metric 20
$ ip route show proto bird         # BIRD tags routes with 12 ("bird"), e.g. Calico
198.19.21.0/26 via 198.19.0.1 dev eth0 proto bird
```

The names come from `/etc/iproute2/rt_protos`; the kernel only stores the number. The `metric 20` on FRR routes is its eBGP administrative distance, written into `RTA_PRIORITY`.

**In the kernel**, `RTM_NEWROUTE` is dispatched to `inet_rtm_newroute()` (registered at `net/ipv4/fib_frontend.c:1695`), which converts the message and inserts it into the FIB (`fib_frontend.c:910`):

```c
static int inet_rtm_newroute(struct sk_buff *skb, struct nlmsghdr *nlh,
			     struct netlink_ext_ack *extack)
{
	...
	err = rtm_to_fib_config(net, skb, nlh, &cfg, extack);
	...
	tb = fib_new_table(net, cfg.fc_table);
	...
	err = fib_table_insert(net, tb, &cfg, extack);
	...
}
```

Every change is broadcast back on the `RTNLGRP_IPV4_ROUTE` netlink group (`rtmsg_fib()`, `net/ipv4/fib_semantics.c:543`), which is how other daemons and `ip monitor` see BGP's work live:

```console
$ ip monitor route
10.2.0.0/24 via 192.0.2.1 dev eth1 proto bgp metric 20
Deleted 10.2.0.0/24 via 192.0.2.1 dev eth1 proto bgp metric 20
```

### 5. Looking routes up at internet scale: `fib_trie`

A full internet table is about **1 million IPv4 routes** (plus ~200k IPv6), and every forwarded packet needs a longest-prefix-match lookup among them. Linux uses an **LC-trie** (level- and path-compressed trie), documented in `Documentation/networking/fib_trie.rst`. The lookup is `fib_table_lookup()` (`net/ipv4/fib_trie.c:1420`):

```c
	/* Step 1: Travel to the longest prefix match in the trie */
	...
	/* Step 2: Sort out leaves and begin backtracing for longest prefix */
```

Path compression skips bits where the trie has no branches, and level compression turns dense regions into wide child arrays, so a lookup touches only a handful of nodes even with a million prefixes. This is why a plain Linux box can hold full BGP tables.

### 6. ECMP: several BGP paths at once

With `maximum-paths` (FRR) or `merge paths` (BIRD), the daemon installs one route with several next hops. For each flow the kernel picks one by hashing, in `fib_select_path()` (`net/ipv4/fib_semantics.c:2209`):

```c
#ifdef CONFIG_IP_ROUTE_MULTIPATH
	if (fib_info_num_path(res->fi) > 1) {
		int h = fib_multipath_hash(net, fl4, skb, NULL);

		fib_select_multipath(res, h, fl4);
	}
```

`fib_select_multipath()` (`fib_semantics.c:2165`) skips next hops whose link is down or whose neighbour entry is dead, then picks the one whose hash range contains the flow's hash. Hashing per flow keeps each TCP connection on one path, avoiding reordering.

Controls (`Documentation/networking/ip-sysctl.rst:102`):

```text
fib_multipath_hash_policy - INTEGER
	- 0 - Layer 3                      (src/dst IP)
	- 1 - Layer 4                      (5-tuple: spreads flows between the same hosts)
	- 2 - Layer 3 or inner Layer 3 if present   (look inside tunnels)
	- 3 - Custom multipath hash (fields from fib_multipath_hash_fields)
fib_multipath_use_neigh - BOOLEAN
	Use status of existing neighbor entry when determining nexthop for multipath routes.
```

```console
$ ip route show 10.2.0.0/24
10.2.0.0/24 proto bgp metric 20
	nexthop via 192.0.2.1 dev eth1 weight 1
	nexthop via 192.0.2.3 dev eth2 weight 1
```

This is the data plane behind the **BGP-in-the-data-center** design: a leaf learns the same prefix from every spine and spreads flows across all of them.

### 7. Nexthop objects: surviving BGP churn

Without them, every route carries its own copy of its next hops. If a peer carrying 900,000 prefixes goes down, the daemon must update 900,000 routes one by one.

**Nexthop objects** (`net/ipv4/nexthop.c`, netlink `RTM_NEWNEXTHOP`) split routes from next hops: routes point at a shared ID, and changing the nexthop group updates every route that uses it at once. FRR uses them by default (`nhid 31` in the output above):

```console
$ ip nexthop show
id 21 via 192.0.2.1 dev eth1 scope link proto zebra
id 22 via 192.0.2.3 dev eth2 scope link proto zebra
id 31 group 21/22 proto zebra
```

**Resilient groups** (`Documentation/networking/nexthop-group-resilient.rst`) go further. Classic ECMP uses the hash-threshold algorithm (RFC 2992): when one next hop disappears, hash ranges are recomputed and flows using *other* next hops move too:

```text
             +-------+-------+-------+-------+-------+
             |   1   |   2   |   3   |   4   |   5   |
             +-------+-+-----+---+---+-----+-+-------+
             |    1    |    2    |    4    |    5    |
             +---------+---------+---------+---------+

              Before and after deletion of next hop 3
	      under the hash-threshold algorithm.
```

Resilient groups put a hash table of buckets in between, so only the failed next hop's buckets move:

> If a multipath group is used for load-balancing between multiple servers, this hash space reassignment causes an issue that packets from a single flow suddenly end up arriving at a server that does not expect them. This can result in TCP connections being reset.

That matters for **anycast load balancing**, where many servers announce the same VIP over BGP and ECMP spreads clients across them.

### 8. Route types BGP uses besides unicast

`rtm_type` (`rtnetlink.h:259`) isn't just "forward":

```c
	RTN_UNICAST,		/* Gateway or direct route	*/
	...
	RTN_BLACKHOLE,		/* Drop				*/
	RTN_UNREACHABLE,	/* Destination is unreachable   */
	RTN_PROHIBIT,		/* Administratively prohibited	*/
```

- **Aggregate/discard routes:** when a router advertises a summary (e.g. `10.0.0.0/8`) it installs `blackhole 10.0.0.0/8` locally, so traffic for unused parts of the summary is dropped instead of looping. Calico does this per IPAM block: `blackhole 198.19.22.128/26 proto bird` in the [Kubernetes notes](kubernetes.md#the-hosts-routing-table).
- **RTBH (remote-triggered black hole):** a BGP route tagged with a blackhole community is installed as `RTN_BLACKHOLE`, dropping traffic to a DDoS target at the edge.

### 9. VRFs: separate routing tables per tenant

A **VRF** (`drivers/net/vrf.c`, `Documentation/networking/vrf.rst`) binds interfaces to their own routing table:

```text
	 +-----------------------------+
	 |           vrf-blue          |  ===> route table 10
	 +-----------------------------+
	    |        |            |
	 +------+ +------+     +-------------+
	 | eth1 | | eth2 | ... |    bond1    |
	 +------+ +------+     +-------------+
```

BGP daemons run one BGP instance per VRF (`router bgp 65000 vrf blue` in FRR), bind the session socket to the VRF device, and install routes with `rtm_table = 10`. This is how MPLS L3VPN and EVPN type-5 keep customers' routes apart on one box.

The docs warn about mixing VRF and global listeners with authentication (`vrf.rst:148`):

> With that sysctl value [`tcp_l3mdev_accept=1`], it is unspecified which listening socket will be selected to handle connections for VRF traffic ... This somewhat unexpected behavior can lead to problems if sockets are configured with extra options (ex. TCP MD5 keys) ...

That's why `tcp_md5sig.tcpm_ifindex` and `tcp_ao_add.ifindex` exist: keys can be scoped to a VRF.

### 10. MPLS labels from BGP

For BGP labeled unicast and L3VPN, routes carry MPLS labels. Linux has an MPLS forwarding plane (`net/mpls/af_mpls.c`) programmed through the same `RTM_NEWROUTE` messages with `AF_MPLS`, enabled by sizing the label table (`Documentation/networking/mpls-sysctl.rst`):

```text
platform_labels - INTEGER
	Number of entries in the platform label table.  It is not
	possible to configure forwarding for label values equal to or
	greater than the number of platform labels.
```

```sh
modprobe mpls_router
sysctl -w net.mpls.platform_labels=100000
sysctl -w net.mpls.conf.eth1.input=1
```

### 11. Hardware switches: offload and traps

On Linux-based switches (SONiC, Cumulus, switchdev drivers like Mellanox `mlxsw` or Marvell `prestera`) the daemon still installs routes into the kernel. The driver listens to FIB notifications and copies routes into the switch ASIC, marking them with flags (`rtnetlink.h:346`):

```c
#define RTM_F_OFFLOAD		0x4000	/* route is offloaded */
#define RTM_F_TRAP		0x8000	/* route is trapping packets */
#define RTM_F_OFFLOAD_FAILED	0x20000000 /* route offload failed ... */
```

`ip route` then shows `offload` or `trap` on those routes.

The ASIC forwards data packets in hardware, but **BGP's own packets** must reach the daemon on the CPU. The kernel defines generic **devlink traps** for exactly this (`include/net/devlink.h:1158`, `net/devlink/trap.c:1004`):

```c
#define DEVLINK_TRAP_GENERIC_NAME_IPV4_BGP \
	"ipv4_bgp"
#define DEVLINK_TRAP_GENERIC_NAME_IPV6_BGP \
	"ipv6_bgp"
```

The `mlxsw` driver traps them to the CPU (`drivers/net/ethernet/mellanox/mlxsw/spectrum_trap.c:1025`):

```c
	{
		.trap = MLXSW_SP_TRAP_CONTROL(IPV4_BGP, BGP, TRAP),
		.listeners_arr = {
			MLXSW_SP_RXL_MARK(IPV4_BGP, BGP, TRAP_TO_CPU, false),
		},
	},
```

These traps are rate-limited by **policers** per trap group (`devlink trap group show`), so a flood aimed at port 179 can't starve the CPU and take down real BGP sessions.

## Part 3: lab with two routers in namespaces

Two routers in separate ASes, connected by a `/31`, each announcing one prefix. This uses BIRD 2 because it runs easily per namespace; it has not been run here, so treat it as a starting point.

```text
   r1 (AS 65001)                               r2 (AS 65002)
   dummy0 10.1.0.1/24                          dummy0 10.2.0.1/24
   veth-r1 192.0.2.0/31  ─────── eBGP ───────  veth-r2 192.0.2.1/31
```

**Topology:**

```sh
ip netns add r1; ip netns add r2
ip link add veth-r1 netns r1 type veth peer name veth-r2 netns r2

ip -n r1 addr add 192.0.2.0/31 dev veth-r1; ip -n r1 link set veth-r1 up
ip -n r2 addr add 192.0.2.1/31 dev veth-r2; ip -n r2 link set veth-r2 up

# the prefix each router "owns"
ip -n r1 link add dummy0 type dummy; ip -n r1 addr add 10.1.0.1/24 dev dummy0; ip -n r1 link set dummy0 up
ip -n r2 link add dummy0 type dummy; ip -n r2 addr add 10.2.0.1/24 dev dummy0; ip -n r2 link set dummy0 up

ip netns exec r1 sysctl -w net.ipv4.ip_forward=1
ip netns exec r2 sysctl -w net.ipv4.ip_forward=1
```

**`r1.conf`** (`r2.conf` is the mirror image: router id `10.2.0.1`, `local 192.0.2.1 as 65002`, `neighbor 192.0.2.0 as 65001`):

```text
router id 10.1.0.1;

protocol device { }

# originate the dummy0 prefix into BGP
protocol direct { ipv4; interface "dummy0"; }

# install BGP-learned routes into the kernel FIB (they appear as "proto bird")
protocol kernel { ipv4 { export where source = RTS_BGP; }; }

protocol bgp r2 {
	local 192.0.2.0 as 65001;
	neighbor 192.0.2.1 as 65002;
	password "s3cret";        # TCP-MD5 (setsockopt TCP_MD5SIG)
	ttl security yes;         # GTSM (TTL 255 out, IP_MINTTL 254 in)
	ipv4 { import all; export where source = RTS_DEVICE; };
}
```

**Run and verify:**

```sh
ip netns exec r1 bird -c r1.conf -s /run/bird-r1.ctl
ip netns exec r2 bird -c r2.conf -s /run/bird-r2.ctl

birdc -s /run/bird-r1.ctl show protocols        # r2 ... Established
birdc -s /run/bird-r1.ctl show route            # 10.2.0.0/24 via 192.0.2.1, [AS65002i]

ip -n r1 route show proto bird                  # 10.2.0.0/24 via 192.0.2.1 dev veth-r1 proto bird metric 32
ip netns exec r1 ss -tnp 'sport = :179 or dport = :179'
ip netns exec r1 nstat -az TcpExtTCPMD5Failure TcpExtTCPMinTTLDrop
ip netns exec r1 ping -c1 -I 10.1.0.1 10.2.0.1  # traffic now follows the BGP route
```

**Experiments:**

- Delete `dummy0` on r2 and watch `ip -n r1 monitor route`: BGP withdraws the prefix and the kernel route disappears.
- Change one side's `password`: the session never reaches *Established*, and `TCPMD5Failure` (or `TCPMD5NotFound`) increases.
- Capture the session: `ip netns exec r1 tcpdump -ni veth-r1 -vv port 179` shows OPEN, KEEPALIVE and UPDATE messages, with `ttl 255` on GTSM sessions.

## Part 4: use cases

### On the internet

1. **Interconnecting autonomous systems.** BGP is the protocol that glues the internet together: every ISP, cloud, CDN, university and large enterprise announces its prefixes to neighbors and learns everyone else's.
2. **Transit and peering.**
   - *Transit:* you pay an upstream ISP to carry your traffic to the whole internet (they send you a full table or a default route).
   - *Peering:* two networks exchange traffic for their own customers directly, often settlement-free, privately or at an **IXP** (Internet Exchange Point) via a **route server**.
3. **Multihoming.** An organization with its own ASN and provider-independent prefix connects to two or more ISPs and announces the same prefix to both: if one ISP fails, BGP withdraws that path and traffic shifts to the other. Combined with LOCAL_PREF/prepending it also gives **inbound/outbound load balancing** across links.
4. **Anycast.** The same prefix is announced from many locations and BGP delivers each client to the nearest one (see [Anycast](bgp-anycast.md#what-is-anycast)): DNS (root servers, `1.1.1.1`, `8.8.8.8`), CDNs, edge platforms like [Fly.io](fly.md), NTP, and DDoS absorption — attack traffic spreads across all sites instead of hitting one.
5. **DDoS mitigation.**
   - **RTBH** (Remote Triggered Black Hole): announce the attacked `/32` with a blackhole community; upstreams drop traffic to it at their edge (sacrificing the target to save the rest).
   - **BGP FlowSpec** (RFC 8955): distribute fine-grained filter rules (match src/dst/port/protocol → drop or rate-limit) to edge routers via BGP.
   - **Scrubbing services** (Cloudflare Magic Transit, Akamai Prolexic, …): during an attack the scrubbing provider announces your prefix (often a more specific one), pulls the traffic in, cleans it, and tunnels clean traffic back to you (GRE).
6. **Traffic engineering.** Steering which links carry which traffic: AS-path prepending, MED, selective or more-specific announcements, and **BGP communities** (tags such as "don't advertise to Europe" or "set LOCAL_PREF 80 at your side" that upstreams act on).
7. **Route security.** Because BGP trusts announcements by default, it is the subject of **hijacks** and **route leaks** (e.g. the 2008 Pakistan Telecom/YouTube hijack, the 2021 Facebook outage where its prefixes were withdrawn). Defenses: **RPKI** route-origin validation (the `rpki-ov-state: valid` in [the `whois` output](bgp-anycast.md#looking-up-an-ips-origin-asn)), IRR filtering, prefix limits, and **BGPsec/ASPA**.
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

| Software  | Typical use                                                                  | Kernel `proto`                        |
| --------- | ---------------------------------------------------------------------------- | ------------------------------------- |
| FRRouting | General Linux routing suite (`bgpd` + `zebra`); SONiC, Cumulus, host routing | `bgp` (186)                           |
| BIRD      | IXP route servers, Calico's per-node BGP daemon                              | `bird` (12)                           |
| GoBGP     | Programmable BGP in Go; MetalLB, Cilium                                      | usually doesn't install routes itself |
| ExaBGP    | Scriptable announcer for VIPs, health-checked anycast, FlowSpec              | doesn't install routes                |
| OpenBGPD  | OpenBSD's BGP daemon                                                         | —                                     |

## Kernel source map

| Topic                                                    | Where (Linux 6.19-rc8)                                                                                              |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `RTPROT_BGP`, `struct rtmsg`, route types, offload flags | `include/uapi/linux/rtnetlink.h:244`, `:259`, `:312`, `:346`                                                        |
| Netlink route add → FIB                                  | `net/ipv4/fib_frontend.c:910` (`inet_rtm_newroute`), `:1695` (registration)                                         |
| Route change notifications                               | `net/ipv4/fib_semantics.c:543` (`rtmsg_fib`)                                                                        |
| Longest-prefix-match lookup                              | `net/ipv4/fib_trie.c:1420` (`fib_table_lookup`), `Documentation/networking/fib_trie.rst`                            |
| ECMP selection and hashing                               | `net/ipv4/fib_semantics.c:2165`, `:2209`; `net/ipv4/route.c:2066`; `ip-sysctl.rst:102`                              |
| Nexthop objects, resilient groups                        | `net/ipv4/nexthop.c`; `Documentation/networking/nexthop-group-resilient.rst`                                        |
| TCP-MD5 / TCP-AO                                         | `include/uapi/linux/tcp.h:369`, `:396`; `Documentation/networking/tcp_ao.rst`                                       |
| GTSM (`IP_MINTTL`)                                       | `include/uapi/linux/in.h:128`; `net/ipv4/ip_sockglue.c:1033`; `net/ipv4/tcp_ipv4.c:2283`                            |
| Counters                                                 | `net/ipv4/proc.c:239` (MD5), `:247` (MinTTL), `:303` (AO)                                                           |
| VRF                                                      | `drivers/net/vrf.c`; `Documentation/networking/vrf.rst`                                                             |
| MPLS                                                     | `net/mpls/af_mpls.c`; `Documentation/networking/mpls-sysctl.rst`                                                    |
| Hardware BGP traps                                       | `include/net/devlink.h:1158`; `net/devlink/trap.c:1004`; `drivers/net/ethernet/mellanox/mlxsw/spectrum_trap.c:1025` |
| Selftests to read                                        | `tools/testing/selftests/net/fib_nexthops.sh`, `tools/testing/selftests/net/tcp_ao/`, `vrf_route_leaking.sh`        |

## Further reading

- [RFC 4271: BGP-4](https://www.rfc-editor.org/rfc/rfc4271)
- [RFC 7938: Use of BGP for routing in large-scale data centers](https://www.rfc-editor.org/rfc/rfc7938)
- [RFC 5082: GTSM](https://www.rfc-editor.org/rfc/rfc5082)
- [RFC 5925: TCP Authentication Option](https://www.rfc-editor.org/rfc/rfc5925)
- [FRRouting documentation](https://docs.frrouting.org/)
- [BIRD documentation](https://bird.network.cz/?get_doc&f=bird.html&v=20)
