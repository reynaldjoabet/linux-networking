# Linux Bridge Internals

A kernel-level walkthrough of how the Linux bridge receives, forwards and delivers frames. For the basics and hands-on commands, see [Linux bridge](linux-bridge.md); for VLAN filtering, see [VLAN](vlan.md).

## Overview

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                           HOST SYSTEM                                   │
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                      TCP/IP Stack                               │   │
│   │                                                                 │   │
│   │   ┌──────────────────────────────────────────────────────────┐  │   │
│   │   │  Routing Table                                           │  │   │
│   │   │  ─────────────────────────────────────────────────────── │  │   │
│   │   │  192.168.1.0/24  →  br0  (directly connected)            │  │   │
│   │   │  0.0.0.0/0       →  192.168.1.1 via br0 (default gw)     │  │   │
│   │   └──────────────────────────────────────────────────────────┘  │   │
│   └────────────────────────────┬────────────────────────────────────┘   │
│                                │                                        │
│                                ▼                                        │
│   ┌────────────────────────────────────────────────────────────────┐    │
│   │              BRIDGE INTERFACE (br0)                            │    │
│   │              IP: 192.168.1.10/24  ◄── Layer 3 endpoint         │    │
│   │              MAC: aa:bb:cc:dd:ee:ff                            │    │
│   │                                                                │    │
│   │  ┌───────────────────────────────────────────────────────────┐ │    │
│   │  │              Layer 2 Switching Engine                     │ │    │
│   │  │                                                           │ │    │
│   │  │   Traffic TO br0's IP  →  Goes UP to TCP/IP stack         │ │    │
│   │  │   Traffic NOT for br0  →  Switched between ports          │ │    │
│   │  └───────────────────────────────────────────────────────────┘ │    │
│   │                                                                │    │
│   │     ┌─────────┐      ┌─────────┐      ┌─────────┐              │    │
│   │     │  eth0   │      │  veth0  │      │  tap0   │              │    │
│   └─────┴────┬────┴──────┴────┬────┴──────┴────┬────┴──────────────┘    │
└──────────────┼────────────────┼────────────────┼────────────────────────┘
               │                │                │
               ▼                ▼                ▼
          Physical         Container            VM
          Network       192.168.1.20       192.168.1.30
```

The bridge device itself acts like any other network interface. The kernel abstracts every interface as a `struct net_device`: the physical NIC (Intel/Realtek) implements it, the bridge (`br0`) implements it, and `veth` pairs implement it.

```c
// net/bridge/br_device.c (lines 439-478)
static const struct net_device_ops br_netdev_ops = {
    .ndo_open            = br_dev_open,
    .ndo_stop            = br_dev_stop,
    .ndo_start_xmit      = br_dev_xmit,      // TX from host
    .ndo_set_mac_address = br_set_mac_address,
    .ndo_set_rx_mode     = br_dev_set_multicast_list,
    // ... etc
};
```
```sh
static const struct net_device_ops br_netdev_ops = { ... };
//           └──── type ─────────┘ └─ variable ─┘
```

## Enslaving a port: `rx_handler`

### The problem: intercepting packets early

Without `rx_handler`, the receive path is:

`NIC → Driver → netif_receive_skb() → Protocol handlers (ip_rcv, arp_rcv, etc.)`

Bridges, bonds and similar virtual devices need to intercept packets **before** protocols see them, to make decisions on layer 2 (MAC addresses), not layer 3 (IP). The `rx_handler` is a function pointer stored on the `net_device`; whoever registers it intercepts all frames before the normal protocol stack sees them.

```c
/*
 * rx_handlers are functions called from inside __netif_receive_skb(), to do
 * special processing of the skb, prior to delivery to protocol handlers.
 *
 * Currently, a net_device can only have a single rx_handler registered.
 * Trying to register a second rx_handler will return -EBUSY.
 */

typedef rx_handler_result_t rx_handler_func_t(struct sk_buff **pskb);

```

```c
enum rx_handler_result {
    RX_HANDLER_CONSUMED,  // Handler took ownership, stop processing
    RX_HANDLER_ANOTHER,   // skb->dev changed, restart receive path
    RX_HANDLER_EXACT,     // Force exact delivery only
    RX_HANDLER_PASS,      // Continue normal processing
};

```
```text
┌─────────────────────────────────────────────────────────────────────┐
│                    Packet arrives on eth0                           │
│                            │                                        │
│                            ▼                                        │
│              __netif_receive_skb_core()                             │
│                            │                                        │
│                            ▼                                        │
│    ┌──────────────────────────────────────────────────────┐         │
│    │  rx_handler = rcu_dereference(skb->dev->rx_handler); │         │
│    │  if (rx_handler) {                                   │         │
│    │      switch (rx_handler(&skb)) {    ◄── HOOK POINT   │         │
│    │          case RX_HANDLER_CONSUMED:                   │         │
│    │              return;  // Done                        │         │
│    │          case RX_HANDLER_ANOTHER:                    │         │
│    │              goto another_round;  // Restart         │         │
│    │          case RX_HANDLER_PASS:                       │         │
│    │              break;  // Continue                     │         │
│    │      }                                               │         │
│    │  }                                                   │         │
│    └──────────────────────────────────────────────────────┘         │
│                            │                                        │
│                            ▼                                        │
│              Protocol handlers (ip_rcv, etc.)                       │
└─────────────────────────────────────────────────────────────────────┘
```

```c
int netdev_rx_handler_register(struct net_device *dev,
                               rx_handler_func_t *rx_handler,
                               void *rx_handler_data)
{
    if (netdev_is_rx_handler_busy(dev))
        return -EBUSY;   // Only ONE handler per device!

    rcu_assign_pointer(dev->rx_handler_data, rx_handler_data);
    rcu_assign_pointer(dev->rx_handler, rx_handler);
    return 0;
}
```

Who uses `rx_handler`:

| Module       | `rx_handler`           | Purpose                           |
| ------------ | ---------------------- | --------------------------------- |
| Bridge       | `br_handle_frame`      | Intercept frames for L2 switching |
| Bonding      | `bond_handle_frame`    | Aggregate multiple NICs           |
| Team         | `team_handle_frame`    | Modern bonding alternative        |
| Open vSwitch | `ovs_vport_receive`    | Software-defined networking       |
| macvlan      | `macvlan_handle_frame` | MAC-based VLAN                    |
| ipvlan       | `ipvlan_handle_frame`  | IP-based VLAN                     |

### Bridge example

When you add `eth0` to a bridge:

```c
// net/bridge/br_if.c:613
err = netdev_rx_handler_register(dev, br_get_rx_handler(dev), p);

// net/bridge/br_input.c:463
rx_handler_func_t *br_get_rx_handler(const struct net_device *dev)
{
    if (netdev_uses_dsa(dev))
        return br_handle_frame_dummy;   // DSA switch, pass through

    return br_handle_frame;             // Normal bridge handler
}
```

Now every packet on `eth0` goes through `br_handle_frame()` before any protocol handler sees it, so the bridge can decide based on MAC addresses — forward to the correct port, or to the local stack if it's for the bridge's IP.

The `rx_handler` is what makes a physical NIC "disappear" from the normal network stack when enslaved to a bridge/bond:

```text
Before bridge:
  eth0 → netif_receive_skb → ip_rcv → application

After adding to bridge:
  eth0 → netif_receive_skb → rx_handler (br_handle_frame) → CONSUMED
                                   │
                                   └→ Bridge logic → br0 → ip_rcv → application
```

**Device-specific, not protocol-specific:**

```text
┌────────────────────────────────────────────────────────────────┐
│  `rx_handler` is per-device:                                   │
│                                                                │
│  eth0 → rx_handler = br_handle_frame    (enslaved to bridge)   │
│  eth1 → rx_handler = NULL               (normal interface)     │
│  eth2 → rx_handler = bond_handle_frame  (enslaved to bond)     │
│                                                                │
│  Each interface can have different behavior!                   │
└────────────────────────────────────────────────────────────────┘
```

**Clean packet ownership transfer** — the return values allow a clean handoff:

```c
enum rx_handler_result {
    RX_HANDLER_CONSUMED,  // "I took it, you're done"
    RX_HANDLER_ANOTHER,   // "I changed skb->dev, try again"
    RX_HANDLER_PASS,      // "I didn't want it, continue normally"
    RX_HANDLER_EXACT,     // "Continue but exact match only"
};
```

**Mutually exclusive ownership** — only one `rx_handler` per device:

```c
// Only ONE rx_handler per device
int netdev_rx_handler_register(...)
{
    if (netdev_is_rx_handler_busy(dev))
        return -EBUSY;   // Can't have two!
    ...
}
```

This prevents conflicts: `eth0` can't be in a bridge **and** a bond at the same time — first one wins, the second gets `-EBUSY`.

```text
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  1. Works at the RIGHT layer (L2, before protocol dispatch)         │
│                                                                     │
│  2. Per-device (not global or per-protocol)                         │
│                                                                     │
│  3. Catches ALL packets (regardless of EtherType)                   │
│                                                                     │
│  4. Clean ownership model (one handler, explicit return values)     │
│                                                                     │
│  5. Zero overhead when not used (simple NULL check)                 │
│                                                                     │
│  6. Enables multiple subsystems: bridge, bond, team, macvlan,       │
│     ipvlan, openvswitch, etc.                                       │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```
The `rx_handler` is essentially the "enslave this interface" mechanism — it's how virtual devices (bridges, bonds, etc.) take ownership of a physical interface's packet stream before the normal network stack processes it.

### Inside `br_add_if()`

`br` = bridge, `add` = add, `if` = interface. The `ip` commands map to kernel functions:

```sh
  ip link add br0 type bridge       →  br_add_bridge()    create br0
  ip link set eth0 master br0       →  br_add_if()        add port to br0
  ip link set eth1 master br0       →  br_add_if()        add port to br0
  ip link set eth1 nomaster          →  br_del_if()        remove port
  ip link del br0                    →  br_del_bridge()    destroy br0
```

```text
#br = bridge, add = add, if = interface.

