# Ethernet

See also: [VLAN](vlan.md), [`ethernet.c`](ethernet.c).

## Frame structure

Standard frame — 64 to 1518 bytes:

```text
|<---- 8 bytes ---->|<-------------------------- 64 to 1518 bytes -------------------------->|

+-------------------+--------+--------+------+--------------------+---------+
| Preamble + SFD    | Dest.  | Source | Type |     Payload        |   FCS   |
|   (8 bytes)       |  MAC   |  MAC   |/Len  | (46–1500 bytes)    | (4 B)   |
|                   | (6 B)  | (6 B)  | (2 B)|                    |         |
+-------------------+--------+--------+------+--------------------+---------+
                   ↑                                                      ↑
                 Frame begins                                        Frame ends
```

| Field                            | Size      | Purpose                                                                                  |
| -------------------------------- | --------- | ---------------------------------------------------------------------------------------- |
| Preamble + Start Frame Delimiter | 7 + 1 B   | Synchronizes clocks between sender and receiver. **Not** counted in the 64-byte minimum. |
| Destination MAC                  | 6 B       | Hardware address of the receiving device.                                                |
| Source MAC                       | 6 B       | Hardware address of the sender.                                                          |
| Type/Length                      | 2 B       | Protocol type (**EtherType**) or payload length (IEEE 802.3).                            |
| Payload                          | 46–1500 B | The data carried. If less than 46 bytes, padding is added to reach the minimum.          |
| FCS                              | 4 B       | Frame Check Sequence (CRC) for error detection.                                          |

VLAN-tagged frame (802.1Q) — up to 1522 bytes:

```text
|<---- 8 bytes ---->|<-------------------------------- 68 to 1522 bytes -------------------------------->|
+-------------------+--------+--------+------+--------+--------------------+---------+
| Preamble + SFD    | Dest.  | Source | Type | VLAN   |     Payload        |   FCS   |
|   (8 bytes)       |  MAC   |  MAC   |/Len  | Tag    | (46–1500 bytes)    | (4 B)   |
|                   | (6 B)  | (6 B)  | (2 B)| (4 B)  |                    |         |
+-------------------+--------+--------+------+--------+--------------------+---------+
```

(Strictly, the 4-byte 802.1Q tag sits between the source MAC and the EtherType, starting with TPID `0x8100`.)

Jumbo frame (example: 9000 bytes total):

```text
+--------+--------+------+-------------------------------+---------+
| Dest.  | Source | Type |          Payload (~8972 B)    |   FCS   |
+--------+--------+------+-------------------------------+---------+
```

### Minimum frame size

| Type                       | Minimum frame size     | Notes                              |
| -------------------------- | ---------------------- | ---------------------------------- |
| Ethernet II / IEEE 802.3   | 64 bytes               | Standard wired Ethernet            |
| VLAN-tagged (802.1Q) frame | 68 bytes               | 4 bytes longer due to the VLAN tag |
| Jumbo frame (nonstandard)  | Not less than 64 bytes | Typically larger max, same minimum |

> Note: IEEE 802.1Q actually keeps the minimum at 64 bytes by allowing the payload to shrink to 42 bytes; many devices nevertheless pad tagged frames to 68.

### MTU

- Data smaller than the MTU → sent in one packet.
- Larger than the MTU → must be fragmented or split.
- This is one reason compact encodings like protobuf can beat JSON on the wire: fewer bytes, fewer packets.

From the kernel's `if_ether.h` (frame sizes omit the preamble and FCS): `ETH_HLEN` 14, `ETH_ZLEN` 60, `ETH_DATA_LEN` 1500, `ETH_FRAME_LEN` 1514 — see [bridge internals](bridging.md#sk_buff-and-the-ethernet-header).

## EtherType

The EtherType field (2 bytes, 16 bits) in the Ethernet header identifies which protocol is encapsulated in the payload, so the receiver knows how to interpret it (IPv4, IPv6, ARP, VLAN, …). In the kernel, **the EtherType determines the handler used** for the frame.

