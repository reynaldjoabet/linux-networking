# TCP

TCP turns unreliable, independent IP packet delivery into a reliable, ordered stream of bytes.

## What IP gives you

IP is only a best-effort packet delivery system. It moves individual packets between hosts:

- Each packet is routed independently, can arrive out of order, can be duplicated, and can be dropped entirely.
- Packet A and Packet B sent from the same source to the same destination might take completely different physical paths across the globe.
- The routers in the middle (the core of the Internet) maintain almost zero state about the sender or receiver; they just look at the destination IP address and forward the packet to the next hop.

IP does not give the application a stable conversation — only a way to send datagrams from one address to another.

## What TCP adds

TCP is a transport-layer protocol that creates the abstraction of a reliable connection on top of IP:

- A **connection** between a source IP and port and a destination IP and port. Source and destination ports are each 16 bits: 2¹⁶ = 65,536 values, so the maximum port is 65,535.
- A **byte stream** rather than a message-oriented protocol. The application writes bytes, and TCP decides how to segment them into packets.
- **Sequence numbers** so the receiver can reorder segments and detect missing data.
- **Acknowledgments** so the sender knows what data has been received.
- **Retransmission** when data appears to have been lost.
- **Flow control** so a fast sender does not overwhelm a slow receiver.
- **Congestion control** so endpoints reduce sending pressure when the network is overloaded.

The data path:

1. Your application writes bytes.
2. TCP splits those bytes into segments.
3. IP carries those segments as packets.
4. The receiver's TCP stack reassembles them into the original byte stream.

This is why TCP feels like a long-lived session even though the underlying network is still just forwarding individual IP packets. The connection may stay open for minutes or hours, while the packets that carry its data exist only momentarily as they move through routers, queues, and network interfaces.

## How it works

### Connection state machine

TCP is connection-oriented. Before application data is exchanged, both sides synchronize state during the **three-way handshake** (`SYN` → `SYN-ACK` → `ACK`). The handshake:

- establishes initial sequence numbers,
- confirms that both endpoints are reachable and ready,
- allocates memory (receive/send buffers) and initializes state variables on both sides.

When the connection is no longer needed, TCP tears it down explicitly with `FIN` and `ACK` exchanges, or abruptly with `RST` if something goes wrong.

### Sequence and acknowledgement (ordering)

TCP assigns a sequence number to every byte it sends. If you send 1,000 bytes, the IP packet payload (a **TCP segment**) contains those bytes and a header indicating they are bytes `X` through `X + 999`.

The receiver sends an ACK acknowledging exactly which bytes it has received. If bytes arrive out of order, the receiver buffers them in memory and waits for the missing pieces.

### Retransmission (reliability)

The sender maintains a timer based on the estimated round-trip time (RTT). If an ACK is not received before the timer expires, the sender assumes the packet was dropped and retransmits it.

### Byte stream, not messages

TCP provides a stream of bytes, not preserved application messages. If an application writes 10 bytes and then 20 bytes, the receiver may read them back as 30 bytes at once, or split across several reads. Message boundaries are an application-level concern, not a TCP feature.

### TLS on top of TCP

TLS relies on TCP to provide ordered, reliable delivery, then adds its own handshake, authentication, encryption, and integrity protection. TCP is responsible for transporting bytes correctly; TLS is responsible for making those bytes confidential and tamper-evident.

## Sockets

- The client creates a socket and initiates a connection to the server's IP and port; with TCP, this starts the three-way handshake.
- Once connected, the client writes to and reads from the socket just like a file.
- Every socket is uniquely identified by a **5-tuple**:
  - socket type: TCP or UDP
  - local IP address
  - local port
  - peer's IP address (optional for UDP)
  - peer's port (optional for UDP)
- One port ≠ one socket: a single application (e.g. a web server on port 443) can handle thousands of sockets simultaneously. See also [Kafka: one process, many ports](kafka.md#one-process-many-ports).
- "Socket" usually means network communication (internet sockets), but **Unix domain sockets** connect processes on the same machine — much faster, since data isn't wrapped in network protocols.

## HTTP over TCP: 1.1 → 2 → 3

### HTTP/1.1

If a browser needs 10 JS/CSS files, it must request them sequentially on a single TCP connection. If the first asset takes 2 seconds to generate on the server, the other 9 requests are blocked waiting in line (**head-of-line blocking at the application layer**).

**The workaround:** browsers open multiple parallel TCP connections to a single server (usually capped at 6). This is inefficient: each connection requires its own TCP three-way handshake and TLS handshake, and opening many TCP connections at once causes network congestion and circumvents TCP's deliberate slow-start phase.

### HTTP/2

HTTP/2 kept the same semantics as HTTP/1.1 (methods, status codes, URIs) but completely rewrote how data is framed and transported over TCP.

- **The paradigm:** a binary framing layer. Text is converted into binary frames (e.g. `HEADERS` frames, `DATA` frames).
- **The fix — stream multiplexing:** all requests go over a single TCP connection. Each binary frame carries a stream ID. The browser can send 100 requests simultaneously; the server chops up the responses and interleaves the chunks; the browser reassembles them by stream ID. This eliminates application-layer HoL blocking.
- **Stateful header compression (HPACK):** client and server maintain a stateful dictionary of headers. If a cookie is sent once, subsequent requests just send an index pointer to it, massively reducing payload size.
- **The flaw — TCP-layer HoL blocking:** HTTP/2 puts all its eggs in one TCP basket. TCP guarantees strict, ordered delivery, so if a single packet containing a chunk of stream 5 is dropped, TCP halts the entire byte stream until it is retransmitted. Even if the packets for streams 6, 7 and 8 arrived perfectly, the kernel withholds them in the TCP receive buffer because it must deliver an ordered byte stream to the application.

### HTTP/3: QUIC (2022)

To fix TCP-layer HoL blocking, TCP has to go. HTTP/3 runs on a new transport protocol, **QUIC**, built on top of UDP.

- **The paradigm:** UDP is connectionless and happy to deliver out-of-order packets. QUIC is implemented in user space and builds the reliability, congestion control and ordering of TCP on top of UDP — but it is aware of HTTP streams.
- **The fix — independent streams:** because QUIC (not TCP) controls the streams, a dropped UDP packet belonging to stream 5 stalls only stream 5. Streams 6, 7 and 8 are delivered immediately. This eliminates transport-layer HoL blocking.
- **Baked-in TLS 1.3 (0-RTT):** with HTTP/2 you do a TCP handshake (1 RTT), then a TLS handshake (1–2 RTT) before sending data. QUIC bakes TLS 1.3 into the transport, so the cryptographic and transport handshakes happen together. If you've visited a server before, HTTP/3 can achieve 0-RTT connection establishment, sending encrypted HTTP requests in the very first UDP packet.
- **Connection migration:** TCP connections are identified by a 4-tuple (source IP, source port, dest IP, dest port). Switch from Wi-Fi to 5G and your IP changes: the TCP connection dies and HTTP/2 must start over. QUIC identifies connections by a cryptographic **connection ID**, so you can switch networks and the HTTP/3 connection continues without interrupting downloads.
- **Header compression (QPACK):** HPACK wouldn't work over QUIC because out-of-order delivery could desync the header dictionaries. QPACK is designed to tolerate out-of-order delivery without breaking compression state.