br_add_if(br, dev)          "add dev (e.g. eth0) to bridge br0"
│
├── 1. VALIDATION
│   ├── Must be ethernet (ARPHRD_ETHER)                         :564
│   ├── Can't bridge a bridge                                    :570
│   ├── Can't already have a master                              :577
│   └── Device must allow bridging                               :581
│
├── 2. CREATE PORT STRUCTURE
│   └── p = new_nbp(br, dev)                                    :587
│       Allocates struct net_bridge_port, links to br and dev
│
├── 3. CONFIGURE THE DEVICE
│   ├── dev_set_allmulti(dev, 1)                                  :592
│   │   Put NIC in all-multicast mode (receive all multicast)
│   │
│   ├── kobject_init_and_add() + br_sysfs_addif()                :599-603
│   │   Create /sys/class/net/eth0/brport/ sysfs entries
│   │
│   ├── br_netpoll_enable()                                      :606
│   │   Enable netpoll for debugging
│   │
│   ├── netdev_rx_handler_register(dev, br_handle_frame, p)      :613
│   │   ┌─────────────────────────────────────────────────┐
│   │   │  THE KEY STEP                                   │
│   │   │  Hijacks all incoming frames on eth0            │
│   │   │  Now eth0 → br_handle_frame() → bridge logic    │
│   │   │  eth0 no longer delivers to IP stack directly   │
│   │   └─────────────────────────────────────────────────┘
│   │
│   ├── dev->priv_flags |= IFF_BRIDGE_PORT                       :616
│   │   Mark eth0 as "I'm a bridge port"
│   │
│   └── netdev_master_upper_dev_link(dev, br->dev)               :618
│       Link eth0 → br0 in the netdev hierarchy
│
├── 4. ADD TO BRIDGE
│   ├── list_add_rcu(&p->list, &br->port_list)                  :622
│   │   Add port to bridge's port list
│   │
│   ├── br_fdb_sync_static()                                     :626
│   │   Sync static FDB entries to port's unicast filter
│   │
│   └── br_fdb_add_local(br, p, dev->dev_addr, 0)               :640
│       ┌──────────────────────────────────────────────┐
│       │  Creates the LOCAL FDB entry:                │
│       │  MAC: eth0's MAC                             │
│       │  flags: BR_FDB_LOCAL | BR_FDB_STATIC         │
│       │  dst: p (this port)                          │
│       └──────────────────────────────────────────────┘
│
├── 5. FINALIZE
│   ├── nbp_vlan_init(p)                                         :653
│   │   Initialize VLAN filtering for this port
│   │
│   ├── br_stp_recalculate_bridge_id(br)                         :660
│   │   Maybe change bridge MAC to lowest port MAC
│   │
│   ├── br_stp_enable_port(p)                                    :663
│   │   Start STP state machine (listening→learning→forwarding)
│   │
│   ├── br_mtu_auto_adjust(br)                                   :673
│   │   Set bridge MTU = min(all port MTUs)
│   │
│   └── kobject_uevent(KOBJ_ADD)                                  :677
│       Notify userspace (udev) about new port
│
└── return 0
```

What `netdev_rx_handler_register()` does:

```text
netdev_rx_handler_register(dev, br_get_rx_handler(dev), p)
                            │            │                │
                            ▼            ▼                ▼
                          eth0     br_handle_frame     port struct

  What it does:
  ┌──────────────────────────────────────────────────┐
  │  1. Check if handler already registered → EBUSY  │
  │  2. Check if device forbids handlers    → EINVAL │
  │  3. dev->rx_handler_data = p                     │  ← bridge port struct
  │  4. dev->rx_handler     = br_handle_frame        │  ← the function
  └──────────────────────────────────────────────────┘

  After this, eth0's net_device looks like:
  ┌──────────────────────────────────────┐
  │  struct net_device  (eth0)           │
  │                                      │
  │  ...                                 │
  │  rx_handler      = br_handle_frame   │  ← called on every frame
  │  rx_handler_data = p                 │  ← so handler knows which port
  │  ...                                 │
  └──────────────────────────────────────┘
  ```

How `__netif_receive_skb_core()` (`netif` = network interface) invokes it:

```c
	rx_handler = rcu_dereference(skb->dev->rx_handler);
	if (rx_handler) {
		if (unlikely(pt_prev)) {
			ret = deliver_skb(skb, pt_prev, orig_dev);
			pt_prev = NULL;
		}
		switch (rx_handler(&skb)) {
		case RX_HANDLER_CONSUMED:
			ret = NET_RX_SUCCESS;
			goto out;
		case RX_HANDLER_ANOTHER:
			goto another_round;
		case RX_HANDLER_EXACT:
			deliver_exact = true;
			break;
		case RX_HANDLER_PASS:
			break;
		default:
			BUG();
		}
	}
```

**Ports lose their IP when enslaved.** Once `eth0` is enslaved, the kernel registers `br_handle_frame` as its `rx_handler` (`br_if.c:613`), and `eth0` no longer delivers frames to the IP stack itself — the bridge intercepts everything. If you had an IP on `eth0` before enslaving it, you lose connectivity; put the IP on `br0` instead.

## Bridge netfilter: `nf_hook_bridge_pre()`

Within `br_handle_frame`, `nf_hook_bridge_pre(skb, pskb)` runs the netfilter bridge pre-routing hooks. Netfilter hooks are **per network namespace** — a container has its own.

  ```text
  nf_hook_bridge_pre(skb, pskb)
│
├── Run netfilter PRE_ROUTING hooks (ebtables, nftables, etc.)
│   │
│   ├── NF_ACCEPT
│   │   ├── broute flag set? → return RX_HANDLER_PASS
│   │   │                      (bypass bridge, let IP stack handle it)
│   │   └── continue to next hook
│   │
│   ├── NF_DROP → kfree_skb → return RX_HANDLER_CONSUMED
│   ├── NF_QUEUE → enqueue for userspace → return RX_HANDLER_CONSUMED
│   └── NF_STOLEN → return RX_HANDLER_CONSUMED
│
├── All hooks passed (or no hooks):
│   │
│   └── br_handle_frame_finish(net, NULL, skb)    ← THE ACTUAL BRIDGE LOGIC
│       │                                           (FDB lookup, forward/flood/pass-up)
│       │
│       return RX_HANDLER_CONSUMED                ← bridge consumed the frame
```

### Brouting

 ```c
 if (BR_INPUT_SKB_CB(skb)->br_netfilter_broute) {
                *pskb = skb;
                return RX_HANDLER_PASS;
            }
```

A netfilter hook can set this flag to say "don't bridge this frame — route it instead". `RX_HANDLER_PASS` sends the skb back to the normal protocol stack (IP routing) as if the bridge never existed. This is how `ebtables -t broute` works — selectively routing instead of bridging.

### With and without `CONFIG_NETFILTER_FAMILY_BRIDGE`

 ```c
net = dev_net(skb->dev);
	br_handle_frame_finish(net, NULL, skb);
#else
br_handle_frame_finish(dev_net(skb->dev), NULL, skb);
```
```text
  CONFIG_NETFILTER_FAMILY_BRIDGE=y    (enabled)
  ═════════════════════════════════
  Bridge frames pass through netfilter hooks (ebtables, nftables).
  You can write rules like:
    ebtables -A FORWARD --src 00:11:22:33:44:55 -j DROP
    nft add rule bridge filter forward drop

  CONFIG_NETFILTER_FAMILY_BRIDGE is not set    (disabled)
  ═════════════════════════════════════════════
  No netfilter hooks for bridge traffic.
  Bridge just forwards/floods/passes-up directly.
  Smaller kernel, slightly faster bridging (no hook overhead).
```

### Bridge hooks

```c
/* Bridge Hooks */
#define NF_BR_PRE_ROUTING    0   /* After promisc drops, checksum checks */
#define NF_BR_LOCAL_IN       1   /* If the packet is destined for this box */
#define NF_BR_FORWARD        2   /* If the packet is destined for another interface */
#define NF_BR_LOCAL_OUT      3   /* Packets coming from a local process */
#define NF_BR_POST_ROUTING   4   /* Packets about to hit the wire */
#define NF_BR_BROUTING       5   /* Special: ebtables broute table */
```

```text
                            ┌─────────────────────────────────────────┐
                            │           LOCAL PROCESSES               │
                            │        (TCP/IP Stack on br0)            │
                            └──────────────┬───────────────────▲──────┘
                                           │                   │
                                    ┌──────▼──────┐     ┌──────┴──────┐
                                    │NF_BR_LOCAL_ │     │NF_BR_LOCAL_ │
                                    │    OUT      │     │     IN      │
                                    │   (hook 3)  │     │   (hook 1)  │
                                    └──────┬──────┘     └──────▲──────┘
                                           │                   │
    ┌──────────────────────────────────────┼───────────────────┼───────────────┐
    │                                      │                   │               │
    │                              ┌───────▼───────────────────┴────────┐      │
    │                              │                                    │      │
    │                              │        BRIDGING DECISION           │      │
    │                              │     (MAC learning, FDB lookup)     │      │
    │                              │                                    │      │
    │                              └───────┬────────────────────▲───────┘      │
    │                                      │                    │              │
    │                               ┌──────▼──────┐      ┌──────┴──────┐       │
    │                               │ NF_BR_      │      │NF_BR_PRE_   │       │
    │                               │ FORWARD     │      │ ROUTING     │       │
    │                               │  (hook 2)   │      │  (hook 0)   │       │
    │                               └──────┬──────┘      └──────▲──────┘       │
    │                                      │                    │              │
    │                               ┌──────▼──────┐             │              │
    │                               │NF_BR_POST_  │             │              │
    │                               │ ROUTING     │             │              │
    │                               │  (hook 4)   │             │              │
    │                               └──────┬──────┘             │              │
    │    LINUX BRIDGE (br0)                │                    │              │
    └──────────────────────────────────────┼────────────────────┼──────────────┘
                                           │                    │
          ┌────────────────────────────────┼────────────────────┼───────────────┐
          │                                │                    │               │
          │   ┌────────┐            ┌──────▼──────┐      ┌──────┴──────┐        │
          │   │BROUTING│◄───────────│   eth1      │      │    eth0     │        │
          │   │(hook 5)│            │  (egress)   │      │  (ingress)  │        │
          │   └───┬────┘            └──────┬──────┘      └──────▲──────┘        │
          │       │                        │                    │               │
          │       ▼                        ▼                    │               │
          │   Route at L3           ┌──────────────┐            │               │
          │   (bypass bridge)       │   NETWORK    │────────────┘               │
          │                         └──────────────┘                            │
          │                              BRIDGE PORTS                           │
          └─────────────────────────────────────────────────────────────────────┘
```
```c
// Called in nf_hook_bridge_pre() when frame first enters bridge
nf_hook_state_init(&state, NF_BR_PRE_ROUTING,
                   NFPROTO_BRIDGE, skb->dev, NULL, NULL,
                   net, br_handle_frame_finish);

// Called in br_pass_frame_up() - packet destined for bridge's IP
return NF_HOOK(NFPROTO_BRIDGE, NF_BR_LOCAL_IN,
               dev_net(indev), NULL, skb, indev, NULL,
               br_netif_receive_skb);

// Called in __br_forward() - packet being forwarded between ports
if (!local_orig) {
    br_hook = NF_BR_FORWARD;
    ...
}
NF_HOOK(NFPROTO_BRIDGE, br_hook, net, NULL, skb, indev, skb->dev,
        br_forward_finish);

// Called in __br_forward() - packet originated locally
} else {
    br_hook = NF_BR_LOCAL_OUT;
    ...
}
NF_HOOK(NFPROTO_BRIDGE, br_hook, ...);

// Called in br_forward_finish() - just before sending out
int br_forward_finish(struct net *net, struct sock *sk, struct sk_buff *skb)
{
    skb_clear_tstamp(skb);
    return NF_HOOK(NFPROTO_BRIDGE, NF_BR_POST_ROUTING,
                   net, sk, skb, NULL, skb->dev,
                   br_dev_queue_push_xmit);
}

// Special hook for routing decision bypass
nf_hook_state_init(&state, NF_BR_BROUTING, NFPROTO_BRIDGE, ...);

