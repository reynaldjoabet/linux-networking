# IP Addressing

## Subnet masks and route specificity

- A **subnet mask** marks which part of an IP address is network (1 bits) and which is host (0 bits). It lets a host decide whether an IP it needs to reach is **local** (talk directly) or **remote** (use a gateway).
- The **default route** `0.0.0.0/0` sends all otherwise-unmatched packets to the ISP/gateway.
- The larger the prefix, the more specific the route: `/31` is more specific than `/30`.
  - `/32` — most specific; a single IP address.
  - `/0` — least specific; all IP addresses.

## Private IPv4 ranges (RFC 1918)

IPv4 has a 32-bit address space (≈ 4.3 billion addresses). To conserve space, there are private ranges:

| Class | Network       | Range                         | Default mask          |
| ----- | ------------- | ----------------------------- | --------------------- |
| A     | `10.0.0.0`    | 10.0.0.0 – 10.255.255.255     | `255.0.0.0` (`/8`)    |
| B     | `172.16.0.0`  | 172.16.0.0 – 172.31.255.255   | `255.240.0.0` (`/12`) |
| C     | `192.168.0.0` | 192.168.0.0 – 192.168.255.255 | `255.255.0.0` (`/16`) |

These are **not** globally unique — millions of networks reuse them. That's why NAT exists: many private networks share the same internal addresses but appear as one public IP on the internet. VPNs are made up of private IPs too.

### The `172.16.0.0/12` block

The private Class B block is **16 contiguous Class B (`/16`) networks**, 172.16.0.0 → 172.31.255.255, written as a single `/12`:

```text
172.16.0.0/16
172.17.0.0/16
172.18.0.0/16
...
172.31.0.0/16
```

