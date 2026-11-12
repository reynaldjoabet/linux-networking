# linux-networking

Notes on Linux networking — from Ethernet frames and bridges up through netfilter, tunnels, overlays, containers and cloud networks.

## Contents

### Foundations

- [Ethernet](ethernet.md) — frame structure, EtherType, MTU, physical encoding
- [IP addressing](ip-addressing.md) — subnet masks, RFC 1918 ranges, IPv6 ULAs
- [TCP](tcp.md) — what TCP adds over IP, sockets, HTTP/1.1 → 2 → 3
- [OS basics](os-basics.md) — file descriptors, virtual memory

### Linux networking stack

- [Routing and the IPv4 packet path](routing.md) — NIC to socket, netfilter hooks, routes and ARP, kernel source
- [iptables](iptables.md) — tables, chains, NAT, recipes
- [Connection tracking](conntrack.md) — the conntrack table and what happens when it fills
- [Network namespaces](linux-namespaces.md) — hands-on labs with veth pairs and bridges
- [Linux bridge](linux-bridge.md) — switching, ebtables/brouting, hands-on
- [Linux bridge internals](bridging.md) — `rx_handler`, FDB, `BR_FDB_LOCAL`, kernel walkthroughs
- [VLAN](vlan.md) — 802.1Q, access/trunk ports, bridge VLAN filtering, QinQ
- [TUN/TAP](tuntap.md) — userspace networking, VMs on TAP + bridge

### Tunnels and overlays

- [Tunneling](tunnel.md) — IPIP, GRE, VXLAN, GRETAP, point-to-point links
- [VXLAN](vxlan.md) — VTEPs, VNIs, frame format, Linux configs, Kubernetes
- [VXLAN lab: two Linux hosts on Azure](linux-vxlan.md)
- [WireGuard](wireguard.md) — cryptokey routing, site-to-site and peer-to-site
- [SSH](ssh.md) — how SSH works, port forwarding, bastions, ngrok/Cloudflare Tunnel

### Internet routing

- [BGP](bgp.md) — protocol, how Linux carries it (netlink, ECMP, TCP-MD5/AO, GTSM, VRFs), labs, use cases
- [Anycast and ASNs](bgp-anycast.md)

### Containers and orchestration

- [Docker](docker.md) — namespaces, cgroups, OverlayFS, image layers, build cache
- [Kubernetes networking](kubernetes.md) — pod/node/service CIDRs, CNIs (Calico, Flannel, AWS VPC CNI), cluster DNS vs CoreDNS
- [Kafka](kafka.md) — listeners, Docker Compose and its NAT rules

### Cloud

- [Cloud networking](cloud-networking.md) — virtual networks, AWS VPC, OVHcloud
- [Azure](azure.md) and [Cosmos DB request units](azure.ru.md)
- [Fly.io](fly.md) — 6PN private network, Flycast, Fly Proxy, `flyctl`
- [Build your own cloud provider](cloudprovider.md)

### Other

- [Wi-Fi and radio frequencies](wifi.md)
- [Gitflow and CI](gitflow.md)
- [Reading list](book.md)

## Further reading

Network virtualization (sobyte):

- [veth pair](https://www.sobyte.net/post/2022-04/network-virtualization-veth-pair/)
- [Bridge](https://www.sobyte.net/post/2022-04/network-virtualization-bridge/)
- [TUN/TAP](https://www.sobyte.net/post/2022-04/network-virtualization-tun-tap/)
- [TUN mode](https://www.sobyte.net/post/2022-04/tun-mode/)
- [Understanding netfilter and iptables](https://www.sobyte.net/post/2022-04/understanding-netfilter-and-iptables/)
- [Docker copy](https://www.sobyte.net/post/2022-04/docker-copy/)

Container fundamentals (sobyte):

- [Learn containers with the OCI spec](https://www.sobyte.net/post/2022-04/container-fundamentals-learn-container-with-oci-spec/)
- [Process isolation using namespaces](https://www.sobyte.net/post/2022-04/container-fundamentals-process-isolation-using-namespace/)
- [Resource limitation using cgroups](https://www.sobyte.net/post/2022-04/container-fundamentals-resource-limitation-using-cgroups/)
- [Permission control using capabilities](https://www.sobyte.net/post/2022-04/container-fundamentals-permission-control-using-capabilities/)
- [Filesystem isolation and sharing](https://www.sobyte.net/post/2022-04/container-fundamentals-filesystem-isolation-and-sharing/)

Videos:

- [How Virtual Networks Work (and Why Your Kernel is Being Scammed)](https://www.youtube.com/watch?v=P2vY_RHvZUM)
- [How Linux networking works](https://www.youtube.com/watch?v=I81L2WRMczA&pp=2AYJ)

## Misc

sbt: start the sbt server on the local network over TCP (for a second machine, "M2"):

```scala
Global / serverConnectionType := ConnectionType.Tcp
```