| EtherType | Protocol / payload                  | Description                                  |
| --------- | ----------------------------------- | -------------------------------------------- |
| `0x0800`  | IPv4                                | Internet Protocol version 4                  |
| `0x0806`  | ARP                                 | Address Resolution Protocol                  |
| `0x0842`  | Wake-on-LAN                         | Wake-on-LAN magic packet                     |
| `0x22EA`  | Stream Reservation Protocol (SRP)   | Time-sensitive networking control            |
| `0x22F3`  | IETF TRILL                          | Transparent Interconnection of Lots of Links |
| `0x6003`  | DECnet Phase IV                     | Digital Equipment Corporation protocol       |
| `0x8035`  | RARP                                | Reverse Address Resolution Protocol          |
| `0x809B`  | AppleTalk (EtherTalk)               | Apple networking protocol                    |
| `0x80F3`  | AARP                                | AppleTalk Address Resolution Protocol        |
| `0x8100`  | VLAN-tagged frame (IEEE 802.1Q)     | VLAN tagging and priority bits               |
| `0x8137`  | IPX                                 | Novell Internetwork Packet Exchange          |
| `0x8204`  | QNX Qnet                            | QNX networking                               |
| `0x86DD`  | IPv6                                | Internet Protocol version 6                  |
| `0x8808`  | Ethernet flow control (PAUSE frame) | IEEE 802.3x flow control                     |
| `0x8809`  | Slow Protocols (LACP, Marker)       | IEEE 802.3 Slow Protocols                    |
| `0x8847`  | MPLS unicast                        | Multiprotocol Label Switching (unicast)      |
| `0x8848`  | MPLS multicast                      | Multiprotocol Label Switching (multicast)    |
| `0x8863`  | PPPoE Discovery Stage               | PPP over Ethernet discovery                  |
| `0x8864`  | PPPoE Session Stage                 | PPP over Ethernet session                    |
| `0x8870`  | Jumbo frames (nonstandard)          | Used by some vendors to mark jumbo frames    |
| `0x887B`  | HomePlug 1.0 MME                    | Powerline networking                         |
| `0x888E`  | EAP over LAN (802.1X)               | Network access authentication                |
| `0x88A8`  | Q-in-Q (802.1ad)                    | Provider bridging (stacked VLANs)            |
| `0x88CC`  | LLDP                                | Link Layer Discovery Protocol                |
| `0x88E5`  | MACsec                              | IEEE 802.1AE security protocol               |
| `0x88F7`  | Precision Time Protocol (PTP)       | IEEE 1588 timing sync                        |
| `0x8906`  | Fibre Channel over Ethernet (FCoE)  | Storage networking                           |
| `0x8915`  | RoCE                                | RDMA over Converged Ethernet                 |
| `0x9100`  | VLAN-tagged (Q-in-Q)                | Double-tagged VLAN frame                     |

## Physical encoding

The bits in a frame are encoded into voltage or light-level changes depending on the medium. The scheme depends on the Ethernet standard:

| Standard   | Bit rate | Encoding      | Description                                                                                                 |
| ---------- | -------- | ------------- | ----------------------------------------------------------------------------------------------------------- |
| 10BASE-T   | 10 Mbps  | Manchester    | Each bit is a voltage transition mid-bit: 0 = high→low, 1 = low→high.                                       |
| 100BASE-TX | 100 Mbps | 4B/5B + MLT-3 | 4 bits mapped to 5-bit codes (enough transitions), sent with Multi-Level Transmission-3 (3 voltage levels). |
| 1000BASE-T | 1 Gbps   | PAM-5         | Pulse Amplitude Modulation with 5 levels; each symbol carries 2 bits, across 4 wire pairs simultaneously.   |
| 10GBASE-T  | 10 Gbps  | PAM-16        | 16 amplitude levels; advanced DSP to handle noise and crosstalk.                                            |

- [Intro to fiber optics and RF encoding (video)](https://www.youtube.com/watch?v=lUo45NqPyq8&list=PLowKtXNTBypH19whXTVoG3oKSuOcw_XeW&index=2)