- Each `/16` contains 65,536 addresses (≈ 65K hosts); the combined `/12` has 1,048,576.
- Used in private enterprise networks, VPNs and cloud VPCs (e.g. AWS's default VPC uses `172.31.0.0/16`).
- The first 12 bits are fixed: `10101100.0001xxxx.xxxxxxxx.xxxxxxxx`. The variable `xxxx` (4 bits) selects which `/16` (16 to 31).

## IPv6 Unique Local Addresses (ULA)

IPv6 defines **Unique Local Addresses** in `fc00::/7` ([RFC 4193](https://www.rfc-editor.org/rfc/rfc4193)) — the IPv6 equivalent of private IPv4, but better. Each ULA prefix includes **40 bits of random global ID**, making collisions between independent networks astronomically unlikely.

`fc00:abcd:1234::/48` and `fc00:dead:beef::/48` are both ULAs — both "private", yet unique enough that two organizations are almost guaranteed not to generate the same prefix by accident. So while two networks can share the same private IPv4 address, private IPv6 addresses are (practically) globally unique. Fly.io's `fdaa::/16` is an example — see [Fly.io](fly.md).

## Point-to-point `/31`

See [Tunneling: point-to-point links and `/31`](tunnel.md#point-to-point-links-and-31).


The VPC CIDR creates the local route automatically. When you create a VPC with `172.16.0.0/16`, every route table in that VPC gets a route you can't delete:
```sh
Destination       Target
172.16.0.0/16     local        ← automatic: "any address in the VPC is reachable inside the VPC"
```
Subnets are slices of that CIDR, each in one Availability Zone:
```sh
subnet-a   172.16.0.0/24   us-east-1a
subnet-b   172.16.1.0/24   us-east-1b
```
Each subnet is associated with a route table. That's where you add everything else:
```sh
Public route table                     Private route table
172.16.0.0/16   local                  172.16.0.0/16   local
0.0.0.0/0       igw-123  (internet)    0.0.0.0/0       nat-456  (NAT gateway)
10.0.0.0/8      tgw-789  (on-prem)
```

That's the whole router configuration. Reaching other subnets works out of the box because of the `local` route. The internet, NAT, peering, VPN, and Transit Gateway each need an extra route you add


### What the instance sees
Reserved addresses. AWS reserves five addresses in every subnet; for `172.16.0.0/24`:

| Address | Use                                     |
| ------- | --------------------------------------- |
| `.0`    | Network address                         |
| `.1`    | VPC router (the subnet's gateway)       |
| `.2`    | DNS resolver                            |
| `.3`    | Reserved                                |
| `.255`  | Broadcast (not supported, but reserved) |

DHCP config. The instance gets its IP, the `/24` mask, and `.1` as its router via DHCP, so its OS table is just:
```sh
default via 172.16.0.1 dev eth0
172.16.0.0/24 dev eth0 scope link
```

The instance never sees the `/16`. That route lives in the VPC route table, not in the OS

###  What happens to a packet
Instance A (`172.16.0.5`, subnet-a) pings instance B (`172.16.1.5`, subnet-b):

```sh
Instance A OS                       AWS (Nitro / VPC fabric)                       Instance B
─────────────                       ────────────────────────                       ──────────
1. 172.16.1.5 not in my /24
   → send to gateway 172.16.0.1
2. ARP "who has 172.16.0.1?"  ──▶   3. Hypervisor answers ARP itself
                                       (there is no real L2 segment)
4. Frame to the gateway MAC   ──▶   5. Look up subnet-a's route table:
                                       172.16.1.5 matches 172.16.0.0/16 → local
                                    6. Mapping service: which physical host
                                       holds the ENI with 172.16.1.5?
                                    7. Security group / NACL checks
                                    8. Encapsulate and send across the
                                       data center network to that host   ──▶  9. Delivered to B's ENI
```

The `.1 `"router" is virtual. The hypervisor (Nitro card) on each host answers ARP and makes the routing decision using the VPC route table plus a mapping database of which ENI owns which IP. It then tunnels the packet to the right physical server

That's also why a VPC has no broadcast or multicast, and why even same-subnet traffic goes through the hypervisor

### Building the same thing yourself on Linux
A plain Linux router with one leg in each subnet behaves the same way:

```sh
# Router: one interface (or VLAN, or bridge) per subnet
ip addr add 172.16.0.1/24 dev eth1     # gateway for subnet-a
ip addr add 172.16.1.1/24 dev eth2     # gateway for subnet-b
sysctl -w net.ipv4.ip_forward=1

# Internet access for the whole "VPC"
ip route add default via 203.0.113.1 dev eth0
iptables -t nat -A POSTROUTING -s 172.16.0.0/16 -o eth0 -j MASQUERADE
```

The router's table now shows connected routes, created automatically when the IPs were added:

```sh
172.16.0.0/24 dev eth1 proto kernel scope link src 172.16.0.1
172.16.1.0/24 dev eth2 proto kernel scope link src 172.16.1.1
default via 203.0.113.1 dev eth0
```

```sh
VPC 172.16.0.0/16
│
├── Internet gateway (igw-123)      ← attached to the VPC; on its own it routes nothing
│
├── Main route table                ← default: used by any subnet with no explicit association
│     172.16.0.0/16  local
│
├── Public route table (custom)
│     172.16.0.0/16  local
│     0.0.0.0/0      igw-123
│
├── subnet-a 172.16.0.0/24  ──associated──▶ Public route table   → "public subnet"
└── subnet-b 172.16.1.0/24  (no association) → Main route table   → "private subnet"
```
- Every subnet uses exactly one route table. That's either one you explicitly associate, or the main table by default.
- A route table can serve many subnets.

```sh
Destination       Target       Purpose
172.16.0.0/16     local        Anything inside the VPC: deliver it within the VPC fabric
10.0.0.0/8        tgw-789      On-prem / other VPCs: send to the Transit Gateway
0.0.0.0/0         igw-123      Everything else (the internet): send to the Internet Gateway
```

`via `doesn't decide whether you ARP; it decides whose MAC you ARP for. `if the outgoing interface needs an Ethernet header, the kernel needs the next hop's MAC`


## How a packet uses the routing table

Host A has two routes: default via `172.16.0.1` and `172.16.0.0/24 dev eth0 scope link`. Step through what happens when `A` pings a host in its own subnet, and a host in another subnet behind a Linux router.

## Same Subnet:A->B
![alt text](image-13.png)

![alt text](image-14.png)

![alt text](image-15.png)

![alt text](image-16.png)

![alt text](image-17.png)

![alt text](image-18.png)

## Other Subnet: A->C
![alt text](image-23.png)

![alt text](image-24.png)

![alt text](image-25.png)

![alt text](image-26.png)

![alt text](image-27.png)

![alt text](image-28.png)

![alt text](image-29.png)

![alt text](image-30.png)

![alt text](image-31.png)

`"next hop" means the machine on your link that receives the frame next`

```sh
Destination on another subnet:   A ──▶ router ──▶ … ──▶ C
                                   next hop = router (the first machine in the chain)

Destination on my subnet:        A ──▶ B
                                   next hop = B (the first machine in the chain is also the last)
```

```c
static inline __be32 rt_nexthop(const struct rtable *rt, __be32 daddr)
{
	if (rt->rt_gw_family == AF_INET)
		return rt->rt_gw4;
	return daddr;
}
```

- If the route has a gateway (`via X`), the next hop is the gateway address.
- If not (a directly connected route), the next hop is the destination itself, which gets ARP-resolved on the local link.

```sh
$ ip route get 172.16.0.9
172.16.0.9 dev eth0 src 172.16.0.5                  ← next hop = 172.16.0.9 itself
$ ip route get 172.16.1.5
172.16.1.5 via 172.16.0.1 dev eth0 src 172.16.0.5   ← next hop = 172.16.0.1
```
![alt text](image-32.png)

TTL (Time To Live) is a counter in the IP header that every router decrements by 1 when it forwards a packet. It exists to stop packets from circling forever when there's a routing loop.

Why it dropped from 64 to 63 in the table:
- `A → B`: the frame only passed through the bridge br0. A bridge works at layer 2 and never looks at the IP header, so the TTL stays 64.
- `A → C`: the packet was routed once, by the Linux router between the two subnets. Forwarding decrements the TTL, so C receives 63. Each additional router would subtract another 1.

The problem TTL solves: routes are configured independently on every router, so mistakes happen. If router X sends `10.0.0.0/8` to Y and Y sends it back to X, a packet would bounce between them forever, and every new packet would add to the load until the link saturated. With TTL, each lap costs 1. When a router would forward a packet with TTL 1, it drops the packet instead and sends an ICMP "Time Exceeded" message back to the sender

### one Linux router joining the 4 subnets
```sh
            internet
               │ eth0 203.0.113.10
        ┌──────┴──────┐
        │   router    │  ip_forward = 1
        └┬────┬────┬─┬┘
     eth1│eth2│eth3│ │eth4
  10.0.0.1 10.64.0.1 10.128.0.1 10.192.0.1
     │   │      │      │
subnet 1 subnet 2 subnet 3 subnet 4
```
### The router
```sh
ip addr add 10.0.0.1/10   dev eth1
ip addr add 10.64.0.1/10  dev eth2
ip addr add 10.128.0.1/10 dev eth3
ip addr add 10.192.0.1/10 dev eth4
ip route add default via 203.0.113.1 dev eth0
sysctl -w net.ipv4.ip_forward=1
```
```sh
$ ip route
default via 203.0.113.1 dev eth0
10.0.0.0/10 dev eth1 proto kernel scope link src 10.0.0.1
10.64.0.0/10 dev eth2 proto kernel scope link src 10.64.0.1
10.128.0.0/10 dev eth3 proto kernel scope link src 10.128.0.1
10.192.0.0/10 dev eth4 proto kernel scope link src 10.192.0.1
203.0.113.0/24 dev eth0 proto kernel scope link src 203.0.113.10
```

The four `/10` routes appeared automatically (`proto kernel`) when the addresses were assigned. Only `default` was typed by hand. 

### A host in each subnet
Every host has the same shape of table: its own subnet plus a default route to its subnet's gateway.

```sh
# host 10.0.0.20 (subnet 1)
default via 10.0.0.1 dev eth0
10.0.0.0/10 dev eth0 proto kernel scope link src 10.0.0.20

# host 10.64.0.20 (subnet 2)
default via 10.64.0.1 dev eth0
10.64.0.0/10 dev eth0 proto kernel scope link src 10.64.0.20

# host 10.128.0.20 (subnet 3)
default via 10.128.0.1 dev eth0
10.128.0.0/10 dev eth0 proto kernel scope link src 10.128.0.20

# host 10.192.0.20 (subnet 4)
default via 10.192.0.1 dev eth0
10.192.0.0/10 dev eth0 proto kernel scope link src 10.192.0.20
```

### Inter-subnet NAT for the internet
Because `10.x` is private, the router also needs one NAT rule covering all four subnets at once:

```sh
iptables -t nat -A POSTROUTING -s 10.0.0.0/8 -o eth0 -j MASQUERADE
```

### Same network split across two routers
If two routers each own two subnets, joined by a /31 link, each router has its own two connected routes plus one summary route to the other half:

```sh
# router 1: owns subnets 1-2, link 192.168.255.0/31
10.0.0.0/10  dev eth1 proto kernel scope link
10.64.0.0/10 dev eth2 proto kernel scope link
10.128.0.0/9 via 192.168.255.1 dev eth3      ← subnets 3+4 summarised as one /9
default via 203.0.113.1 dev eth0

# router 2: owns subnets 3-4
10.128.0.0/10 dev eth1 proto kernel scope link
10.192.0.0/10 dev eth2 proto kernel scope link
10.0.0.0/9 via 192.168.255.0 dev eth3        ← subnets 1+2 summarised as one /9
default via 192.168.255.0 dev eth3
```

`ip addr add 10.192.0.12/10 dev eth4 only means: "this machine's address on eth4 is 10.192.0.12".`

An address is a gateway only when other machines send their traffic to it. That's set on the other machines, with `default via`:
```sh
Router                                   Host in subnet 4
──────                                   ────────────────
ip addr add 10.192.0.12/10 dev eth4      ip addr add 10.192.0.20/10 dev eth0
  "my address is .12"                      "my address is .20"

                                         ip route add default via 10.192.0.12
                                           "send everything else to .12"   ◀── this makes .12 the gateway
```

A router has several local-link routes, one per subnet it's attached to, plus routes `via` other routers for networks further away.

A few routes without via aren't ordinary local links:
- Point-to-point interfaces: `10.0.0.0/30 dev tun0 or 10.10.11.0/24 dev wg0`. Still "directly on this link", but the link is a tunnel, so there's no ARP; packets go straight into the tunnel.
- Your own addresses: the kernel's hidden `local` table holds routes like `local 10.192.0.1 dev eth4 scope host `(view with `ip route show table local`). These deliver to the machine itself, not out of an interface.
- Special route types that go nowhere: `blackhole 10.99.0.0/16` (silently drop), `unreachable` …, `prohibit` …. Calico's `blackhole 198.19.22.128/26 proto bird` in your Kubernetes notes is one of these.