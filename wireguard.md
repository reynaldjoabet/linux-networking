# WireGuard

## A simple network interface

WireGuard works by adding a network interface (or several), like `eth0` or `wlan0`, called `wg0` (or `wg1`, `wg2`, `wg3`, etc.). This interface acts as a **tunnel interface** and is configured like any other:

- addresses with `ifconfig(8)` or `ip-address(8)`,
- routes with `route(8)` or `ip-route(8)`,
- and so on with all the ordinary networking utilities.

The WireGuard-specific aspects of the interface are configured with the `wg(8)` tool.

Under the hood, `wg0` is a kernel network interface **backed by a UDP socket**. Packets arrive on the UDP socket, are authenticated/decrypted, and are then injected into the kernel as normal IP packets on `wg0`.

WireGuard does **not** create a layer 2 network — it creates a **layer 3** interface. So `wg0` and `eth0` are not on the same L2 network, even if their IPs are in the same `/24`.

### Sending a packet

WireGuard associates tunnel IP addresses with public keys and remote endpoints. When the interface sends a packet to a peer:

1. This packet is meant for `192.168.30.8`. Which peer is that? Let me look... Okay, it's for peer `ABCDEFGH`. (If it's not for any configured peer, drop the packet.)
2. Encrypt the entire IP packet using peer `ABCDEFGH`'s public key.
3. What is the remote endpoint of peer `ABCDEFGH`? Let me look... Okay, the endpoint is UDP port 53133 on host `216.58.211.110`.
4. Send the encrypted bytes from step 2 over the internet to `216.58.211.110:53133` using UDP.

### Receiving a packet

1. I just got a packet from UDP port 7361 on host `98.139.183.24`. Let's decrypt it!
2. It decrypted and authenticated properly for peer `LMNOPQRS`. Okay, let's remember that peer `LMNOPQRS`'s most recent internet endpoint is `98.139.183.24:7361` using UDP.
3. Once decrypted, the plain-text packet is from `192.168.43.89`. Is peer `LMNOPQRS` allowed to be sending us packets as `192.168.43.89`?
4. If so, accept the packet on the interface. If not, drop it.

### On the wire

```text
[Layer 2: Ethernet / PPP]
  SRC MAC = peer's NIC MAC        (only on local physical link)
  DST MAC = next-hop MAC

[Layer 3: IP]
  SRC IP = peer_public_ip
  DST IP = endpoint_public_ip

[Layer 4: UDP]
  SRC PORT = random/high port
  DST PORT = 51820 (WireGuard)

[Payload]
  Encrypted WireGuard message
    └─ Encrypted inner IP packet
```

## Cryptokey routing

At the heart of WireGuard is **Cryptokey Routing**: associating public keys with a list of tunnel IP addresses that are allowed inside the tunnel.

- Each network interface has a private key and a list of peers.
- Each peer has a public key.
- Public keys are short and simple, and are used by peers to authenticate each other. They can be passed around for configuration files by any out-of-band method — similar to sending your SSH public key to a friend for access to a shell server.

A server might have this configuration:

```ini
[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
ListenPort = 51820

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
AllowedIPs = 10.192.122.3/32, 10.192.124.1/24

[Peer]
PublicKey = TrMvSoP4jYQlY6RIzBgbssQqY3vxI2Pi+y71lOWWXX0=
AllowedIPs = 10.192.122.4/32, 192.168.0.0/16

[Peer]
PublicKey = gN65BkIKy1eCE9pP1wdc8ROUtkHLF2PfAqYdyYBz6EA=
AllowedIPs = 10.10.10.230/32
```

And a client might have this simpler configuration:

```ini
[Interface]
PrivateKey = gI6EdUSYvn8ugXOt8QQD6Yc+JyiZxIhp3GInSWRfWGE=
ListenPort = 21841

[Peer]
PublicKey = HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=
Endpoint = 192.95.5.69:51820
AllowedIPs = 0.0.0.0/0
```

## Endpoint vs tunnel IPs

`Endpoint` answers: **"Where do I send encrypted UDP packets for this peer?"** In practice it's the IP address (or hostname) of the router/device you are connecting to:

- a publicly reachable IP or DNS name,
- plus a UDP port.

```ini
Endpoint = vpn.example.com:51820
```

The endpoint is **not** the tunnel IP:

| Address                       | Lives                                   | Used for                                   | Example                                                   |
| ----------------------------- | --------------------------------------- | ------------------------------------------ | --------------------------------------------------------- |
| Endpoint (underlay)           | Outside the tunnel, public internet side | Routing UDP packets between peers          | `203.0.113.10:51000`                                      |
| Tunnel IP (overlay)           | Inside the tunnel (`10.x.x.x`)          | Internal traffic once connected            | `10.10.11.1/24` on the router, `10.10.11.2/24` on the laptop |

WireGuard uses the Endpoint only to send UDP packets to the peer's real-world address. The tunnel IPs are used only after the packet is decrypted inside the VPN.

A VPN client gets an address in the same range as the VPN gateway:

```ini
# Server (gateway)
[Interface]
Address = 10.100.0.1/24

# Client
[Interface]
Address = 10.100.0.2/24
```

## Example: site-to-site

```ini
# Site A
[Interface]
Address = 10.255.0.1/30
ListenPort = 51820
PrivateKey = A_PRIVATE_KEY

[Peer]
PublicKey = B_PUBLIC_KEY
AllowedIPs = 10.255.0.2/32, 192.168.20.0/24
```

```ini
# Site B
[Interface]
Address = 10.255.0.2/30
PrivateKey = B_PRIVATE_KEY

[Peer]
PublicKey = A_PUBLIC_KEY
Endpoint = site-a.example.com:51820
AllowedIPs = 10.255.0.1/32, 192.168.10.0/24
PersistentKeepalive = 25
```

## Example: peer-to-site (WireGuard on the router)

```text
                       public internet              ┌─── wg0 10.10.11.1/24
10.10.11.2/24                                       │        VPN network
        home0│            xxxxxx       ppp0 ┌───────┴┐
           ┌─┴──┐         xx   xxxxx  ──────┤ router │
           │    ├─wlan0  xx       xx        └───┬────┘    home network, .home domain
           │    │       xx        x             │.1       10.10.10.0/24
           │    │        xxx    xxx             └───┬─────────┬─────────┐
           └────┘          xxxxxx                   │         │         │
Laptop in                                         ┌─┴─┐     ┌─┴─┐     ┌─┴─┐
Coffee shop                                       │   │     │   │     │   │
                                                  │pi4│     │NAS│     │...│
                                                  │   │     │   │     │   │
                                                  └───┘     └───┘     └───┘
```

Laptop configuration:

```ini
[Interface]
PrivateKey = <contents-of-laptop-private.key>
ListenPort = 51000
Address = 10.10.11.2/24

[Peer]
PublicKey = <contents-of-router-public.key>
Endpoint = <home-ppp0-IP-or-hostname>:51000
AllowedIPs = 10.10.11.0/24,10.10.10.0/24
```

`AllowedIPs` on the client doubles as its routing table for the tunnel:

- Traffic to `10.10.11.0/24` → `wg0`
- Traffic to `10.10.10.0/24` → `wg0`
- Everything else → normal internet

If you set `AllowedIPs = 0.0.0.0/0`, **all** traffic goes through the VPN.

You need `10.10.11.0/24` because WireGuard is a layer 3 routed tunnel: it must have an IP network on the tunnel so the kernel knows how to route packets and identify peers.

### The `wg0` route

```text
10.10.11.0/24 dev wg0 proto kernel scope link src 10.10.11.1
```

Any packet destined for `10.10.11.0/24` is sent out `wg0`; the kernel added this route automatically; the destination is directly reachable on that link; when sending, use `10.10.11.1` as the source address.

- **`proto kernel`** — who installed the route. Linux added it automatically when an IP address was configured on the interface. Not a static route (`proto static`), DHCP (`proto dhcp`), or a routing daemon (BGP/OSPF).
- **`scope link`** — how far the route applies. The destination is directly reachable on this interface; no gateway IP is needed. Typical for Ethernet subnets, VPN tunnel networks, and point-to-point links. Contrast:
  - `scope global` → reachable via a gateway
  - `scope host` → loopback / local-only