```

### Hook priority order

```c
enum nf_br_hook_priorities {
    NF_BR_PRI_FIRST           = INT_MIN,
    NF_BR_PRI_NAT_DST_BRIDGED = -300,    /* DNAT for bridged */
    NF_BR_PRI_FILTER_BRIDGED  = -200,    /* Filter for bridged */
    NF_BR_PRI_BRNF            = 0,       /* br_netfilter */
    NF_BR_PRI_NAT_DST_OTHER   = 100,     /* DNAT for routed */
    NF_BR_PRI_FILTER_OTHER    = 200,     /* Filter for routed */
    NF_BR_PRI_NAT_SRC         = 300,     /* SNAT */
    NF_BR_PRI_LAST            = INT_MAX,
};
```
	
Forwarded packet (eth0 → eth1):  
`eth0 → PRE_ROUTING → FORWARD → POST_ROUTING → eth1`  
Packet to bridge IP:  
`eth0 → PRE_ROUTING → LOCAL_IN → TCP/IP Stack`.   
Packet from bridge IP:  
`TCP/IP Stack → LOCAL_OUT → POST_ROUTING → eth1`.  

## The forwarding decision: `br_handle_frame_finish()`

This is where the decision is made (simplified; the full source is in the appendix):

```c
// net/bridge/br_input.c (lines 73-240)
int br_handle_frame_finish(struct net *net, struct sock *sk, struct sk_buff *skb)
{
    struct net_bridge_port *p = br_port_get_rcu(skb->dev);
    struct net_bridge_fdb_entry *dst = NULL;
    bool local_rcv, mcast_hit = false;
    struct net_bridge *br;
    u16 vid = 0;

    br = p->br;
    
    // ... VLAN and state checks ...

    // MAC learning - update FDB with source MAC
    if (p->flags & BR_LEARNING)
        br_fdb_update(br, p, eth_hdr(skb)->h_source, vid, 0);

    promisc = !!(br->dev->flags & IFF_PROMISC);
    local_rcv = promisc;   // Promisc mode = always copy to local stack

    // BROADCAST: Always deliver locally
    if (is_broadcast_ether_addr(eth_hdr(skb)->h_dest)) {
        pkt_type = BR_PKT_BROADCAST;
        local_rcv = true;    // <-- LOCAL DELIVERY
    }
    
    // UNICAST: Check FDB for destination
    if (pkt_type == BR_PKT_UNICAST) {
        dst = br_fdb_find_rcu(br, eth_hdr(skb)->h_dest, vid);
    }

    if (dst) {
        // FDB entry found - is it LOCAL (bridge's own MAC)?
        if (test_bit(BR_FDB_LOCAL, &dst->flags))
            return br_pass_frame_up(skb, false);  // <-- TO HOST IP STACK!

        // Forward to the port
        br_forward(dst->dst, skb, local_rcv, false);
    } else {
        // Unknown destination - flood to all ports
        br_flood(br, skb, pkt_type, local_rcv, false, vid);
    }

    // If local_rcv is set, also pass up to host
    if (local_rcv)
        return br_pass_frame_up(skb, promisc);

    return 0;
}
```

What it does:

- If learning is enabled, it updates the **Forwarding Database (FDB)**, mapping the source MAC to the incoming port.
- **Unicast:** looks up the destination MAC in the FDB.
  - Found → `br_forward()` to that port.
  - Not found → `br_flood()` to every port.
- **Multicast/broadcast:** uses the multicast database, or floods.
- If the destination MAC is the bridge's own (a **LOCAL** FDB entry) → `br_pass_frame_up()`.

```text
┌────────────────────────────────────────────────────────────────────┐
│                      PACKET ARRIVES ON eth0                        │
│                              │                                     │
│                              ▼                                     │
│                    br_handle_frame()                               │
│                              │                                     │
│                              ▼                                     │
│               br_handle_frame_finish()                             │
│                              │                                     │
│              ┌───────────────┴───────────────┐                     │
│              │                               │                     │
│              ▼                               ▼                     │
│    FDB lookup for dst MAC          Is dst MAC == bridge MAC?       │
│              │                               │                     │
│    ┌─────────┴─────────┐                    YES                    │
│    │                   │                     │                     │
│   FOUND            NOT FOUND                 ▼                     │
│    │                   │         ┌──────────────────────┐          │
│    ▼                   ▼         │  BR_FDB_LOCAL set?   │          │
│ br_forward()      br_flood()     └──────────┬───────────┘          │
│    │                   │                    YES                    │
│    ▼                   ▼                     │                     │
│  Out via          Out via all                ▼                     │
│  port X           ports              br_pass_frame_up()            │
│                                              │                     │
│                                              ▼                     │
│                                    skb->dev = br0                  │
│                                              │                     │
│                                              ▼                     │
│                                   netif_receive_skb()              │
│                                              │                     │
│                                              ▼                     │
│                                    TCP/IP STACK                    │
│                                      (br0 IP)                      │
└────────────────────────────────────────────────────────────────────┘
```

`dst = br_fdb_find_rcu(br, eth_hdr(skb)->h_dest, vid);` — if an entry is found, the kernel checks `BR_FDB_LOCAL`:

- **Set:** the MAC belongs to the bridge interface itself or one of its ports → `return br_pass_frame_up(skb, false)`.
- **Not set:** the MAC belongs to an external device (like a VM) → `br_forward()` sends it out another port.

### Broadcast and promiscuous mode

```c
if (is_multicast_ether_addr(eth_hdr(skb)->h_dest)) {
		/* by definition the broadcast is also a multicast address */
		if (is_broadcast_ether_addr(eth_hdr(skb)->h_dest)) {
			pkt_type = BR_PKT_BROADCAST;
			local_rcv = true;
		} else {
			pkt_type = BR_PKT_MULTICAST;
			if (br_multicast_rcv(&brmctx, &pmctx, vlan, skb, vid))
				goto drop;
		}
	}
```

For a broadcast (`FF:FF:FF:FF:FF:FF`), `local_rcv` is set to true: the host's stack sees the packet, while `br_flood()` makes sure every VM sees it too.

```c
promisc = !!(br->dev->flags & IFF_PROMISC);
local_rcv = promisc;
```

In promiscuous mode `local_rcv` is forced to `true`: the packet is forwarded to its destination **and** a clone is sent up to the host's CPU.

## Passing frames up to the host stack

### `br_pass_frame_up()`

Sends frames up to the TCP/IP stack (for the bridge's own IP):

```c
// net/bridge/br_input.c (lines 33-69)
static int br_pass_frame_up(struct sk_buff *skb, bool promisc)
{
    struct net_device *indev, *brdev = BR_INPUT_SKB_CB(skb)->brdev;
    struct net_bridge *br = netdev_priv(brdev);
    struct net_bridge_vlan_group *vg;

    dev_sw_netstats_rx_add(brdev, skb->len);  // Update bridge device stats

    vg = br_vlan_group_rcu(br);

    /* Bridge is just like any other port.  Make sure the
     * packet is allowed except in promisc mode when someone
     * may be running packet capture.
     */
    if (!(brdev->flags & IFF_PROMISC) &&
        !br_allowed_egress(vg, skb)) {
        kfree_skb(skb);
        return NET_RX_DROP;
    }

    indev = skb->dev;
    skb->dev = brdev;       // <-- KEY: Change device to bridge itself
    skb = br_handle_vlan(br, NULL, vg, skb);
    if (!skb)
        return NET_RX_DROP;

    // Deliver to the network stack via NF_BR_LOCAL_IN hook
    return NF_HOOK(NFPROTO_BRIDGE, NF_BR_LOCAL_IN,
                   dev_net(indev), NULL, skb, indev, NULL,
                   br_netif_receive_skb);  // <-- Goes to TCP/IP stack
}
```

- `skb->dev = brdev;` — the packet is labeled as coming from the physical port (e.g. `eth0`); the bridge relabels it as coming from the bridge device (`br0`).
- It then calls `br_netif_receive_skb`, which simply calls `netif_receive_skb(skb)`.

The key transformation is `skb->dev = brdev`: the packet now appears to have arrived directly on `br0`, so the IP layer checks whether the destination IP matches the IP configured on `br0`.

```text
┌─────────────────────────────────────────────────────────────────────────┐
│  1. br_pass_frame_up()                    [net/bridge/br_input.c:33]    │
│         │                                                               │
│         │  skb->dev = brdev;     ◄── Changes device to br0              │
│         │                                                               │
│         ▼                                                               │
│  2. NF_HOOK(NF_BR_LOCAL_IN, ..., br_netif_receive_skb)                  │
│         │                                                               │
│         │  Runs netfilter hooks (ebtables, etc.)                        │
│         │                                                               │
│         ▼                                                               │
│  3. br_netif_receive_skb()                [net/bridge/br_input.c:27]    │
│         │                                                               │
│         ▼                                                               │
│  4. netif_receive_skb(skb)                [net/core/dev.c:6172]         │
│         │                                                               │
│         │  Main network stack entry point                               │
│         │                                                               │
│         ▼                                                               │
│  5. __netif_receive_skb_core()            [net/core/dev.c:5922]         │
│         │                                                               │
│         │  Looks up protocol handler based on skb->protocol             │
│         │  (ETH_P_IP = 0x0800 for IPv4)                                 │
│         │                                                               │
│         ▼                                                               │
│  6. deliver_ptype_list_skb()              [net/core/dev.c]              │
│         │                                                               │
│         │  Finds registered packet_type for ETH_P_IP                    │
│         │                                                               │
│         ▼                                                               │
│  7. ip_rcv()                              [net/ipv4/ip_input.c:564]     │
│         │                                                               │
│         │  IPv4 receive handler                                         │
│         │                                                               │
│         ▼                                                               │
│  8. ip_rcv_finish() → ip_local_deliver() → TCP/UDP handlers             │
└─────────────────────────────────────────────────────────────────────────┘
```

```c
// net/bridge/br_input.c:27-31
static int br_netif_receive_skb(struct net *net, struct sock *sk, struct sk_buff *skb)
{
    br_drop_fake_rtable(skb);
    return netif_receive_skb(skb);   // <-- Enter main network stack
}
//Core receive dispatches by protocol
// net/core/dev.c:6144-6155
static int __netif_receive_skb_one_core(struct sk_buff *skb, bool pfmemalloc)
{
    struct packet_type *pt_prev = NULL;
    
    ret = __netif_receive_skb_core(&skb, pfmemalloc, &pt_prev);
    
    if (pt_prev)
        // INDIRECT_CALL to ip_rcv or ipv6_rcv based on protocol
        ret = INDIRECT_CALL_INET(pt_prev->func, ipv6_rcv, ip_rcv, 
                                  skb, skb->dev, pt_prev, orig_dev);
    return ret;
}

//Protocol handler lookup
// net/core/dev.c:6088-6095  (inside __netif_receive_skb_core)
type = skb->protocol;   // e.g., ETH_P_IP (0x0800)

// Find handler in ptype_base hash table
deliver_ptype_list_skb(skb, &pt_prev, orig_dev, type,
                       &ptype_base[ntohs(type) & PTYPE_HASH_MASK]);

// IP layer receives the packet
// net/ipv4/ip_input.c:564-576
int ip_rcv(struct sk_buff *skb, struct net_device *dev, 
           struct packet_type *pt, struct net_device *orig_dev)
{
    struct net *net = dev_net(dev);   // dev is now br0!
    
    skb = ip_rcv_core(skb, net);      // Validate IP header
    
    // Continue through routing to local delivery
    return NF_HOOK(NFPROTO_IPV4, NF_INET_PRE_ROUTING,
                   net, NULL, skb, dev, NULL,
                   ip_rcv_finish);
}
```

### `netif_receive_skb()`

**Device lookup uses hashing:** a system can have thousands of interfaces (especially in cloud/container environments), so the kernel uses hash tables, not simple lists, to find devices and protocol handlers.

```c
/**
 *	netif_receive_skb - process receive buffer from network
 *	@skb: buffer to process
 *
 *	netif_receive_skb() is the main receive data processing function.
 *	It always succeeds. The buffer may be dropped during processing
 *	for congestion control or by the protocol layers.
 *
 *	This function may only be called from softirq context and interrupts
 *	should be enabled.
 *
 *	Return values (usually ignored):
 *	NET_RX_SUCCESS: no congestion
 *	NET_RX_DROP: packet was dropped
 */
int netif_receive_skb(struct sk_buff *skb)
{
	int ret;

	trace_netif_receive_skb_entry(skb);

	ret = netif_receive_skb_internal(skb);
	trace_netif_receive_skb_exit(ret);

	return ret;
}

