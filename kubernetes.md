# Kubernetes Networking

## The core rules

- Every pod gets its own IP address, from the cluster's **Pod CIDR** range.
- Each pod has its own networking environment, isolated from the host using network namespaces.
- **Every pod can reach every other pod by IP, without NAT** — on any node.

See also: [VXLAN in Kubernetes](vxlan.md#vxlan-in-kubernetes).

## Address ranges

### Pod CIDR (pod IP range)

- IP range used to assign IPs to pods; every pod gets one IP.
- Pods talk to each other directly (no NAT, by design).
- One or more Pod CIDRs may exist (per-node Pod CIDRs).

```text
10.244.0.0/16   (Flannel default)
192.168.0.0/16
100.64.0.0/10
```

### Node CIDR (node / host network)

- IPs assigned to Kubernetes nodes — usually your cloud VPC / subnet.
- Carries kubelet ↔ API server, node ↔ node traffic, ingress/egress.
- Pods reach the outside world via node IPs (often with SNAT).

```text
10.0.0.0/16
172.16.0.0/12
```

### Service CIDR

Must not overlap with the Pod CIDR or the Node CIDR — but Service IPs are **virtual**, not routed.

**Where it is set.** The range is a control-plane setting, fixed when the cluster is created:

| Setup | Setting | Default |
|---|---|---|
| Any cluster | `kube-apiserver --service-cluster-ip-range` (and the same flag on `kube-controller-manager`) | none |
| kubeadm | `ClusterConfiguration.networking.serviceSubnet` | `10.96.0.0/12` |
| EKS | `kubernetesNetworkConfig.serviceIpv4Cidr` | `10.100.0.0/16` or `172.20.0.0/16` |
| GKE | `--services-ipv4-cidr` | picked from the VPC |
| AKS | `--service-cidr` | `10.0.0.0/16` |

```yaml
# kubeadm config
apiVersion: kubeadm.k8s.io/v1beta4
kind: ClusterConfiguration
networking:
  serviceSubnet: 10.96.0.0/12     # → --service-cluster-ip-range on kube-apiserver
  podSubnet: 10.244.0.0/16
  dnsDomain: cluster.local        # → kubelet clusterDomain
```

**How a Service gets its IP.** When you create a Service without `spec.clusterIP`, the API server allocates a free address from the range and stores it in the Service object. Nothing else chooses it, and it stays until the Service is deleted. A few addresses are fixed by convention:

| IP (kubeadm) | Service | Why |
|---|---|---|
| `10.96.0.1` | `default/kubernetes` | The API server always takes the first address |
| `10.96.0.10` | `kube-system/kube-dns` | kubeadm sets `clusterIP` explicitly to the 10th address and writes the same IP into kubelet's `clusterDNS` |
| anything else | your Services | Allocated automatically (e.g. `orders` → `10.96.4.7`) |

To pin an address yourself, set `spec.clusterIP` to a free IP in the range. Automatic allocation prefers the upper part of the range, which keeps the lower part free for pinned IPs like these.

**Finding the range on an existing cluster:**

```sh
kubectl get servicecidr                                           # Kubernetes 1.33+: the range as an API object
kubectl -n kube-system get cm kubeadm-config -o yaml | grep serviceSubnet    # kubeadm clusters
kubectl -n kube-system get pod -l component=kube-apiserver -o yaml | grep service-cluster-ip-range

# Works anywhere: ask for an impossible IP and read the error
kubectl create service clusterip probe --tcp=80 --clusterip=1.1.1.1 --dry-run=server
# → ... provided IP is not in the valid range. The range of valid IPs is 10.96.0.0/12
```

### Splitting the Pod CIDR per node

If the cluster Pod CIDR is `172.16.0.0/12`, Kubernetes (or the CNI) splits it:

```text
Node    Pod CIDR
node-1  172.16.1.0/24
node-2  172.16.2.0/24
node-3  172.16.3.0/24
```

### No overlaps allowed

Pod CIDRs must **not** overlap: each pod IP must be globally unique in the cluster, otherwise "every pod reaches every pod by IP without NAT" is impossible. Who enforces it?

- **Kubernetes control plane** — `kube-controller-manager` allocates a unique Pod CIDR per node (with `--allocate-node-cidrs=true`).
- **CNI plugin** — validates and programs routes; refuses invalid setups.
- **Cloud provider / routing layer** — often enforces uniqueness via routing tables or BGP.

> Because pods and nodes cannot have overlapping IPs, if your physical network already uses the `172.x` range, you are forced to use `10.x.x.x` or `192.168.x.x` for your pods.

### Sizing

- There are 256 `/24` networks inside `10.0.0.0/16`.
- There are 4,096 `/24` networks inside `172.16.0.0/12`.
- A `/16` (e.g. `10.96.0.0/16`) = 65,536 IPs.
- `10.96.0.0/12` (the Service CIDR default) = 1,048,576 IPs — a `/12` is 16× larger than a `/16`.

## Pod → pod on another node

```text
Node CIDR: 10.0.0.0/16
Pod CIDR:  172.16.0.0/12 (split per node)

# routes on node-A
172.16.2.0/24 via 10.0.0.12 dev eth0
172.16.3.0/24 via 10.0.0.13 dev eth0
# If the destination IP is 172.16.2.x, send it to node-B's node IP 10.0.0.12
```

Scenario: Pod A on node-A (`172.16.1.10`) → Pod B on node-B (`172.16.2.20`):

```text
Pod A (172.16.1.10)
  ↓
node-A routing table:
  172.16.2.0/24 → via 10.0.0.12
  ↓
packet sent to node-B (10.0.0.12)
  ↓
node-B delivers packet to pod B (172.16.2.20)
```

No NAT — pure L3 routing. A node knows an IP belongs to a pod on another node because the CNI installed explicit routes for every other node's Pod CIDR (`172.16.2.0/24 → next hop 10.0.0.12`). There is no ARP across nodes, no broadcast, no guessing.

**Why this scales:** each node needs one route per **node**, not per pod; `/24` (or similar) Pod CIDRs keep routing tables small.

### How packets are carried between nodes

Depends on the CNI implementation:

| Approach                         | How                                                                                                   | Used by                                                     |
| -------------------------------- | ----------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| **Overlay** (VXLAN / Geneve)     | Node wraps the pod packet in another packet: outer uses the node CIDR (`10.0.0.x`), inner the pod CIDR (`172.16.x.x`) | Flannel (VXLAN), Calico (VXLAN mode)                        |
| **Native routing** (no overlay)  | Pod IPs are directly routable; nodes advertise pod CIDRs via BGP or cloud routing tables              | Calico (BGP mode), Cilium (direct routing), cloud-provider routing |

## CNI plugins

CNI (Container Network Interface) is a standard API that lets different network implementations plug into Kubernetes. Kubernetes calls it whenever a pod is created or destroyed. Two types:

- **CNI network plugins** — add/delete pods to/from the pod network: create/delete each pod's network interface and connect/disconnect it to the rest of the network.
- **CNI IPAM plugins** — allocate and release pod IP addresses. Depending on the plugin, this may mean allocating CIDRs to each node, or obtaining IPs from the underlying cloud network.

### Flannel vs Calico

- Flannel usually uses a **Linux bridge** (with an overlay).
- Calico usually does **not** use a bridge — it uses **routing**.

This architectural difference is the main reason Calico is generally faster and more scalable than Flannel. See [Flannel vs Calico: L2 vs L3 networking](https://medium.com/@jain.sm/flannel-vs-calico-a-battle-of-l2-vs-l3-based-networking-5a30cd0a3ebd#:~:text=Since%20container%20B%20is%20not,a%20physical%20network%20using%20UDP.).

## Calico

### Pods as veth pairs, nodes as routers

For every pod, Calico creates a **veth pair**:

- one end sits inside the pod as `eth0`;
- the other sits on the host with an algorithmically generated name starting with `cali` (e.g. `cali7bd2a3f`).

Calico sets up the host network namespace so that each node acts as a **virtual router**, with the local pods connected to it. With 50 pods on a node, `ip addr` shows 50 `cali*` interfaces:

```text
1: lo: <LOOPBACK,UP,LOWER_UP> ...
2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP>  <-- Your actual physical wire
3: tunl0@NONE: <NOARP,UP,LOWER_UP>          <-- The IPIP tunnel
4: cali669f123: <BROADCAST,MULTICAST,UP>    <-- Pod A's "tail"
5: cali992a884: <BROADCAST,MULTICAST,UP>    <-- Pod B's "tail"
6: calibbc4511: <BROADCAST,MULTICAST,UP>    <-- Pod C's "tail"
... and so on for every Pod ...
```

Without aggregation the routing table gets very large: 100 nodes × 100 pods = 10,000 entries on every host (one per pod). This is why Calico uses **BGP** to sync routes efficiently.

### Local pods: routes vs neighbors

A destination IP is local to the node if `ip route get <DEST_IP>` returns a route like `dev caliXXXX scope link`.

- **Routing** answers *where* to send.
- **ARP / neighbor lookup** answers *to which* interface/MAC.

```console
$ ip neigh show
172.16.1.10 dev cali1234 lladdr 7a:aa:... REACHABLE
172.16.1.20 dev cali5678 lladdr 6b:bb:... REACHABLE
```

One route per Pod CIDR, many neighbor entries (one per pod).

### The pod's view

```console
$ ip -c link show up
1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN mode DEFAULT group default qlen 1000
    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
3: eth0@if9: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1410 qdisc noqueue state UP mode DEFAULT group default
    link/ether 3a:0c:14:d0:92:89 brd ff:ff:ff:ff:ff:ff link-netnsid 0
```

- `eth0` links to the host network namespace (`link-netnsid 0`): it's the pod's side of the veth pair.
- `@if9` is the interface number of the other end of the pair, in the host's namespace (interface 9).

The pod's routes:

```console
$ ip route
default via 169.254.1.1 dev eth0
169.254.1.1 dev eth0  scope link
```

The pod's default route is out over `eth0`: any traffic not for itself goes over `eth0`.

| Route                              | Purpose                                     |
| ---------------------------------- | ------------------------------------------- |
| `169.254.1.1 dev eth0 scope link`  | Makes the gateway itself reachable          |
| `default via 169.254.1.1 dev eth0` | Sends all non-local traffic via the gateway |

Why both are needed — say the pod sends to `8.8.8.8`:

1. Is there a route for `8.8.8.8`? ❌ → use the default route: send via `169.254.1.1` on `eth0`.
2. Now: "How do I actually reach `169.254.1.1`?" → that's the second route, `169.254.1.1 dev eth0 scope link`.

Without it, Linux wouldn't know how to reach `169.254.1.1` and the default route would be unusable.

### The host's routing table

```text
default via 192.168.44.65 dev eth0 proto dhcp src 192.168.44.75 metric 100
192.168.44.64/28 dev eth0 proto kernel scope link src 192.168.44.75
192.168.44.65 dev eth0 proto dhcp scope link src 192.168.44.75 metric 100
198.19.0.0/20 dev eth0 proto kernel scope link src 198.19.0.2
198.19.21.0/26 via 198.19.0.1 dev eth0 proto bird
198.19.21.64/26 via 198.19.0.3 dev eth0 proto bird
198.19.22.128 dev cali1eaab2bfc77 scope link
blackhole 198.19.22.128/26 proto bird
198.19.22.129 dev cali9c9ee09e807 scope link
198.19.22.130 dev calid35188eb0ba scope link
198.19.22.131 dev calif0a98285df9 scope link
198.19.22.132 dev caliea2aa288365 scope link
```

- Routes labeled **`proto bird`** are routes to pods on other nodes that Calico learned over BGP.
- `cali*` `scope link` routes are local pods.
- **Route aggregation:** Calico aggregates routes when possible (`/26` here). The `/26` is the default block size Calico IPAM allocates on demand as nodes need pod IPs.

Example — destination `198.19.21.42` matches `198.19.21.0/26 via 198.19.0.1 dev eth0 proto bird`. Linux now needs to reach `198.19.0.1`; the route `198.19.0.0/20 dev eth0 proto kernel scope link src 198.19.0.2` says it's directly reachable on `eth0`.

`dev eth0` means Linux puts the packet on the wire via `eth0` as the egress interface. The kernel can decide where a packet goes (routing) and what to do with it (firewalling, NAT), but it can't move bits anywhere without an interface.

With WireGuard encryption enabled, Calico adds a `wireguard.cali` interface:

```text
10: wireguard.cali: <POINTOPOINT,NOARP,UP,LOWER_UP> mtu 1400 qdisc noqueue state UNKNOWN group default qlen 1000
    inet 198.19.22.131/32 brd 198.19.22.131 scope global wireguard.cali
```

### BGP peering

BGP is a standards-based protocol for sharing routes across a network, supported by most routers and a fundamental building block of the internet with exceptional scaling characteristics. Calico configures each Linux host to act as a router, programming routes to local pods in the host namespace. Using BGP, Calico can share these routes with other BGP-capable routers in the underlying network — typically on-premise or private cloud, rather than public cloud. Pods then become first-class citizens on the network and overlays are avoided. See [BGP, ASNs and Anycast](bgp-anycast.md).

### Calico on AWS: overlays

AWS networking only knows the ENI IP addresses, nothing about where pods are. If a pod sends traffic to a pod on another node, it's routed out of `eth0` to the AWS network — which doesn't know where to send it, because it isn't aware of pod IPs. The traditional fix is an **overlay**:

- Calico supports VXLAN and IPIP overlays, implemented as virtual interfaces in the Linux kernel.
- A packet to a pod on another node is encapsulated (VXLAN or IPIP) in an outer packet using the **node** IPs, hiding the pod IPs. AWS handles it like any node-to-node traffic.
- The receiving node decapsulates it and delivers the original packet to the destination pod.

So with Calico, **Pod IP ≠ VPC IP**; with the AWS VPC CNI, **Pod IP = VPC IP**.

## Cloud CNIs

Cloud networking (AWS, GCP, Azure) is pure L3 routing: subnets are routed, route tables decide the next hop, no user-visible bridges. Example AWS route table:

```text
172.31.0.0/16 → local
0.0.0.0/0     → igw-12345
```

### Amazon VPC CNI

| Model      | Bridge | How it works                                                     | Policy                        |
| ---------- | ------ | ---------------------------------------------------------------- | ----------------------------- |
| L3 routing | No     | Pods get VPC IPs; AWS route tables handle routing. No overlay.   | Security groups / NACLs       |

- The host side of each pod veth pair is named `eni*` rather than `cali*`.
- Pods get IPs from the underlying **VPC subnet**, configured as **secondary IPs on the instance's ENIs**. When a node's pods exceed the secondary IPs an ENI supports, additional ENIs are attached automatically.
- The Linux routing table routes each pod's traffic via the ENI associated with its IP: e.g. pod A → pod C goes via `eth0` on node 1, while pod B → pod E goes via `eth1` on node 1.
- Because AWS already knows the pod IPs, traffic can be routed across VPC subnet boundaries without an overlay.

#### ENIs

An **ENI** (Elastic Network Interface) is a virtual NIC on an EC2 instance. An instance can have one primary ENI (`eth0`), multiple secondary ENIs (`eth1`, `eth2`, …), and multiple IPs per ENI. Each ENI has exactly **one primary private IP** (mandatory, special at the EC2/OS level) and zero or more secondary private IPs.

```text
eth0 (primary ENI)
  primary IP:    10.0.0.10   ← mandatory (node IP)
  secondary IP:  10.0.0.21   (pod)
  secondary IP:  10.0.0.22   (pod)

eth1 (secondary ENI)
  primary IP:    10.0.0.30   (pod)
  secondary IP:  10.0.0.31   (pod)
  secondary IP:  10.0.0.32   (pod)
```

- The first ENI attached is the primary ENI; it becomes `eth0`, and its primary IP is the instance identity — **the node's IP is always the primary private IP of `eth0`**.
- `eth0` is where the node itself lives: SSH connects there, kubelet talks to the API server, the default route exists.
- A node with 15 ENIs has 15 primary IPs, but only `eth0`'s is reserved for the node. Primary IPs on secondary ENIs are **not** node IPs and can be used by pods.
- Other ENIs exist because a single interface can hold only a limited number of IPs; attaching more ENIs lets AWS attach more IPs to the same node.

```text
VPC Subnet: 10.0.0.0/24
│
├─ Node A
│   ├─ eth0 primary IP  → 10.0.0.10  (node IP)
│   ├─ eth0 secondary   → 10.0.0.21  (pod)
│   ├─ eth1 primary     → 10.0.0.30  (pod)
│   └─ eth1 secondary   → 10.0.0.31  (pod)
│
├─ Node B
│   ├─ eth0 primary IP  → 10.0.0.11  (node IP)
│   └─ eth0 secondary   → 10.0.0.41  (pod)
```

#### Max pods per node

Each ENI has a fixed IP limit and each instance type a fixed number of ENIs:

| Instance type | Max ENIs | IPs per ENI |
| ------------- | -------- | ----------- |
| t3.small      | 3        | 4           |
| t3.medium     | 3        | 6           |
| m5.large      | 3        | 10          |
| m5.xlarge     | 4        | 15          |
| c5.2xlarge    | 4        | 15          |

The commonly quoted formula is `(max ENIs) × (IPs per ENI − 1)`, reserving each ENI's primary IP. Per the notes above, though, only `eth0`'s primary IP is truly reserved for the node; the AWS VPC CNI accounts for this automatically.

Worked example — **m5.large** (3 ENIs × 10 IPs = 30 raw IPs):

| ENI    | IP usage      |
| ------ | ------------- |
| `eth0` | 1 IP → node   |
| `eth0` | 9 IPs → pods  |
| `eth1` | 10 IPs → pods |
| `eth2` | 10 IPs → pods |

Total pod IPs: `9 + 10 + 10 = 29`.

### Azure CNI (classic & overlay)

| Model      | Bridge | How it works                                                  |
| ---------- | ------ | ------------------------------------------------------------- |
| L3 routing | No     | Pod IPs are routable in the VNet; the Azure fabric handles next hops. |

## Pod IP routability outside the cluster

An important distinguishing feature of network implementations is whether pod IPs are routable **outside** the cluster.

### Not routable

- **Outbound:** when a pod connects to an IP outside the cluster, Kubernetes uses **SNAT** to change the source IP from the pod's to the node's. Return packets are mapped back automatically. The pod is unaware; the destination sees the node as the source; the broader network never sees pod IPs.
- **Inbound:** something outside the cluster can only reach a pod via Kubernetes **Services** or **Ingress** — the broader network doesn't know how to route to pod IPs.
- With an overlay network, pod IPs are normally not routable outside the cluster.

### Routable (BGP)

With BGP (see [Calico BGP peering](#bgp-peering)), nodes share pod routes with the underlying network, making pod IPs routable outside the cluster.

## Cluster DNS vs CoreDNS

**Cluster DNS** is the *what*: a Kubernetes contract that every pod can resolve Services (and some pods) by name, e.g. `my-svc.my-ns.svc.cluster.local`. **CoreDNS** is the *how*: the DNS server that implements that contract in most clusters today.

| | Cluster DNS | CoreDNS |
|---|---|---|
| What it is | A cluster add-on role defined by the [Kubernetes DNS spec](https://github.com/kubernetes/dns/blob/master/docs/specification.md) | A general-purpose DNS server (CNCF project, Go, plugin-based) |
| Where it lives | A Service IP plus kubelet config | A Deployment of pods in `kube-system` |
| Replaceable? | No — workloads depend on the names | Yes — kube-dns (older), NodeLocal DNSCache, or a cloud DNS can serve it |
| Configured by | kubelet `clusterDNS` / `clusterDomain`, pod `dnsPolicy` / `dnsConfig` | The `Corefile` in the `coredns` ConfigMap |
| Exists outside Kubernetes? | No | Yes — it can serve any zone |

### The pieces

- **The DNS Service.** A ClusterIP Service in `kube-system`, by convention the 10th address of the Service CIDR (`10.96.0.10` with kubeadm, `10.100.0.10` or `172.20.0.10` on EKS). It is still named **`kube-dns`** even when CoreDNS is behind it — kept for compatibility after CoreDNS replaced kube-dns as the default (Kubernetes 1.13).
- **kubelet.** Its `clusterDNS` setting is that Service IP and `clusterDomain` is usually `cluster.local`. kubelet writes them into every pod's `/etc/resolv.conf`.
- **CoreDNS pods.** The endpoints behind the `kube-dns` Service. Its `kubernetes` plugin watches the API server for Services and EndpointSlices and answers from memory; anything else is forwarded upstream.

```sh
kubectl -n kube-system get svc kube-dns            # the cluster DNS IP
kubectl -n kube-system get deploy coredns          # the implementation
kubectl -n kube-system get cm coredns -o yaml      # the Corefile
```

### Where each setting lives

There is no per-namespace DNS object. Settings are per cluster, per node, or per pod:

| Setting | Scope | Set in |
|---|---|---|
| Corefile (plugins, upstreams, cache, stub zones) | Cluster | `coredns` ConfigMap in `kube-system`; one CoreDNS for everyone |
| Cluster domain (`cluster.local`) | Cluster (set on each node, must match) | kubelet `clusterDomain` |
| DNS server IP (`10.96.0.10`) | Cluster (set on each node) | kubelet `clusterDNS` |
| Node search domains, upstream resolvers | Node | Node's `/etc/resolv.conf` (kubelet `resolvConf`) |
| `search` list | Pod | Generated by kubelet: `<pod-ns>.svc.<domain> svc.<domain> <domain>` + node's search domains |
| `dnsPolicy`, `dnsConfig` (`ndots`, extra nameservers/searches) | Pod | Pod spec, usually via the Deployment's pod template |

The namespace only affects the first `search` entry. For namespace-wide defaults (e.g. `ndots: 2` for every pod in `payments`), set `dnsConfig` in each workload, or have an admission policy (Kyverno, Gatekeeper, a mutating webhook) inject it.

### A pod's `/etc/resolv.conf`

```text
nameserver 10.96.0.10
search default.svc.cluster.local svc.cluster.local cluster.local
options ndots:5
```

- `nameserver` is the cluster DNS Service IP, not a CoreDNS pod IP.
- `search` lists the endings the resolver tries on short names ([see below](#search-domains-how-short-names-resolve)).
- `ndots:5` decides whether those endings are tried before or after the bare name ([see below](#ndots-endings-first-or-bare-name-first)).

### Where a name like `orders.shop.svc.cluster.local` comes from

Nobody writes DNS records in Kubernetes. You create a Service; CoreDNS's `kubernetes` plugin watches the API server and **derives** the name from the Service's metadata:

```text
orders . shop . svc . cluster.local
  │       │      │        │
  │       │      │        └── cluster domain: kubelet clusterDomain + the zone in the Corefile's `kubernetes` line
  │       │      └── fixed label: "this is a Service" (pods get `pod`)
  │       └── metadata.namespace of the Service
  └── metadata.name of the Service
```

The answer (`10.96.4.7`) is the Service's ClusterIP, which the API server picks from the [Service CIDR](#service-cidr) when the Service is created.

The configs behind every example in this section:

```yaml
# --- namespace "shop": the orders Service, and the client pod doing the lookups
apiVersion: v1
kind: Namespace
metadata:
  name: shop
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orders
  namespace: shop
spec:
  replicas: 2
  selector:
    matchLabels: { app: orders }
  template:
    metadata:
      labels: { app: orders }
    spec:
      containers:
        - name: orders
          image: nginx:1.27
          ports:
            - name: http
              containerPort: 80
---
apiVersion: v1
kind: Service
metadata:
  name: orders          # → orders.shop.svc.cluster.local
  namespace: shop
spec:
  selector: { app: orders }
  # clusterIP: 10.96.4.7   # optional; normally left out and assigned automatically
  ports:
    - name: http        # named port → SRV record _http._tcp.orders.shop.svc.cluster.local
      port: 80
      targetPort: http
---
apiVersion: v1
kind: Pod
metadata:
  name: client          # the pod whose lookups are traced below
  namespace: shop       # → kubelet writes "search shop.svc.cluster.local ..." into its resolv.conf
spec:
  # dnsPolicy: ClusterFirst   # the default; this is what makes kubelet write the cluster resolv.conf
  containers:
    - name: tools
      image: nicolaka/netshoot:v0.13
      command: ["sleep", "infinity"]
---
# --- namespace "payments": the billing Service, reached from shop as "billing.payments"
apiVersion: v1
kind: Namespace
metadata:
  name: payments
---
apiVersion: v1
kind: Service
metadata:
  name: billing         # → billing.payments.svc.cluster.local
  namespace: payments
spec:
  selector: { app: billing }
  ports:
    - port: 80
```

`api.stripe.com` needs no config: it isn't in `cluster.local`, so CoreDNS's `forward` plugin passes it to the node's upstream resolver.

The cluster-wide parts come from kubelet on every node (`/var/lib/kubelet/config.yaml`) and from the [Corefile](#the-corefile):

```yaml
# KubeletConfiguration (excerpt)
clusterDomain: cluster.local     # the last part of every name, and of every search ending
clusterDNS:
  - 10.96.0.10                   # the kube-dns Service IP → "nameserver" in each pod's resolv.conf
resolvConf: /etc/resolv.conf     # the node's file; its search domains are appended to the pod's
```

```text
kubernetes cluster.local in-addr.arpa ip6.arpa {   # Corefile: answer this zone from the API
```

Check each piece:

```sh
kubectl apply -f dns-demo.yaml                     # the manifests above, saved to a file
kubectl -n shop get svc orders                     # CLUSTER-IP is the A record
kubectl -n shop exec client -- cat /etc/resolv.conf
kubectl -n shop exec client -- dig +search orders            # follows the search list
kubectl -n shop exec client -- dig +search billing.payments
kubectl -n shop exec client -- dig orders.shop.svc.cluster.local
```

**The `dev`/`prod` example** below is the same idea twice: a Service with the same name in two namespaces, and an app that only uses the short name.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: dev        # → postgres.dev.svc.cluster.local   (prod: same manifest, namespace: prod)
spec:
  selector: { app: postgres }
  ports:
    - port: 5432
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api
  namespace: dev        # deploy the identical manifest to prod
spec:
  selector:
    matchLabels: { app: api }
  template:
    metadata:
      labels: { app: api }
    spec:
      containers:
        - name: api
          image: example/api:1.0
          env:
            - name: DB_HOST
              value: postgres   # short name: first search ending is <own namespace>.svc.cluster.local
```

### Search domains: how short names resolve

**DNS servers only understand full names.** CoreDNS has a record for `orders.shop.svc.cluster.local`; ask it for `orders` and it answers NXDOMAIN ("no such name"). It never guesses.

Your app just says `orders`, so something must turn that into the full name. That is **the resolver inside the pod** (the libc code behind `getaddrinfo()`), not CoreDNS. It **guesses**: it appends endings to the name, asks the server about each guess, and stops at the first one that exists. The endings come from the `search` line in `/etc/resolv.conf`. This is standard resolver behaviour (`man resolv.conf`): an office network with `search corp.example.com` lets you `ssh fileserver`. Kubernetes uses the same mechanism with cluster endings.

For a pod in namespace `shop` (`search shop.svc.cluster.local svc.cluster.local cluster.local`), each line below is a real DNS query, sent one at a time:

**The app asks for `orders`**

```text
query 1: orders.shop.svc.cluster.local   → 10.96.4.7     STOP
```

The first guess is right: `orders` means "the `orders` Service in my own namespace".

**The app asks for `billing.payments`**

```text
query 1: billing.payments.shop.svc.cluster.local  → NXDOMAIN
query 2: billing.payments.svc.cluster.local       → 10.96.9.2   STOP
```

The first ending produced a name that doesn't exist, so the resolver moved on to the next.

**The app asks for `api.stripe.com`**

The resolver can't tell an internet name from a cluster name, so it does the same thing:

```text
query 1: api.stripe.com.shop.svc.cluster.local   → NXDOMAIN
query 2: api.stripe.com.svc.cluster.local        → NXDOMAIN
query 3: api.stripe.com.cluster.local            → NXDOMAIN
query 4: api.stripe.com                          → 54.187.x.x  STOP
```

Three wasted queries: the endings only make sense for cluster names, but they were tried anyway.

Which ending each short form needs:

| You type | Ending that matches | Resolves as |
|---|---|---|
| `orders` | `shop.svc.cluster.local` | `orders.shop.svc.cluster.local`: a Service in **your own** namespace |
| `billing.payments` | `svc.cluster.local` | `billing.payments.svc.cluster.local`: a Service in **another** namespace |
| `billing.payments.svc` | `cluster.local` | `billing.payments.svc.cluster.local` |

kubelet builds the first ending from the pod's own namespace. So the same config (`DB_HOST=postgres`) reaches `postgres` in `dev` when the pod runs in `dev`, and `postgres` in `prod` when it runs in `prod`. Environments are wired up by namespace, with no config changes.

The node's own search domains are appended too (on EC2, `ec2.internal` or `<region>.compute.internal`), so pods often have 4–6 endings.

### ndots: endings first, or bare name first?

Trying endings first is right for `orders` but wrong for `api.stripe.com`. `ndots` is how the resolver picks the order, by counting the dots in the name:

| The name has… | The resolver tries… |
|---|---|
| fewer dots than `ndots` | each search ending, in order, then the name as-is |
| `ndots` or more dots | the name as-is first, then the search endings if that fails |
| a trailing dot (`api.stripe.com.`) | only the name as-is, never the search list |

`ndots` never stops a name from resolving. It only changes the order, and so how many wasted queries come before the right one.

The whole procedure:

```python
def resolve(name):
    if name.endswith("."):                       # fully qualified: no guessing
        return ask_dns(name)

    guesses = [name + "." + s for s in search_domains]
    if name.count(".") < ndots:
        order = guesses + [name]                 # endings first
    else:
        order = [name] + guesses                 # bare name first

    for candidate in order:
        answer = ask_dns(candidate)              # a real query to CoreDNS
        if answer:
            return answer
    return "not found"
```

The search list provides the endings, `ndots` decides whether they go before or after the bare name, and CoreDNS only ever answers exact full names.

**Why Kubernetes picks 5.** Every short cluster name should hit the search list first, including the longest:

| Name | Dots | With ndots:5 |
|---|---|---|
| `orders` | 0 | endings first ✓ |
| `orders.shop` | 1 | endings first ✓ |
| `orders.shop.svc` | 2 | endings first ✓ |
| `_http._tcp.orders.shop.svc` (SRV) | 4 | endings first ✓ |

The side effect: most internet names (`api.stripe.com` has 2 dots, `s3.us-east-1.amazonaws.com` has 3) are also treated as short, which gives the `api.stripe.com` sequence above.

**The cost.** Many resolvers send A (IPv4) and AAAA (IPv6) queries in parallel, so one external lookup can cost up to 8 queries, plus 2 more for each node search domain. That is a large share of the load on CoreDNS in busy clusters, and it is where the conntrack races from [the packet path](#the-packet-path) tend to show up.

**What lowering it to 2 does:**

| Name | Dots | ndots:5 | ndots:2 |
|---|---|---|---|
| `orders` | 0 | endings first (hit) | endings first (hit) |
| `orders.shop` | 1 | endings first (hit) | endings first (hit) |
| `orders.shop.svc` | 2 | endings first (hit) | as-is first: miss (goes upstream), *then* endings (hit) |
| `api.stripe.com` | 2 | 3 misses, then hit | as-is first: **hit immediately** |

External names get fast; cluster names with 2+ dots pay one extra miss but still resolve. If your apps use `orders` or `orders.shop` internally and full domains externally, ndots:2 is nearly all gain.

Mitigations:

- **Trailing dot** (you control the hostname): `api.stripe.com.` is fully qualified and skips the search list entirely. It is the cheapest fix and changes nothing else.
- **Lower `ndots` per pod** (the name is buried in a library or SDK):

```yaml
spec:
  dnsConfig:
    options:
      - name: ndots
        value: "2"
```

- **NodeLocal DNSCache** (neither is practical): the extra queries still happen, but they are answered from a cache on the node (see [the packet path](#the-packet-path)).

### The Corefile

```text
.:53 {
    errors
    health
    ready
    kubernetes cluster.local in-addr.arpa ip6.arpa {
        pods insecure
        fallthrough in-addr.arpa ip6.arpa
    }
    prometheus :9153
    forward . /etc/resolv.conf     # upstream = the node's resolver
    cache 30
    loop
    reload
    loadbalance
}
```

`kubernetes` answers `cluster.local`; `forward` sends everything else to the node's resolvers (in the cloud, usually the VPC resolver).

### What names exist

Using the `shop` namespace from [above](#where-a-name-like-ordersshopsvcclusterlocal-comes-from):

| Name | Answer | Created by |
|---|---|---|
| `orders.shop.svc.cluster.local` | A/AAAA: the Service's ClusterIP | a normal Service |
| `db.shop.svc.cluster.local` | A/AAAA: every ready pod IP | a headless Service (`clusterIP: None`) |
| `db-0.db.shop.svc.cluster.local` | A: one StatefulSet pod | a StatefulSet whose `serviceName` is the headless Service |
| `_http._tcp.orders.shop.svc.cluster.local` | SRV: port 80 and target `orders.shop.svc.cluster.local` | a Service port with `name: http` (see `orders` above) |
| `10-244-1-5.shop.pod.cluster.local` | A: `10.244.1.5` (dashed pod IP) | any pod, if the Corefile has `pods insecure` |
| `stripe.shop.svc.cluster.local` | CNAME `api.stripe.com` | an ExternalName Service |

```yaml
# headless Service + StatefulSet → db.shop... and db-0.db.shop...
apiVersion: v1
kind: Service
metadata:
  name: db
  namespace: shop
spec:
  clusterIP: None       # headless: DNS returns pod IPs, no virtual IP
  selector: { app: db }
  ports:
    - port: 5432
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: db              # pods are named db-0, db-1, ...
  namespace: shop
spec:
  serviceName: db       # must name the headless Service → db-0.db.shop.svc.cluster.local
  replicas: 2
  selector:
    matchLabels: { app: db }
  template:
    metadata:
      labels: { app: db }
    spec:
      containers:
        - name: postgres
          image: postgres:16
          env:
            - name: POSTGRES_PASSWORD
              value: example
---
# ExternalName → stripe.shop.svc.cluster.local is a CNAME to api.stripe.com
apiVersion: v1
kind: Service
metadata:
  name: stripe
  namespace: shop
spec:
  type: ExternalName
  externalName: api.stripe.com
```

```sh
kubectl -n shop exec client -- dig +short db.shop.svc.cluster.local          # several pod IPs
kubectl -n shop exec client -- dig +short db-0.db.shop.svc.cluster.local
kubectl -n shop exec client -- dig SRV _http._tcp.orders.shop.svc.cluster.local
kubectl -n shop exec client -- dig stripe.shop.svc.cluster.local             # CNAME, then A
```

### `dnsPolicy`

| Policy | Pod uses |
|---|---|
| `ClusterFirst` (default) | Cluster DNS; non-cluster names forwarded upstream |
| `ClusterFirstWithHostNet` | Same, for `hostNetwork: true` pods |
| `Default` | The node's `/etc/resolv.conf` — no cluster names |
| `None` | Only what `dnsConfig` specifies |

Note that `Default` is *not* the default; `ClusterFirst` is. A `hostNetwork: true` pod with `ClusterFirst` silently falls back to `Default` behaviour.

### The packet path

1. The app calls `getaddrinfo()`; the libc resolver sends UDP to `10.96.0.10:53`.
2. kube-proxy's iptables/IPVS rules (or Cilium's eBPF) DNAT it to one CoreDNS pod IP — the same mechanism as any [Service](#service-cidr).
3. CoreDNS answers from its cache of the API, or forwards upstream.
4. [conntrack](conntrack.md) reverses the DNAT on the reply.

Two well-known problems come from step 2: the UDP **conntrack insert race** (A and AAAA queries sent in parallel from the same socket; one gets dropped, causing 5 s timeouts) and **conntrack table pressure** on busy nodes. **NodeLocal DNSCache** fixes both: a CoreDNS-based cache runs as a DaemonSet on every node, listens on a link-local address (`169.254.20.10`), and is reached without DNAT or conntrack. It is still part of *cluster DNS* — just a different implementation path.

## Linux routing refresher

When Linux sends a packet, the routing table answers two concrete questions:

1. Which local interface do I send it out of? (**egress interface**)
2. Which IP do I send it to next? (**next hop** — the next router/machine)

For `172.16.2.0/24 via 10.0.0.12 dev eth0`: egress interface `eth0`, next-hop IP `10.0.0.12`. The frame then gets:

- source MAC = MAC of `eth0`;
- destination MAC = MAC of the next hop `10.0.0.12` — which implies `10.0.0.12` is reachable on the same layer 2 network as `eth0`.

**Rule:** if a route says `via X`, another route must make `X` reachable.

```text
# Traditional subnet gateway
default via 192.168.1.1 dev eth0
192.168.1.0/24 dev eth0 proto kernel scope link
```

`192.168.1.1` is inside `192.168.1.0/24`, so the subnet route implicitly makes the gateway reachable.

```text
# Cloud link-local gateway
default via 169.254.1.1 dev eth0
169.254.1.1 dev eth0 scope link
```

The gateway is not inside your subnet, so the kernel needs an explicit host route (`/32`).

```text
# Point-to-point interfaces (PPP, VPN)
default via 10.8.0.1 dev tun0
```

**Policy routing / multiple tables:** if table 100 has `default via 169.254.1.1 table 100`, it must also contain `169.254.1.1 dev eth0 scope link table 100`.

## Reaching cluster workloads from your machine

### `kubectl port-forward` (TCP only)

```sh
# Pod → local:8080 forwards to the pod's 8080
kubectl port-forward pod/web-6f9c 8080:8080

# Deployment (choose a pod automatically)
kubectl port-forward deploy/web 8080:8080

# Service (nice for databases)
kubectl -n prod port-forward svc/postgres 5432:5432

# Multiple ports
kubectl port-forward pod/web 8080:80 8443:443

# Listen on all interfaces (share with teammates on your LAN; lock down your firewall!)
kubectl port-forward --address 0.0.0.0 svc/grafana 3000:3000
```

### Debugging a pod's network with `nsenter`

`nsenter` enters the namespaces of other processes. Each pod has its own network namespace, so from the pod's node you can enter it and troubleshoot networking — extremely useful when pods have no shell to `exec` into, or you can't run a network utility pod.

1. Find the PID of the pod's container process (e.g. with `ps aux`).
2. Enter its network namespace (`/proc/{PID}/ns/net`):

   ```sh
   nsenter -t {PID} -n
   ```

### `kubectl proxy`

A local HTTP proxy to the Kubernetes API — handy for API exploration:

```sh
kubectl proxy --port=8001 --address=127.0.0.1
```

> **Minikube** is an open-source tool that runs a single-node Kubernetes cluster locally on your computer.

## kubectl reference

<details>
<summary><code>kubectl</code> (top-level help)</summary>

```text
kubectl controls the Kubernetes cluster manager.

 Find more information at: https://kubernetes.io/docs/reference/kubectl/

Basic Commands (Beginner):
  create          Create a resource from a file or from stdin
  expose          Take a replication controller, service, deployment or pod and expose it as a new Kubernetes service
  run             Run a particular image on the cluster
  set             Set specific features on objects

Basic Commands (Intermediate):
  explain         Get documentation for a resource
  get             Display one or many resources
  edit            Edit a resource on the server
  delete          Delete resources by file names, stdin, resources and names, or by resources and label selector

Deploy Commands:
  rollout         Manage the rollout of a resource
  scale           Set a new size for a deployment, replica set, or replication controller
  autoscale       Auto-scale a deployment, replica set, stateful set, or replication controller

Cluster Management Commands:
  certificate     Modify certificate resources
  cluster-info    Display cluster information
  top             Display resource (CPU/memory) usage
  cordon          Mark node as unschedulable
  uncordon        Mark node as schedulable
  drain           Drain node in preparation for maintenance
  taint           Update the taints on one or more nodes

Troubleshooting and Debugging Commands:
  describe        Show details of a specific resource or group of resources
  logs            Print the logs for a container in a pod
  attach          Attach to a running container
  exec            Execute a command in a container
  port-forward    Forward one or more local ports to a pod
  proxy           Run a proxy to the Kubernetes API server
  cp              Copy files and directories to and from containers
  auth            Inspect authorization
  debug           Create debugging sessions for troubleshooting workloads and nodes
  events          List events

Advanced Commands:
  diff            Diff the live version against a would-be applied version
  apply           Apply a configuration to a resource by file name or stdin
  patch           Update fields of a resource
  replace         Replace a resource by file name or stdin
  wait            Wait for a specific condition on one or many resources
  kustomize       Build a kustomization target from a directory or URL

Settings Commands:
  label           Update the labels on a resource
  annotate        Update the annotations on a resource
  completion      Output shell completion code for the specified shell (bash, zsh, fish, or powershell)

Subcommands provided by plugins:

Other Commands:
  alpha           Commands for features in alpha
  api-resources   Print the supported API resources on the server
  api-versions    Print the supported API versions on the server, in the form of "group/version"
  config          Modify kubeconfig files
  plugin          Provides utilities for interacting with plugins
  version         Print the client and server version information

Usage:
  kubectl [flags] [options]
```

</details>

<details>
<summary><code>kubectl autoscale</code></summary>

The command attempts to use the `autoscaling/v2` API first; on error it falls back to `autoscaling/v1`. It looks up a deployment, replica set, stateful set, or replication controller by name and creates an autoscaler that uses it as a reference, automatically increasing or decreasing the number of pods as needed.

```sh
# Auto scale a deployment "foo", with the number of pods between 2 and 10, no target CPU utilization specified so a default autoscaling policy will be used
kubectl autoscale deployment foo --min=2 --max=10

# Auto scale a replication controller "foo", with the number of pods between 1 and 5, target CPU utilization at 80%
kubectl autoscale rc foo --max=5 --cpu=80%

# Auto scale a deployment "bar", with the number of pods between 3 and 6, target average CPU of 500m and memory of 200Mi
kubectl autoscale deployment bar --min=3 --max=6 --cpu=500m --memory=200Mi

# Auto scale a deployment "bar", with the number of pods between 2 and 8, target CPU utilization 60% and memory utilization 70%
kubectl autoscale deployment bar --min=2 --max=8 --cpu=60% --memory=70%
```

</details>

<details>
<summary><code>kubectl create</code></summary>

```text
Create a resource from a file or from stdin.

 JSON and YAML formats are accepted.

Examples:
  # Create a pod using the data in pod.json
  kubectl create -f ./pod.json

  # Create a pod based on the JSON passed into stdin
  cat pod.json | kubectl create -f -

  # Edit the data in registry.yaml in JSON then create the resource using the edited data
  kubectl create -f registry.yaml --edit -o json

Available Commands:
  clusterrole           Create a cluster role
  clusterrolebinding    Create a cluster role binding for a particular cluster role
  configmap             Create a config map from a local file, directory or literal value
  cronjob               Create a cron job with the specified name
  deployment            Create a deployment with the specified name
  ingress               Create an ingress with the specified name
  job                   Create a job with the specified name
  namespace             Create a namespace with the specified name
  poddisruptionbudget   Create a pod disruption budget with the specified name
  priorityclass         Create a priority class with the specified name
  quota                 Create a quota with the specified name
  role                  Create a role with single rule
  rolebinding           Create a role binding for a particular role or cluster role
  secret                Create a secret using a specified subcommand
  service               Create a service using a specified subcommand
  serviceaccount        Create a service account with the specified name
  token                 Request a service account token
```

</details>

<details>
<summary><code>kubectl expose</code></summary>

```text
Expose a resource as a new Kubernetes service.

 Looks up a deployment, service, replica set, replication controller or pod by name and uses the selector for that
resource as the selector for a new service on the specified port. A deployment or replica set will be exposed as a
service only if its selector is convertible to a selector that service supports, i.e. when the selector contains only
the matchLabels component. Note that if no port is specified via --port and the exposed resource has multiple ports, all
will be re-used by the new service. Also if no labels are specified, the new service will re-use the labels from the
resource it exposes.

 Possible resources include (case insensitive):

 pod (po), service (svc), replicationcontroller (rc), deployment (deploy), replicaset (rs)

Examples:
  # Create a service for a replicated nginx, which serves on port 80 and connects to the containers on port 8000
  kubectl expose rc nginx --port=80 --target-port=8000

  # Create a service for a replication controller identified by type and name specified in "nginx-controller.yaml",
  # which serves on port 80 and connects to the containers on port 8000
  kubectl expose -f nginx-controller.yaml --port=80 --target-port=8000

  # Create a service for a pod valid-pod, which serves on port 444 with the name "frontend"
  kubectl expose pod valid-pod --port=444 --name=frontend

  # Create a second service based on the above service, exposing the container port 8443 as port 443 with the name
  # "nginx-https"
  kubectl expose service nginx --port=443 --target-port=8443 --name=nginx-https

  # Create a service for a replicated streaming application on port 4100 balancing UDP traffic and named 'video-stream'.
  kubectl expose rc streamer --port=4100 --protocol=UDP --name=video-stream

  # Create a service for a replicated nginx using replica set, which serves on port 80 and connects to the containers on
  # port 8000
  kubectl expose rs nginx --port=80 --target-port=8000

  # Create a service for an nginx deployment, which serves on port 80 and connects to the containers on port 8000
  kubectl expose deployment nginx --port=80 --target-port=8000
```

</details>

<details>
<summary><code>kubectl run</code></summary>

```text
Create and run a particular image in a pod.

Examples:
  # Start a nginx pod
  kubectl run nginx --image=nginx

  # Start a hazelcast pod and let the container expose port 5701
  kubectl run hazelcast --image=hazelcast/hazelcast --port=5701

  # Start a hazelcast pod and set environment variables "DNS_DOMAIN=cluster" and "POD_NAMESPACE=default" in the
  # container
  kubectl run hazelcast --image=hazelcast/hazelcast --env="DNS_DOMAIN=cluster" --env="POD_NAMESPACE=default"

  # Start a hazelcast pod and set labels "app=hazelcast" and "env=prod" in the container
  kubectl run hazelcast --image=hazelcast/hazelcast --labels="app=hazelcast,env=prod"

  # Dry run; print the corresponding API objects without creating them
  kubectl run nginx --image=nginx --dry-run=client

  # Start a nginx pod, but overload the spec with a partial set of values parsed from JSON
  kubectl run nginx --image=nginx --overrides='{ "apiVersion": "v1", "spec": { ... } }'

  # Start a busybox pod and keep it in the foreground, don't restart it if it exits
  kubectl run -i -t busybox --image=busybox --restart=Never
```

</details>

<details>
<summary><code>kubectl get</code></summary>

```text
Display one or many resources.

 Prints a table of the most important information about the specified resources. You can filter the list using a label
selector and the --selector flag. If the desired resource type is namespaced you will only see results in the current
namespace if you don't specify any namespace.

 By specifying the output as 'template' and providing a Go template as the value of the --template flag, you can filter
the attributes of the fetched resources.

Use "kubectl api-resources" for a complete list of supported resources.

Examples:
  # List all pods in ps output format
  kubectl get pods

  # List all pods in ps output format with more information (such as node name)
  kubectl get pods -o wide

  # List a single replication controller with specified NAME in ps output format
  kubectl get replicationcontroller web

  # List deployments in JSON output format, in the "v1" version of the "apps" API group
  kubectl get deployments.v1.apps -o json

  # List a single pod in JSON output format
  kubectl get -o json pod web-pod-13je7

  # List a pod identified by type and name specified in "pod.yaml" in JSON output format
  kubectl get -f pod.yaml -o json

  # List resources from a directory with kustomization.yaml - e.g. dir/kustomization.yaml
  kubectl get -k dir/

  # Return only the phase value of the specified pod
  kubectl get -o template pod/web-pod-13je7 --template={{.status.phase}}

  # List resource information in custom columns
  kubectl get pod test-pod -o custom-columns=CONTAINER:.spec.containers[0].name,IMAGE:.spec.containers[0].image

  # List all replication controllers and services together in ps output format
  kubectl get rc,services

  # List one or more resources by their type and names
  kubectl get rc/web service/frontend pods/web-pod-13je7

  # List the 'status' subresource for a single pod
  kubectl get pod web-pod-13je7 --subresource status

  # List all deployments in namespace 'backend'
  kubectl get deployments.apps --namespace backend

  # List all pods existing in all namespaces
  kubectl get pods --all-namespaces
```

</details>

<details>
<summary><code>kubectl scale</code></summary>

```text
Set a new size for a deployment, replica set, replication controller, or stateful set.

 Scale also allows users to specify one or more preconditions for the scale action.

 If --current-replicas or --resource-version is specified, it is validated before the scale is attempted, and it is
guaranteed that the precondition holds true when the scale is sent to the server.

Examples:
  # Scale a replica set named 'foo' to 3
  kubectl scale --replicas=3 rs/foo

  # Scale a resource identified by type and name specified in "foo.yaml" to 3
  kubectl scale --replicas=3 -f foo.yaml

  # If the deployment named mysql's current size is 2, scale mysql to 3
  kubectl scale --current-replicas=2 --replicas=3 deployment/mysql

  # Scale multiple replication controllers
  kubectl scale --replicas=5 rc/example1 rc/example2 rc/example3

  # Scale stateful set named 'web' to 3
  kubectl scale --replicas=3 statefulset/web
```

</details>

## Further reading

- [Kubernetes networking (sobyte)](https://www.sobyte.net/post/2022-03/kubernetes-networking/)
- [How the Kubernetes network plugin works (sobyte)](https://www.sobyte.net/post/2022-03/how-the-kubernetes-network-plugin-works/)
- [Linux virtual network interfaces (sobyte)](https://www.sobyte.net/post/2022-01/linux-virtual-network-interfaces/)
- [Project Calico part II: CNCF roadmap](https://medium.com/@buraktahtacioglu/project-calico-part-ii-cncf-roadmap-8a6b1239220c#:~:text=But%20there%20are%20some%20environments,interfaces%20within%20the%20linux%20kernel.)
- [Flannel vs Calico: L2 vs L3 networking](https://medium.com/@jain.sm/flannel-vs-calico-a-battle-of-l2-vs-l3-based-networking-5a30cd0a3ebd#:~:text=Since%20container%20B%20is%20not,a%20physical%20network%20using%20UDP.)
- [Deep dive on the AWS CNI plug-in for Kubernetes (video)](https://www.youtube.com/watch?v=ezcnPcRcJdc)