### Packet walkthrough

**Router → VPN client:**

```text
SRC = 10.10.10.50
DST = 10.10.11.2
```

It matches `10.10.11.0/24`, so the kernel uses `dev wg0 src 10.10.11.1`. WireGuard then encrypts it and sends it to the peer.

**VPN client → home network:**

1. The laptop sends an encrypted UDP packet to the router's public IP and port: `DST = <router-public-ip>:51000`. This is just a normal UDP packet on the internet.
2. WireGuard on the router listens on UDP `51000`. When the packet arrives, the WireGuard driver:
   - decrypts the UDP payload, yielding a normal IP packet,
   - associates it with the peer (via public key),
   - injects it into the kernel routing stack on `wg0`.
3. Once decrypted, the router sees `SRC = 10.10.11.2` (laptop inside the VPN), `DST = 10.10.10.0/24` (home network destination).
4. The router has the LAN (`10.10.10.0/24`) on its internal interface, so the routing table sends it out there. The packet is forwarded to the home network as if it originated from a local router.

## Example: peer-to-site (WireGuard on an internal system)

```text
                       public internet
10.10.10.11/24
        home0│            xxxxxx       ppp0 ┌────────┐
           ┌─┴──┐         xx   xxxxx  ──────┤ router │
           │    ├─ppp0  xxx       xx        └───┬────┘    home network, .home domain
           │    │       xx        x             │         10.10.10.0/24
           │    │        xxx    xxx             └───┬─────────┬─────────┐
           └────┘          xxxxxx                   │         │         │
                                                  ┌─┴─┐     ┌─┴─┐     ┌─┴─┐
                                            wg0 ──┤   │     │   │     │   │
                                  10.10.10.10/32  │pi4│     │NAS│     │...│
                                                  │   │     │   │     │   │
                                                  └───┘     └───┘     └───┘
Reserved for VPN users:
10.10.10.10-49
```

Setup:

1. **Carve out addresses:** there's no new network dedicated to VPN users, so reserve a section of the home network for the VPN (`10.10.10.10-49`).
2. **Port forward on the router:** `10.10.10.10` (the pi4) is the internal WireGuard endpoint, running on `51000/udp`. Configure the router to forward all `51000/udp` traffic to `10.10.10.10` on the same port.
3. **Firewall:** allow hosts on the internet to send traffic to the router on `51000/udp`.
4. **IP forwarding on the pi4:** required so it can pass packets between the WireGuard interface and the LAN interface instead of dropping them.

Why IP forwarding is required on the pi4 — it receives a packet like:

```text
SRC = 10.10.10.11
DST = 10.10.10.25   (NAS)
```

The packet is not for the pi4; it needs to go to the NAS. By default Linux **drops** it. With forwarding enabled (`net.ipv4.ip_forward=1`) it is passed on.

## IP forwarding: host vs router

Forwarding controls whether packets may move between interfaces. Traffic is **not** forwarded when the kernel is acting as a host rather than a router, which happens whenever either is true:

- IP forwarding is disabled, or
- the packet is destined for the local machine itself.

**"Is the destination IP mine?"** — it is if it matches any IP assigned to the machine on any interface (`wg0`, `eth0`, `lo`, etc.), plus special local addresses.

- If yes → local delivery.
- If no → consider forwarding.

So traffic to `10.10.10.0/24` is forwarded (not locally delivered) unless the destination is exactly the router's own address, such as `10.10.10.1`.

Being in the same IP subnet does **not** mean packets move between interfaces automatically. Linux only moves packets between interfaces when it is explicitly acting as a router (IP forwarding enabled) — even if both interfaces use addresses from `10.10.10.0/24`. (Every host with an IP in `10.10.10.0/24` automatically has a connected route for `10.10.10.0/24`.)

## Further reading

- [WireGuard VPN on Hetzner](https://upvpn.app/articles/post/wireguard-vpn-on-hetzner/)
- [Virtual internal network with WireGuard](https://blog.stefan-koch.name/2019/06/15/virtual-internal-network-with-wireguard)