static int __netif_receive_skb(struct sk_buff *skb)
{
	int ret;

	if (sk_memalloc_socks() && skb_pfmemalloc(skb)) {
		unsigned int noreclaim_flag;

		/*
		 * PFMEMALLOC skbs are special, they should
		 * - be delivered to SOCK_MEMALLOC sockets only
		 * - stay away from userspace
		 * - have bounded memory usage
		 *
		 * Use PF_MEMALLOC as this saves us from propagating the allocation
		 * context down to all allocation sites.
		 */
		noreclaim_flag = memalloc_noreclaim_save();
		ret = __netif_receive_skb_one_core(skb, true);
		memalloc_noreclaim_restore(noreclaim_flag);
	} else
		ret = __netif_receive_skb_one_core(skb, false);

	return ret;
}

static int __netif_receive_skb_one_core(struct sk_buff *skb, bool pfmemalloc)
{
	struct net_device *orig_dev = skb->dev;
	struct packet_type *pt_prev = NULL;
	int ret;

	ret = __netif_receive_skb_core(&skb, pfmemalloc, &pt_prev);
	if (pt_prev)
		ret = INDIRECT_CALL_INET(pt_prev->func, ipv6_rcv, ip_rcv, skb,
					 skb->dev, pt_prev, orig_dev);
	return ret;
}
```

### `sk_buff` and the Ethernet header

The `sk_buff` is the standard packet structure across the whole kernel: a wrapper containing the raw network data plus pointers to where the different headers (Ethernet, IP, TCP) begin.

- Inside it, a pointer called `data` marks the current position.
- **In the bridge layer**, `skb->data` points to the very start of the packet — the destination MAC.
- **Transition to IP:** just before calling `ip_rcv`, the kernel "pulls" (`skb_pull_inline`), moving `skb->data` forward by 14 bytes — the size of a standard Ethernet header.
- `len` is the size from the current `data` pointer to the end, so it decreases by 14 when the MAC header is skipped.

```c
//if_ether.h
/*
 *	IEEE 802.3 Ethernet magic constants.  The frame sizes omit the preamble
 *	and FCS/CRC (frame check sequence).
 */

#define ETH_ALEN	6		/* Octets in one ethernet addr	 */
#define ETH_TLEN	2		/* Octets in ethernet type field */
#define ETH_HLEN	14		/* Total octets in header.	 */
#define ETH_ZLEN	60		/* Min. octets in frame sans FCS */
#define ETH_DATA_LEN	1500		/* Max. octets in payload	 */
#define ETH_FRAME_LEN	1514		/* Max. octets in frame sans FCS */
#define ETH_FCS_LEN	4		/* Octets in the FCS		 */

#define ETH_MIN_MTU	68		/* Min IPv4 MTU per RFC791	*/
#define ETH_MAX_MTU	0xFFFFU		/* 65535, same as IP_MAX_MTU	*/
```

### The full receive path, step by step

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                          LAYER 2 (BRIDGE)                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. HARDWARE/DRIVER                                                         │
│     NIC receives frame → Driver calls napi_gro_receive()                    │
│                              │                                              │
│                              ▼                                              │
│  2. netif_receive_skb() [net/core/dev.c]                                    │
│     • skb->dev = eth0 (physical NIC)                                        │
│     • Checks for rx_handler (bridge has one registered!)                    │
│                              │                                              │
│                              ▼                                              │
│  3. rx_handler = br_handle_frame [net/bridge/br_input.c:337]                │
│     • Validates frame                                                       │
│     • Enters bridge code                                                    │
│                              │                                              │
│                              ▼                                              │
│  4. nf_hook_bridge_pre() → NF_BR_PRE_ROUTING hook                           │
│     • ebtables PREROUTING chain runs here                                   │
│                              │                                              │
│                              ▼                                              │
│  5. br_handle_frame_finish() [net/bridge/br_input.c:73]                     │
│     • MAC learning (update FDB with source MAC)                             │
│     • FDB lookup for destination MAC                                        │
│                              │                                              │
│              ┌───────────────┴───────────────┐                              │
│              │                               │                              │
│              ▼                               ▼                              │
│     dst->flags has               dst NOT found OR                           │
│     BR_FDB_LOCAL set?            dst is another port                        │
│     (bridge's own MAC)                       │                              │
│              │                               │                              │
│             YES                              ▼                              │
│              │                        br_forward() or                       │
│              │                        (stays at L2)                         │
│              ▼                                                              │
│  6. br_pass_frame_up() [net/bridge/br_input.c:33]                           │
│                                                                             │
│     ┌──────────────────────────────────────────────────────────────┐        │
│     │  KEY TRANSFORMATION:                                         │        │
│     │                                                              │        │
│     │  skb->dev = brdev;   // Change from eth0 → br0               │        │
│     │                                                              │        │
│     │  This makes the packet appear as if it arrived on br0!       │        │
│     └──────────────────────────────────────────────────────────────┘        │
│                              │                                              │
│                              ▼                                              │
│  7. NF_HOOK(NF_BR_LOCAL_IN, ..., br_netif_receive_skb)                      │
│     • ebtables INPUT chain runs here                                        │
│                              │                                              │
│                              ▼                                              │
│  8. br_netif_receive_skb() [net/bridge/br_input.c:27]                       │
│     return netif_receive_skb(skb);  ──────────────────────┐                 │
│                                                            │                │
└────────────────────────────────────────────────────────────┼────────────────┘
                                                             │
                    ┌────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          RE-ENTER NET CORE                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  9. netif_receive_skb() [net/core/dev.c:6320]                               │
│     • NOW skb->dev = br0 (not eth0!)                                        │
│     • br0 has NO rx_handler → passes through                                │
│                              │                                              │
│                              ▼                                              │
│  10. __netif_receive_skb_core() [net/core/dev.c:5922]                       │
│     • Looks up protocol handler by skb->protocol                            │
│     • skb->protocol = 0x0800 (ETH_P_IP)                                     │
│                              │                                              │
│                              ▼                                              │
│  11. ptype_base lookup                                                      │
│      ┌─────────────────────────────────────────────┐                        │
│      │  Protocol handlers registered at boot:      │                        │
│      │                                             │                        │
│      │  ETH_P_IP   (0x0800) → ip_rcv()             │                        │
│      │  ETH_P_IPV6 (0x86DD) → ipv6_rcv()           │                        │
│      │  ETH_P_ARP  (0x0806) → arp_rcv()            │                        │
│      └─────────────────────────────────────────────┘                        │
│                              │                                              │
│                              ▼                                              │
│  12. INDIRECT_CALL_INET(pt_prev->func, ipv6_rcv, ip_rcv, ...)               │
│      [net/core/dev.c:6152]                                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                                             │
                    ┌────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          LAYER 3 (IP)                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  13. ip_rcv() [net/ipv4/ip_input.c:564]                                     │
│      • dev = br0 (this is key!)                                             │
│      • Validates IP header (checksum, length, version)                      │
│                              │                                              │
│                              ▼                                              │
│  14. NF_HOOK(NF_INET_PRE_ROUTING, ..., ip_rcv_finish)                       │
│      • iptables PREROUTING runs here                                        │
│                              │                                              │
│                              ▼                                              │
│  15. ip_rcv_finish() [net/ipv4/ip_input.c:439]                              │
│                              │                                              │
│                              ▼                                              │
│  16. ip_rcv_finish_core() [net/ipv4/ip_input.c:322]                         │
│      • Calls ip_route_input_noref()                                         │
│      • ROUTING DECISION happens here!                                       │
│                              │                                              │
│      ┌───────────────────────┴───────────────────────┐                      │
│      │                                               │                      │
│      │  ip_route_input_noref() checks:               │                      │
│      │                                               │                      │
│      │  Is dst IP == br0's IP address?               │                      │
│      │      │                                        │                      │
│      │     YES → Create route with RTCF_LOCAL flag   │                      │
│      │           rt->dst.input = ip_local_deliver    │                      │
│      │                                               │                      │
│      │     NO  → Forward route                       │                      │
│      │           rt->dst.input = ip_forward          │                      │
│      │                                               │                      │
│      └───────────────────────────────────────────────┘                      │
│                              │                                              │
│                              ▼                                              │
│  17. dst_input(skb) [include/net/dst.h:472]                                 │
│      return skb_dst(skb)->input(skb);                                       │
│      // For local: calls ip_local_deliver                                   │
│                              │                                              │
│                              ▼                                              │
│  18. ip_local_deliver() [net/ipv4/ip_input.c:250]                           │
│      • Reassembles IP fragments if needed                                   │
│                              │                                              │
│                              ▼                                              │
│  19. NF_HOOK(NF_INET_LOCAL_IN, ..., ip_local_deliver_finish)                │
│      • iptables INPUT chain runs here                                       │
│                              │                                              │
│                              ▼                                              │
│  20. ip_local_deliver_finish() [net/ipv4/ip_input.c:229]                    │
│      • Strips IP header                                                     │
│                              │                                              │
│                              ▼                                              │
│  21. ip_protocol_deliver_rcu()                                              │
│      • Looks up transport protocol handler                                  │
│                              │                                              │
│              ┌───────────────┴───────────────┐                              │
│              │                               │                              │ 
│              ▼                               ▼                              │
│      IPPROTO_TCP (6)              IPPROTO_UDP (17)                          │
│              │                               │                              │
│              ▼                               ▼                              │
│      tcp_v4_rcv()                  udp_rcv()                                │
│              │                               │                              │
│              ▼                               ▼                              │
│      Socket lookup & delivery to application                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

In code:

1. The bridge registers an `rx_handler` on enslaved interfaces:

```c
// net/bridge/br_if.c - when adding port to bridge
netdev_rx_handler_register(dev, br_handle_frame, p);
```

2. The network core checks for an `rx_handler`:

 ```c
 // net/core/dev.c:6035 (in __netif_receive_skb_core)
rx_handler = rcu_dereference(skb->dev->rx_handler);
if (rx_handler) {
    switch (rx_handler(&skb)) {        // Calls br_handle_frame!
    case RX_HANDLER_CONSUMED:
        ret = NET_RX_SUCCESS;
        goto out;
    ...
    }
}
```

3. The bridge changes the device and re-injects:

```c
// net/bridge/br_input.c:33
static int br_pass_frame_up(struct sk_buff *skb, bool promisc)
{
    skb->dev = brdev;           // eth0 → br0
    ...
    return NF_HOOK(..., br_netif_receive_skb);
}

// net/bridge/br_input.c:27
static int br_netif_receive_skb(...)
{
    return netif_receive_skb(skb);  // Re-enter network stack!
}
```

4. Protocol dispatch finds the IP handler:

```c
// net/core/dev.c:6152
ret = INDIRECT_CALL_INET(pt_prev->func, ipv6_rcv, ip_rcv,
                          skb, skb->dev, pt_prev, orig_dev);
```

5. Routing decides on local delivery:

```c
// net/ipv4/route.c:1667-1668
if (flags & RTCF_LOCAL)
    rt->dst.input = ip_local_deliver;   // Local IP → deliver locally
```

6. `dst_input()` calls the routing decision:

```c
// include/net/dst.h:472
static inline int dst_input(struct sk_buff *skb)
{
    return skb_dst(skb)->input(skb);    // Calls ip_local_deliver!
}
```

## The FDB and `BR_FDB_LOCAL`

`BR_FDB_LOCAL` is a flag in the bridge's FDB marking a MAC address as belonging to the bridge or its ports themselves (not learned from traffic). The magic: when the bridge adds its own MAC to the FDB it marks it "local", so any packet destined to that MAC goes up to the host's IP stack instead of being forwarded out another port.

```c
/* bridge fdb flags */
enum {
    BR_FDB_LOCAL,           // MAC belongs to bridge/port itself
    BR_FDB_STATIC,          // Entry won't age out
    BR_FDB_STICKY,          // Don't change port for this MAC
    BR_FDB_ADDED_BY_USER,   // Added via netlink/ioctl
    BR_FDB_ADDED_BY_EXT_LEARN,  // Learned from hardware offload
    BR_FDB_OFFLOADED,       // Offloaded to hardware
    BR_FDB_NOTIFY,          // Send notifications
    BR_FDB_NOTIFY_INACTIVE, // Entry is inactive
    BR_FDB_LOCKED,          // MAC-Auth Bypass locked entry
    BR_FDB_DYNAMIC_LEARNED, // Learned from traffic
};
```

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                        BRIDGE FDB (Forwarding Database)                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  LOCAL Entries (BR_FDB_LOCAL = 1)                                   │    │
│  │  ─────────────────────────────────────                              │    │
│  │  • Bridge's own MAC address                                         │    │
│  │  • Port's own MAC address (when port added)                         │    │
│  │  • Packets to these MACs → DELIVER LOCALLY (to host IP stack)       │    │
│  │  • NEVER forwarded out ports                                        │    │
│  │  • Never age out (also has BR_FDB_STATIC)                           │    │
│  │                                                                     │    │
│  │  Example:                                                           │    │
│  │    MAC: aa:bb:cc:dd:ee:00  Port: NULL (bridge itself)  FLAGS: LOCAL │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  LEARNED Entries (BR_FDB_LOCAL = 0)                                 │    │
│  │  ───────────────────────────────────                                │    │
│  │  • MACs learned from network traffic                                │    │
│  │  • Packets to these MACs → FORWARD to associated port               │    │
│  │  • Age out after br->ageing_time (default 5 minutes)                │    │
│  │                                                                     │    │
│  │  Example:                                                           │    │
│  │    MAC: 11:22:33:44:55:66  Port: eth0   FLAGS: (none)               │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### When local entries are created

An entry is marked local at the moment of creation, based on who "owns" the MAC — the bridge distinguishes MACs that are part of the host system (local) from external devices:

- **Bridge creation:** `br0`'s own MAC is inserted with `BR_FDB_LOCAL` set.
- **Enslaving a port:** `ip link set eth0 master br0` — the bridge adds `eth0`'s MAC to the FDB marked `LOCAL`.
- **Static entries:** added manually with the `self` and `local` keywords, e.g. `bridge fdb add [MAC] dev br0 self local`.

When a VM (e.g. QEMU/KVM) sends a packet through the bridge, the bridge **learns** the VM's MAC and adds it **without** the LOCAL flag. Keeping `eth0`'s MAC as LOCAL ensures that if something targets the physical hardware's address, the host OS can still respond (e.g. hardware management or diagnostic tools). Internally, learned entries are added with `is_local = 0`; the bridge's own ports with `is_local = 1`.

1. When the bridge is created:

```c
// net/bridge/br_device.c - Bridge setup
// Bridge's MAC added with BR_FDB_LOCAL
```

2. When a port is added (`br_if.c:649`):

```c
// Adding port (e.g., eth0) to bridge
if (br_fdb_add_local(br, p, dev->dev_addr, 0))
    netdev_err(dev, "failed insert local address...");
```

3. `fdb_add_local()` (`br_fdb.c:426-452`):

 ```c
 static int fdb_add_local(struct net_bridge *br, struct net_bridge_port *source,
                         const unsigned char *addr, u16 vid)
{
    struct net_bridge_fdb_entry *fdb;

    if (!is_valid_ether_addr(addr))
        return -EINVAL;

    fdb = br_fdb_find(br, addr, vid);
    if (fdb) {
        // Already exists as LOCAL, nothing to do
        if (test_bit(BR_FDB_LOCAL, &fdb->flags))
            return 0;
        // Conflict: learned MAC same as port's MAC - delete learned one
        br_warn(br, "adding interface with same address as received packet");
        fdb_delete(br, fdb, true);
    }

    // Create entry with BOTH BR_FDB_LOCAL and BR_FDB_STATIC flags
    fdb = fdb_create(br, source, addr, vid,
                     BIT(BR_FDB_LOCAL) | BIT(BR_FDB_STATIC));
    if (!fdb)
        return -ENOMEM;

    fdb_add_hw_addr(br, addr);
    fdb_notify(br, fdb, RTM_NEWNEIGH, true);
    return 0;
}
```

### How `BR_FDB_LOCAL` controls packet flow (`br_input.c:221`)

```c
// In br_handle_frame_finish()
dst = br_fdb_find_rcu(br, eth_hdr(skb)->h_dest, vid);

if (dst) {
    // Found destination MAC in FDB
    
    if (test_bit(BR_FDB_LOCAL, &dst->flags))
        // ═══════════════════════════════════════════════════
        // KEY: BR_FDB_LOCAL means "this is OUR MAC"
        // Don't forward - send up to host's network stack!
        // ═══════════════════════════════════════════════════
        return br_pass_frame_up(skb, false);

    // Not local - forward to the port
    br_forward(dst->dst, skb, local_rcv, false);
} else {
    // Unknown MAC - flood to all ports
    br_flood(br, skb, pkt_type, local_rcv, false, vid);
}
```

### Where `dst` points

The FDB maps MAC addresses to **ports**:

```text
┌────────────────────────────────────────────────────────────────────┐
│  FDB Entry Question: "Where do I send packets to MAC X?"           │
│                                                                    │
│  If dst = &eth0_port  →  "Send out eth0"                           │
│  If dst = &eth1_port  →  "Send out eth1"                           │
│  If dst = NULL        →  "Give to the bridge itself (to host)"     │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

```text
Someone sends packet to aa:bb:cc:dd:ee:00 (br0's MAC)

1. Packet arrives on eth0 (a real port)

2. Bridge looks up destination MAC in FDB:
   
   ┌──────────────────────────────────────────────────────┐
   │  Found: aa:bb:cc:dd:ee:00                            │
   │         dst = NULL                                   │
   │         flags = BR_FDB_LOCAL                         │
   │                                                      │
   │  Meaning: "This MAC is OURS, not a port's"           │
   └──────────────────────────────────────────────────────┘

3. Since it's LOCAL → deliver up to host TCP/IP stack

4. Host application receives the packet
```

**Why is `dst` NULL?** Because there's no port to forward to: the destination is the bridge **device**, not a port.

```c
struct net_bridge_fdb_entry {
    struct net_bridge_fdb_key  key;    // ← Contains the MAC address
    struct net_bridge_port     *dst;   // ← Points to a PORT (not MAC!)
    unsigned long              flags;  // ← Contains BR_FDB_LOCAL
};

struct net_bridge_fdb_key {
    mac_addr addr;   // ← The actual MAC address (NOT null!)
    u16 vlan_id;
};
```

```sh
$ ip link show eth0
    link/ether 52:54:00:11:11:11   # eth0's MAC

$ ip link show eth1  
    link/ether 52:54:00:22:22:22   # eth1's MAC

$ ip link show br0
    link/ether aa:bb:cc:dd:ee:00   # br0's MAC
```

```text
┌────────────────────────────────────────────────────────────────────────┐
│                            HOST                                        │
│                                                                        │
│   ┌─────────────────────────────────────────────────────────────────┐  │
│   │  br0 device                                                     │  │
│   │  MAC: aa:bb:cc:dd:ee:00                                         │  │
│   │                                                                 │  │
│   │    ┌───────────────────┐    ┌───────────────────┐               │  │
│   │    │   eth0 (port)     │    │   eth1 (port)     │               │  │
│   │    │ MAC: 52:54:00:11  │    │ MAC: 52:54:00:22  │               │  │
│   │    └─────────┬─────────┘    └─────────┬─────────┘               │  │
│   └──────────────┼──────────────────────────┼───────────────────────┘  │
│                  │                          │                          │
└──────────────────┼──────────────────────────┼──────────────────────────┘
                   ▼                          ▼
               Network                    Network
```

Three devices, three MACs. All three need `BR_FDB_LOCAL`, because a packet to `br0`'s, `eth0`'s, or `eth1`'s MAC must be delivered to the host.

The FDB after setup:

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│  FDB Entries:                                                               │
│                                                                             │
│  ┌────────────────────┬─────────────┬──────────────┬──────────────────────┐ │
│  │ MAC Address        │ dst (port)  │ BR_FDB_LOCAL │ Why?                 │ │
│  ├────────────────────┼─────────────┼──────────────┼──────────────────────┤ │
│  │ aa:bb:cc:dd:ee:00  │ NULL        │ YES          │ br0's own MAC        │ │
│  │ (br0's MAC)        │             │              │                      │ │
│  ├────────────────────┼─────────────┼──────────────┼──────────────────────┤ │
│  │ 52:54:00:11:11:11  │ eth0_port   │ YES          │ eth0's own MAC       │ │
│  │ (eth0's MAC)       │             │              │                      │ │
│  ├────────────────────┼─────────────┼──────────────┼──────────────────────┤ │
│  │ 52:54:00:22:22:22  │ eth1_port   │ YES          │ eth1's own MAC       │ │
│  │ (eth1's MAC)       │             │              │                      │ │
│  ├────────────────────┼─────────────┼──────────────┼──────────────────────┤ │
│  │ 99:88:77:66:55:44  │ eth0_port   │ NO           │ Learned: someone on  │ │
│  │ (some other device)│             │              │ the network via eth0 │ │
│  └────────────────────┴─────────────┴──────────────┴──────────────────────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Example: a container's veth on the bridge

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                                HOST                                         │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                         br0 (bridge)                                │   │
│   │                    MAC: aa:bb:cc:dd:ee:00                           │   │
│   │                                                                     │   │
│   │       ┌──────────────────┐           ┌──────────────────┐           │   │
│   │       │    veth-host     │           │       eth0       │           │   │
│   │       │ MAC: fe:00:00:01 │           │ MAC: 52:54:00:01 │           │   │
│   │       │   (enslaved)     │           │   (enslaved)     │           │   │
│   │       └────────┬─────────┘           └──────────────────┘           │   │
│   └────────────────┼────────────────────────────────────────────────────┘   │
│                    │                                                        │
│              veth pair                                                      │
│             (virtual wire)                                                  │
│                    │                                                        │
└────────────────────┼────────────────────────────────────────────────────────┘
                     │
┌────────────────────┼────────────────────────────────────────────────────────┐
│  CONTAINER         │                                                        │
│  NAMESPACE         │                                                        │
│                    │                                                        │
│       ┌────────────▼─────────┐                                              │
│       │    veth-container    │                                              │
│       │   MAC: fe:00:00:02   │                                              │
│       │   IP: 192.168.1.10   │                                              │
│       └──────────────────────┘                                              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

Adding `veth-host` to the bridge:

```c
// net/bridge/br_if.c - when "ip link set veth-host master br0" runs

int br_add_if(struct net_bridge *br, struct net_device *dev) 
{
    // Create port structure for veth-host
    p = new_nbp(br, dev);  // struct net_bridge_port
    
    // Register rx_handler - THIS IS KEY!
    netdev_rx_handler_register(dev, br_get_rx_handler(dev), p);
    
    // Add veth-host's MAC to FDB with BR_FDB_LOCAL
    br_fdb_add_local(br, p, dev->dev_addr, 0);
    
    // ...
}
```

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│  FDB Entries:                                                               │
│                                                                             │
│  MAC Address        │ dst (port)     │ BR_FDB_LOCAL │ Created By            │
│  ───────────────────┼────────────────┼──────────────┼────────────────────── │
│  aa:bb:cc:dd:ee:00  │ NULL           │ YES          │ Bridge creation       │
│  (br0's MAC)        │                │              │                       │
│                     │                │              │                       │
│  fe:00:00:01        │ veth-host port │ YES          │ br_fdb_add_local()    │
│  (veth-host MAC)    │                │              │ when port added       │
│                     │                │              │                       │
│  fe:00:00:02        │ veth-host port │ NO           │ Learned when          │
│  (veth-container)   │                │              │ container sends pkt   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

`BR_FDB_LOCAL` means the MAC is local to the bridge domain, and the entry can point to either:

- a specific bridge port: `dst` = that port;
- the bridge device itself: `dst` = NULL.

```c
// Check if it's the bridge's own entry: dst is NULL
!f->dst && !test_bit(BR_FDB_ADDED_BY_USER, &f->flags)

// Add bridge's MAC with NULL as source (meaning dst will be NULL)
fdb_add_local(br, NULL, newaddr, 0);
//                ▲
//                └── NULL = no port = bridge itself
```
```c
// For BRIDGE's own MAC
fdb_add_local(br, NULL, br->dev->dev_addr, 0);
//                ▲
//                └── source = NULL → fdb->dst = NULL

// For PORT's MAC
fdb_add_local(br, p, dev->dev_addr, 0);
//                ▲
//                └── source = port → fdb->dst = port
```

### Docker example: all host-side MACs are local

Local entries are FDB entries marked `BR_FDB_LOCAL` for MACs belonging to the bridge domain itself (not learned from traffic) — each bridge port owns a MAC. They're MACs of devices that are part of the bridge, not remote hosts discovered via forwarding; frames to them are delivered up the stack instead of forwarded out a port.

```text
                          ╔═══════════════════════════════════════╗
                          ║         HOST NETWORK STACK            ║
                          ║     (iptables · routing · TCP/IP)     ║
                          ╚═══════════════════╤═══════════════════╝
                                              │
                                              │ IP: 172.17.0.1/16
                                              │
                          ┌───────────────────┴───────────────────┐
                          │                                       │
                          │          docker0  (br0)               │
                          │                                       │
                          │       MAC: AA:AA:AA:AA:AA:00          │
                          │       FDB: LOCAL  ·  dst = NULL       │
                          │                                       │
                          └────┬──────────────┼──────────────┬────┘
                               │              │              │
                               │              │              │
                ┌──────────────┘              │              └─────────────┐
                │                             │                            │
        ┌───────┴────────┐           ┌────────┴───────┐           ┌────────┴───────┐
        │  veth-host0    │           │  veth-host1    │           │  veth-host2    │
        │                │           │                │           │                │
        │  MAC: BB:BB:01 │           │  MAC: BB:BB:02 │           │  MAC: BB:BB:03 │
        │  FDB: LOCAL    │           │  FDB: LOCAL    │           │  FDB: LOCAL    │
        │  dst = port0   │           │  dst = port1   │           │  dst = port2   │
        └───────┬────────┘           └────────┬───────┘           └────────┬───────┘
                │                             │                            │
                │ veth pair                   │ veth pair                  │ veth pair
                │ (virtual cable)             │ (virtual cable)            │ (virtual cable)
                │                             │                            │
 ═══════════════╪═════════════════════════════╪════════════════════════════╪════════════
    N E T W O R K    N A M E S P A C E    B O U N D A R Y
 ═══════════════╪═════════════════════════════╪════════════════════════════╪════════════
                │                             │                            │
        ┌───────┴────────┐           ┌────────┴───────┐           ┌────────┴───────┐
        │  eth0          │           │  eth0          │           │  eth0          │
        │                │           │                │           │                │
        │  MAC: CC:CC:01 │           │  MAC: CC:CC:02 │           │  MAC: CC:CC:03 │
        │  FDB: DYNAMIC  │           │  FDB: DYNAMIC  │           │  FDB: DYNAMIC  │
        │  dst = port0   │           │  dst = port1   │           │  dst = port2   │
        └───────┬────────┘           └────────┬───────┘           └────────┬───────┘
                │                             │                            │
        ┌───────┴────────┐           ┌────────┴───────┐           ┌────────┴───────┐
        │                │           │                │           │                │
        │  Container 1   │           │  Container 2   │           │  Container 3   │
        │                │           │                │           │                │
        │  172.17.0.2    │           │  172.17.0.3    │           │  172.17.0.4    │
        │                │           │                │           │                │
        │  ┌──────────┐  │           │  ┌──────────┐  │           │  ┌──────────┐  │
        │  │  nginx   │  │           │  │  redis   │  │           │  │  app     │  │
        │  └──────────┘  │           │  └──────────┘  │           │  └──────────┘  │
        │                │           │                │           │                │
        └────────────────┘           └────────────────┘           └────────────────┘
```
```text
FDB LOCAL entries in docker0 (br0):

  AA:AA:AA:AA:AA:00   LOCAL   dst = NULL     ← br0's MAC
  BB:BB:BB:BB:BB:01   LOCAL   dst = port0    ← veth-host0's MAC
  BB:BB:BB:BB:BB:02   LOCAL   dst = port1    ← veth-host1's MAC
  BB:BB:BB:BB:BB:03   LOCAL   dst = port2    ← veth-host2's MAC
```

All four are LOCAL; the only difference is `dst` (bridge device MAC → `dst = NULL`; port MAC → `dst` = that port). The behavior is identical: for a frame addressed to any of them, the same code at `br_input.c:222` fires:

```c
if (test_bit(BR_FDB_LOCAL, &dst->flags))
    return br_pass_frame_up(skb, false);  // deliver to host stack
```

It doesn't matter whether `dst` is NULL or a port — the `BR_FDB_LOCAL` check happens first and short-circuits to `br_pass_frame_up()` before `dst->dst` is dereferenced.

So if Container 2 sent a frame to `BB:BB:BB:BB:BB:01` (veth-host0's MAC), it would go up to the host stack, not out veth-host0 (technically not reachable in practice, since veth-host0 has no IP for ARP to resolve). **Any frame destined for any local MAC (br0 or any port) is delivered up, never forwarded out.**

```text
                     HOST MACHINE
            ┌─────────────────────────────┐
            │                             │
            │    Host Network Stack       │  ← owns ALL of these:
            │                             │
            │    br0    MAC: AA:AA:00     │  ← host's interface
            │    veth-host0 MAC: BB:BB:01 │  ← host's interface
            │    veth-host1 MAC: BB:BB:02 │  ← host's interface
            │    veth-host2 MAC: BB:BB:03 │  ← host's interface
            │                             │
            └─────┬──────┬──────┬─────────┘
                  │      │      │
 ═════════════════╪══════╪══════╪═══════ netns boundary
                  │      │      │
            Container1  C2     C3
            MAC: CC:01  CC:02  CC:03      ← NOT the host's
```

All the `veth-host*` interfaces are **host** interfaces: they live in the host's network namespace, and the host owns them just as much as it owns `br0`.

## Bridge ports and data structures

A bridge port is a network interface attached to a bridge, represented in the kernel by `struct net_bridge_port`.

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│   BEFORE adding to bridge:                                              │
│                                                                         │
│      eth0 ──── Just a regular network interface                         │
│                (struct net_device)                                      │
│                                                                         │
│   AFTER adding to bridge:                                               │
│                                                                         │
│      eth0 ──── Still a net_device                                       │
│         │                                                               │
│         └───► Plus a "bridge port" wrapper                              │
│               (struct net_bridge_port)                                  │
│               that connects it to the bridge                            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

```c
// net/bridge/br_private.h

struct net_bridge_port {
    struct net_bridge        *br;          // Which bridge I belong to
    struct net_device        *dev;         // The actual interface (eth0)
    struct list_head         list;         // Link in bridge's port list
    
    unsigned long            flags;        // Port flags
    
    // STP (Spanning Tree Protocol) state
    u8                       state;        // DISABLED, LISTENING, LEARNING, 
                                           // FORWARDING, BLOCKING
    u8                       priority;     // Port priority
    u32                      port_no;      // Port number
    
    // ... many more fields
};
```

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                           struct net_bridge (br0)                          │
│                                                                            │
│   port_list ───► ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│                  │ bridge_port │───►│ bridge_port │───►│ bridge_port │     │
│                  │   (eth0)    │    │   (eth1)    │    │   (veth0)   │     │
│                  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘     │
│                         │                  │                  │            │
│                         ▼                  ▼                  ▼            │
│                  ┌────────────┐     ┌────────────┐     ┌────────────┐      │
│                  │ net_device │     │ net_device │     │ net_device │      │
│                  │   eth0     │     │   eth1     │     │   veth0    │      │
│                  └────────────┘     └────────────┘     └────────────┘      │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

This command creates a bridge port:

```sh
ip link set eth0 master br0
```

```c
// In kernel: net/bridge/br_if.c

int br_add_if(struct net_bridge *br, struct net_device *dev)
{
    struct net_bridge_port *p;
    
    // Create the bridge port structure
    p = new_nbp(br, dev);
    
    // Register rx_handler with port as data
    netdev_rx_handler_register(dev, br_get_rx_handler(dev), p);
    //                                                      ▲
    //                                   port pointer passed here
    
    // Add to bridge's port list
    list_add_rcu(&p->list, &br->port_list);
    
    // ...
}
```

```sh
$ bridge link show
3: eth0: <BROADCAST,MULTICAST,UP> mtu 1500 master br0 state forwarding priority 32 cost 100
4: eth1: <BROADCAST,MULTICAST,UP> mtu 1500 master br0 state forwarding priority 32 cost 100
5: veth0: <BROADCAST,MULTICAST,UP> mtu 1500 master br0 state forwarding priority 32 cost 100
# Or with port numbers explicitly:
$ cat /sys/class/net/br0/brif/eth0/port_no
1
$ cat /sys/class/net/br0/brif/eth1/port_no
2
$ cat /sys/class/net/br0/brif/veth0/port_no
3

```

```c
struct net_bridge_fdb_entry {
    struct net_bridge_fdb_key    key;     // The MAC address
    struct net_bridge_port       *dst;    // ← Points to a BRIDGE PORT
    unsigned long                flags;   // BR_FDB_LOCAL, etc.
};
```

```text
┌──────────────────────┬────────────────────┬───────────────┬───────────────┐
│ MAC Address          │ dst points to      │ BR_FDB_LOCAL  │ Meaning       │
├──────────────────────┼────────────────────┼───────────────┼───────────────┤
│ aa:bb:cc:dd:ee:00    │ NULL               │ YES           │ br0's MAC     │
│ (br0's MAC)          │                    │               │ → to host     │
├──────────────────────┼────────────────────┼───────────────┼───────────────┤
│ 52:54:00:11:11:11    │ Port 1 (eth0)      │ YES           │ eth0's MAC    │
│ (eth0's MAC)         │                    │               │ → to host     │
├──────────────────────┼────────────────────┼───────────────┼───────────────┤
│ fe:00:00:00:00:02    │ Port 2 (veth0)     │ NO            │ Container MAC │
│ (container's MAC)    │                    │               │ → forward out │
│                      │                    │               │   veth0       │
├──────────────────────┼────────────────────┼───────────────┼───────────────┤
│ 11:22:33:44:55:66    │ Port 1 (eth0)      │ NO            │ Learned MAC   │
│ (external device)    │                    │               │ → forward out │
│                      │                    │               │   eth0        │
└──────────────────────┴────────────────────┴───────────────┴───────────────┘
```

```c
// Forward a packet
void br_forward(struct net_bridge_port *to, struct sk_buff *skb, ...)
{
    // "to" is a pointer to the port
    
    skb->dev = to->dev;    // Get the net_device directly from port
    //         ▲
    //         └── No lookup needed! Direct pointer access
    
    dev_queue_xmit(skb);   // Send on that device
}
```

### `struct net_bridge`

```c
struct net_bridge {
    // The network device representing this bridge
    struct net_device       *dev;           // br0 as a net_device
    
    // List of all ports attached to this bridge
    struct list_head        port_list;      // eth0, eth1, veth0, etc.
    
    // The MAC address table (FDB)
    struct rhashtable       fdb_hash_tbl;   // MAC → port mappings
    struct hlist_head       fdb_list;       // List of all FDB entries
    
    // STP (Spanning Tree Protocol)
    bridge_id               bridge_id;      // This bridge's ID
    bridge_id               designated_root;// Root bridge ID
    u16                     root_port;      // Port to root bridge
    unsigned long           max_age;        // STP timers
    unsigned long           hello_time;
    unsigned long           forward_delay;
    u8                      stp_enabled;    // BR_NO_STP, BR_KERNEL_STP...
    
    // Timers
    struct timer_list       hello_timer;
    struct timer_list       tcn_timer;
    struct delayed_work     gc_work;        // FDB garbage collection
    
    // Settings
    unsigned long           ageing_time;    // How long FDB entries live
    unsigned long           options;        // Bridge options/flags
    
    // ... and more
};
```

### The FDB is per-bridge

Each bridge has its own FDB — completely separate:

```text
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│   ┌────────────────────────────┐    ┌────────────────────────────┐       │
│   │       net_bridge (br0)     │    │       net_bridge (br1)     │       │
│   │                            │    │                            │       │
│   │  fdb_hash_tbl ──────┐      │    │  fdb_hash_tbl ──────┐      │       │
│   │                     │      │    │                     │      │       │
│   │   ┌─────────────────▼──┐   │    │   ┌─────────────────▼──┐   │       │
│   │   │     FDB for br0    │   │    │   │     FDB for br1    │   │       │
│   │   │                    │   │    │   │                    │   │       │
│   │   │ aa:bb:cc:01 → eth0 │   │    │   │ dd:ee:ff:01 → eth2 │   │       │
│   │   │ aa:bb:cc:02 → eth1 │   │    │   │ dd:ee:ff:02 → eth3 │   │       │
│   │   │ aa:bb:cc:03 → veth0│   │    │   │ dd:ee:ff:03 → veth1│   │       │
│   │   └────────────────────┘   │    │   └────────────────────┘   │       │
│   │                            │    │                            │       │
│   │  port_list:                │    │  port_list:                │       │
│   │    eth0, eth1, veth0       │    │    eth2, eth3, veth1       │       │
│   │                            │    │                            │       │
│   └────────────────────────────┘    └────────────────────────────┘       │
│                                                                          │
│   Completely separate! No sharing between bridges.                       │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│  Different bridges are different Layer 2 domains.                       │
│                                                                         │
│  The same MAC address could exist on both:                              │
│                                                                         │
│    br0:  aa:bb:cc:dd:ee:ff → eth0    (Device A on network 1)            │
│    br1:  aa:bb:cc:dd:ee:ff → eth2    (Device B on network 2)            │
│                                                                         │
│  They're separate networks, so separate tables.                         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

```c
// FDB lookup is always scoped to a specific bridge
dst = br_fdb_find_rcu(br, mac, vid);
//                    ▲
//                    └── Which bridge to search in
```

### `port_list`

A circular doubly linked list of all ports attached to a bridge:

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                                                                            │
│   net_bridge (br0)                                                         │
│   ┌─────────────────┐                                                      │
│   │  port_list      │                                                      │
│   │  (list_head)    │                                                      │
│   │   next ─────────────┐                                                  │
│   │   prev ───────────┐ │                                                  │
│   └─────────────────┘ │ │                                                  │
│                       │ │                                                  │
│        ┌──────────────┘ │                                                  │
│        │ ┌──────────────┘                                                  │
│        │ │                                                                 │
│        ▼ ▼                                                                 │
│   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐                  │
│   │ port (eth0) │     │ port (eth1) │     │port (veth0) │                  │
│   │             │     │             │     │             │                  │
│   │ list.next ──────► │ list.next ──────► │ list.next ──────► (back to     │
│   │ list.prev ◄────── │ list.prev ◄────── │ list.prev ◄──     port_list)   │
│   │             │     │             │     │             │                  │
│   │ dev → eth0  │     │ dev → eth1  │     │ dev → veth0 │                  │
│   └─────────────┘     └─────────────┘     └─────────────┘                  │
│                                                                            │
│   It's a circular doubly-linked list                                       │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```
```c
// include/linux/types.h
struct list_head {
    struct list_head *next;
    struct list_head *prev;
};
```

## Walkthroughs

### Container → host (packet to `br0`'s IP)

```text
┌────────────────────────────────────────────────────────────────────┐
│                           HOST                                     │
│                                                                    │
│   ┌─────────────────────────────────────────────────────────────┐  │
│   │                    TCP/IP Stack                             │  │
│   │                  (listening on br0)                         │  │
│   │                   IP: 192.168.1.1                           │  │
│   └──────────────────────────┬──────────────────────────────────┘  │
│                              │                                     │
│   ┌──────────────────────────▼──────────────────────────────────┐  │
│   │                    br0 (bridge)                             │  │
│   │              MAC: aa:bb:cc:dd:ee:00                         │  │
│   │               IP: 192.168.1.1/24                            │  │
│   │                                                             │  │
│   │     ┌─────────────┐              ┌─────────────┐            │  │
│   │     │   veth-br   │              │   eth0      │            │  │
│   │     │  (enslaved) │              │  (enslaved) │            │  │
│   └─────┴──────┬──────┴──────────────┴─────────────┴────────────┘  │
│                │                                                   │
│         veth pair                                                  │
│                │                                                   │
└────────────────┼───────────────────────────────────────────────────┘
                 │
    ┌────────────▼────────────┐
    │       CONTAINER         │
    │                         │
    │   ┌─────────────────┐   │
    │   │     veth0       │   │
    │   │  192.168.1.10   │   │
    │   └────────┬────────┘   │
    │            │            │
    │   App sends packet to   │
    │   192.168.1.1 (br0 IP)  │
    │                         │
    └─────────────────────────┘
```
```text
┌──────────────────────────────────────────────────────────────────────────────┐
│  STEP 1: Container's App Sends Packet                                        │
│                                                                              │
│  Container process: send(sock, data, ...) to 192.168.1.1                     │
│                              │                                               │
│                              ▼                                               │
│  Container's IP stack builds packet:                                         │
│    • src IP: 192.168.1.10 (container)                                        │
│    • dst IP: 192.168.1.1  (bridge)                                           │
│    • ARP lookup → dst MAC: aa:bb:cc:dd:ee:00 (br0's MAC)                     │
│    • src MAC: container's veth0 MAC                                          │
│                              │                                               │
│                              ▼                                               │
│  dev_queue_xmit(skb) on veth0                                                │
└──────────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  STEP 2: Veth Pair Magic                                                     │
│                                                                              │
│  veth0 (container) ←──paired──→ veth-br (host)                               │
│                                                                              │
│  Veth driver directly injects packet to peer:                                │
│    skb->dev = veth-br;     // Appears on host side                           │
│    netif_receive_skb(skb); // Enter host's network stack                     │
└──────────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  STEP 3: Bridge rx_handler Intercepts                                        │
│                                                                              │
│  netif_receive_skb() on host:                                                │
│    skb->dev = veth-br                                                        │
│                              │                                               │
│                              ▼                                               │
│  __netif_receive_skb_core():                                                 │
│    rx_handler = veth-br->rx_handler;  // = br_handle_frame (enslaved!)       │
│    rx_handler(&skb);                                                         │
│                              │                                               │
│                              ▼                                               │
│  br_handle_frame():                                                          │
│    • Validates frame                                                         │
│    • Passes through NF_BR_PRE_ROUTING                                        │
└──────────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  STEP 4: Bridge FDB Lookup                                                   │
│                                                                              │
│  br_handle_frame_finish():                                                   │
│                                                                              │
│    // Learn source MAC                                                       │
│    br_fdb_update(br, veth-br, src_mac, ...);                                 │
│                                                                              │
│    // Lookup destination MAC (aa:bb:cc:dd:ee:00)                             │
│    dst = br_fdb_find_rcu(br, dst_mac, vid);                                  │
│                              │                                               │
│                              ▼                                               │
│    ┌─────────────────────────────────────────────────────────────────┐       │
│    │  FDB Entry Found:                                               │       │
│    │    MAC: aa:bb:cc:dd:ee:00                                       │       │
│    │    Flags: BR_FDB_LOCAL  ◄── THIS IS THE KEY!                    │       │
│    │                                                                 │       │
│    │  BR_FDB_LOCAL means: "This is the bridge's own MAC"             │       │
│    └─────────────────────────────────────────────────────────────────┘       │
│                              │                                               │
│    if (test_bit(BR_FDB_LOCAL, &dst->flags))                                  │
│        return br_pass_frame_up(skb, false);  ◄── GO TO HOST!                 │
└──────────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  STEP 5: Pass Frame Up to Host Stack                                         │
│                                                                              │
│  br_pass_frame_up():                                                         │
│                                                                              │
│    skb->dev = br0;   // ◄── CRITICAL: Change device to bridge                │
│                                                                              │
│    NF_HOOK(NF_BR_LOCAL_IN, ..., br_netif_receive_skb);                       │
│                              │                                               │
│                              ▼                                               │
│  br_netif_receive_skb():                                                     │
│    return netif_receive_skb(skb);  // Re-enter with dev=br0                  │
└──────────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  STEP 6: Normal IP Processing                                                │
│                                                                              │
│  netif_receive_skb() — now skb->dev = br0:                                   │
│    • br0 has NO rx_handler → continues normally                              │
│    • skb->protocol = ETH_P_IP (0x0800)                                       │
│                              │                                               │
│                              ▼                                               │
│  ip_rcv():                                                                   │
│    • dev = br0                                                               │
│    • Validates IP header                                                     │
│                              │                                               │
│                              ▼                                               │
│  ip_rcv_finish_core():                                                       │
│    ip_route_input_noref(skb, dst=192.168.1.1, ...)                           │
│                              │                                               │
│    ┌─────────────────────────▼─────────────────────────────────────────┐     │
│    │  Routing Decision:                                                │     │
│    │                                                                   │     │
│    │  "Is 192.168.1.1 configured on br0?"                              │     │
│    │      → YES!                                                       │     │
│    │      → rt->rt_type = RTN_LOCAL                                    │     │
│    │      → rt->dst.input = ip_local_deliver                           │     │
│    └───────────────────────────────────────────────────────────────────┘     │
│                              │                                               │
│                              ▼                                               │
│  dst_input() → ip_local_deliver()                                            │
└──────────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  STEP 7: Deliver to Application                                              │
│                                                                              │
│  ip_local_deliver_finish():                                                  │
│    • Strip IP header                                                         │
│    • Look up protocol (TCP=6, UDP=17)                                        │ 
│                              │                                               │
│                              ▼                                               │
│  tcp_v4_rcv() or udp_rcv():                                                  │
│    • Find socket listening on 192.168.1.1                                    │
│    • Deliver to application                                                  │
│                              │                                               │
│                              ▼                                               │
│  ┌───────────────────────────────────────────────────────────────────────┐   │
│  │  Application on host receives packet from container!                  │   │
│  │  e.g., SSH server, web server, database                               │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Container → container

```text
  Container 2 pings Container 1
  src MAC = CC:CC:02   dst MAC = CC:CC:01

  FDB lookup for CC:CC:01 → DYNAMIC, dst = port0

  BR_FDB_LOCAL is NOT set → normal forwarding path:
      br_forward(dst->dst, skb, ...)  → send out port0 (veth-host0)

                          ┌──────────────────────────────┐
                          │        docker0  (br0)        │
                          │                              │
                          │  lookup CC:CC:01             │
                          │  → DYNAMIC, dst=port0        │
                          │  → br_forward() out port0    │
                          │                              │
                          └──┬────────────────────┬──────┘
                             │                    │
                         port0               port1
                       veth-host0           veth-host1
                             │                    │
                    ═════════╪════════════════════╪═══════
                             │                    │
                          eth0                 eth0
                        CC:CC:01              CC:CC:02
                             │                    │
                      ┌──────┴───────┐     ┌──────┴───────┐
                      │ Container 1  │     │ Container 2  │
                      │  ◄── ping    │     │  ping ──►    │
                      └──────────────┘     └──────────────┘

  The frame goes:  C2 eth0 → veth-host1 → br0 → veth-host0 → C1 eth0
                                           │
                                      pure L2 forward
                                    host stack NOT involved
```

### Frame arriving from the physical network

```text
 PHYSICAL NETWORK
 ════════════════
        │
        │ electrical/optical signal arrives on wire
        │
  ┌─────┴──────────┐
  │  NIC hardware  │  DMA into ring buffer → IRQ/NAPI
  └─────┬──────────┘
        │
        │ skb created, skb->dev = eth0
        │
 ━━━━━━━┿━━━━━━━━━━━━ KERNEL ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        │
  ┌─────┴──────────────────────────────────────────────┐
  │  netif_receive_skb()                               │
  │                                                    │
  │  Normal path: deliver to protocol handlers (IP)    │
  │  BUT eth0 is enslaved — it has an rx_handler!      │
  │                                                    │
  │  eth0->rx_handler == br_handle_frame               │  ← registered at
  │  (set when eth0 was added to bridge)               │    br_if.c:613
  │                                                    │
  │  → call rx_handler(skb) instead of normal delivery │
  └─────┬──────────────────────────────────────────────┘
        │
        │  INTERCEPTED — eth0 never delivers to IP stack directly
        │
  ┌─────┴──────────────────────────────────────────────┐
  │  br_handle_frame()                br_input.c:339   │
  │                                                    │
  │  p = br_port_get_rcu(skb->dev)  → gets port struct │
  │  Basic checks (valid MAC, STP state, etc.)         │
  │  → calls nf_hook_bridge_pre()                      │
  │      → br_handle_frame_finish()                    │
  └─────┬──────────────────────────────────────────────┘
        │
  ┌─────┴──────────────────────────────────────────────┐
  │  br_handle_frame_finish()          br_input.c:77   │
  │                                                    │
  │  1. Learn source MAC (add CC:CC:01 → port entry)   │
  │  2. Lookup dst MAC in FDB                          │
  │  3. Decision:                                      │
  │     LOCAL?    → br_pass_frame_up() → host stack    │
  │     DYNAMIC?  → br_forward() → out another port    │
  │     NOT FOUND?→ br_flood() → all ports             │
  └────────────────────────────────────────────────────┘
```

## Appendix: full source of `br_pass_frame_up()` and `br_handle_frame_finish()`

<details>
<summary><code>net/bridge/br_input.c</code></summary>

```c
static int br_pass_frame_up(struct sk_buff *skb, bool promisc)
{
	struct net_device *indev, *brdev = BR_INPUT_SKB_CB(skb)->brdev;
	struct net_bridge *br = netdev_priv(brdev);
	struct net_bridge_vlan_group *vg;

	dev_sw_netstats_rx_add(brdev, skb->len);

	vg = br_vlan_group_rcu(br);

	/* Reset the offload_fwd_mark because there could be a stacked
	 * bridge above, and it should not think this bridge it doing
	 * that bridge's work forwarding out its ports.
	 */
	br_switchdev_frame_unmark(skb);

	/* Bridge is just like any other port.  Make sure the
	 * packet is allowed except in promisc mode when someone
	 * may be running packet capture.
	 */
	if (!(brdev->flags & IFF_PROMISC) &&
	    !br_allowed_egress(vg, skb)) {
		kfree_skb(skb);
		return NET_RX_DROP;
	}

	indev = skb->dev;
	skb->dev = brdev;
	skb = br_handle_vlan(br, NULL, vg, skb);
	if (!skb)
		return NET_RX_DROP;
	/* update the multicast stats if the packet is IGMP/MLD */
	br_multicast_count(br, NULL, skb, br_multicast_igmp_type(skb),
			   BR_MCAST_DIR_TX);

	BR_INPUT_SKB_CB(skb)->promisc = promisc;

	return NF_HOOK(NFPROTO_BRIDGE, NF_BR_LOCAL_IN,
		       dev_net(indev), NULL, skb, indev, NULL,
		       br_netif_receive_skb);
}

/* note: already called with rcu_read_lock */
int br_handle_frame_finish(struct net *net, struct sock *sk, struct sk_buff *skb)
{
	enum skb_drop_reason reason = SKB_DROP_REASON_NOT_SPECIFIED;
	struct net_bridge_port *p = br_port_get_rcu(skb->dev);
	enum br_pkt_type pkt_type = BR_PKT_UNICAST;
	struct net_bridge_fdb_entry *dst = NULL;
	struct net_bridge_mcast_port *pmctx;
	struct net_bridge_mdb_entry *mdst;
	bool local_rcv, mcast_hit = false;
	struct net_bridge_mcast *brmctx;
	struct net_bridge_vlan *vlan;
	struct net_bridge *br;
	bool promisc;
	u16 vid = 0;
	u8 state;

	if (!p)
		goto drop;

	br = p->br;

	if (br_mst_is_enabled(p)) {
		state = BR_STATE_FORWARDING;
	} else {
		if (p->state == BR_STATE_DISABLED) {
			reason = SKB_DROP_REASON_BRIDGE_INGRESS_STP_STATE;
			goto drop;
		}

		state = p->state;
	}

	brmctx = &p->br->multicast_ctx;
	pmctx = &p->multicast_ctx;
	if (!br_allowed_ingress(p->br, nbp_vlan_group_rcu(p), skb, &vid,
				&state, &vlan))
		goto out;

	if (p->flags & BR_PORT_LOCKED) {
		struct net_bridge_fdb_entry *fdb_src =
			br_fdb_find_rcu(br, eth_hdr(skb)->h_source, vid);

		if (!fdb_src) {
			/* FDB miss. Create locked FDB entry if MAB is enabled
			 * and drop the packet.
			 */
			if (p->flags & BR_PORT_MAB)
				br_fdb_update(br, p, eth_hdr(skb)->h_source,
					      vid, BIT(BR_FDB_LOCKED));
			goto drop;
		} else if (READ_ONCE(fdb_src->dst) != p ||
			   test_bit(BR_FDB_LOCAL, &fdb_src->flags)) {
			/* FDB mismatch. Drop the packet without roaming. */
			goto drop;
		} else if (test_bit(BR_FDB_LOCKED, &fdb_src->flags)) {
			/* FDB match, but entry is locked. Refresh it and drop
			 * the packet.
			 */
			br_fdb_update(br, p, eth_hdr(skb)->h_source, vid,
				      BIT(BR_FDB_LOCKED));
			goto drop;
		}
	}

	nbp_switchdev_frame_mark(p, skb);

	/* insert into forwarding database after filtering to avoid spoofing */
	if (p->flags & BR_LEARNING)
		br_fdb_update(br, p, eth_hdr(skb)->h_source, vid, 0);

	promisc = !!(br->dev->flags & IFF_PROMISC);
	local_rcv = promisc;

	if (is_multicast_ether_addr(eth_hdr(skb)->h_dest)) {
		/* by definition the broadcast is also a multicast address */
		if (is_broadcast_ether_addr(eth_hdr(skb)->h_dest)) {
			pkt_type = BR_PKT_BROADCAST;
			local_rcv = true;
		} else {
			pkt_type = BR_PKT_MULTICAST;
			if (br_multicast_rcv(&brmctx, &pmctx, vlan, skb, vid))
				goto drop;
		}
	}

	if (state == BR_STATE_LEARNING) {
		reason = SKB_DROP_REASON_BRIDGE_INGRESS_STP_STATE;
		goto drop;
	}

	BR_INPUT_SKB_CB(skb)->brdev = br->dev;
	BR_INPUT_SKB_CB(skb)->src_port_isolated = !!(p->flags & BR_ISOLATED);

	if (IS_ENABLED(CONFIG_INET) &&
	    (skb->protocol == htons(ETH_P_ARP) ||
	     skb->protocol == htons(ETH_P_RARP))) {
		br_do_proxy_suppress_arp(skb, br, vid, p);
	} else if (IS_ENABLED(CONFIG_IPV6) &&
		   skb->protocol == htons(ETH_P_IPV6) &&
		   br_opt_get(br, BROPT_NEIGH_SUPPRESS_ENABLED) &&
		   pskb_may_pull(skb, sizeof(struct ipv6hdr) +
				 sizeof(struct nd_msg)) &&
		   ipv6_hdr(skb)->nexthdr == IPPROTO_ICMPV6) {
			struct nd_msg *msg, _msg;

			msg = br_is_nd_neigh_msg(skb, &_msg);
			if (msg)
				br_do_suppress_nd(skb, br, vid, p, msg);
	}

	switch (pkt_type) {
	case BR_PKT_MULTICAST:
		mdst = br_mdb_entry_skb_get(brmctx, skb, vid);
		if ((mdst || BR_INPUT_SKB_CB_MROUTERS_ONLY(skb)) &&
		    br_multicast_querier_exists(brmctx, eth_hdr(skb), mdst)) {
			if ((mdst && mdst->host_joined) ||
			    br_multicast_is_router(brmctx, skb) ||
			    br->dev->flags & IFF_ALLMULTI) {
				local_rcv = true;
				DEV_STATS_INC(br->dev, multicast);
			}
			mcast_hit = true;
		} else {
			local_rcv = true;
			DEV_STATS_INC(br->dev, multicast);
		}
		break;
	case BR_PKT_UNICAST:
		dst = br_fdb_find_rcu(br, eth_hdr(skb)->h_dest, vid);
		if (unlikely(!dst && vid &&
			     br_opt_get(br, BROPT_FDB_LOCAL_VLAN_0))) {
			dst = br_fdb_find_rcu(br, eth_hdr(skb)->h_dest, 0);
			if (dst &&
			    (!test_bit(BR_FDB_LOCAL, &dst->flags) ||
			     test_bit(BR_FDB_ADDED_BY_USER, &dst->flags)))
				dst = NULL;
		}
		break;
	default:
		break;
	}

	if (dst) {
		unsigned long now = jiffies;

		if (test_bit(BR_FDB_LOCAL, &dst->flags))
			return br_pass_frame_up(skb, false);

		if (now != READ_ONCE(dst->used))
			WRITE_ONCE(dst->used, now);
		br_forward(dst->dst, skb, local_rcv, false);
	} else {
		if (!mcast_hit)
			br_flood(br, skb, pkt_type, local_rcv, false, vid);
		else
			br_multicast_flood(mdst, skb, brmctx, local_rcv, false);
	}

	if (local_rcv)
		return br_pass_frame_up(skb, promisc);

out:
	return 0;
drop:
	kfree_skb_reason(skb, reason);
	goto out;
}
```

</details>
