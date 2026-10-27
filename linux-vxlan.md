# VXLAN Overlay Between Two Linux Hosts

This lab demonstrates a VXLAN network overlay between two Linux hosts in its simplest form. This is the fundamental principle behind cluster networking.

The quickstart deployment provisions two Linux Azure VMs, each with an out-of-the-box installation. Azure Bastion is also deployed and enabled for the VMs, so you can connect to them immediately.

See also: [VXLAN](vxlan.md) for the protocol itself.

## Reference architecture

[![Deploy to Azure](https://aka.ms/deploytoazurebutton)](https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fnehalineogi%2Fazure-cross-solution-network-architectures%2Fmain%2Fadvanced-linux-networking%2Fjson%2Flinuxhost.json)

### Design components

1. Two Ubuntu Linux VMs in a subnet with an `eth0` interface (deployed via the "Deploy to Azure" button).
2. A `vxlan-demo` interface acting as the VTEP and creating the overlay (layer 2 over layer 3).
3. Packet captures showing VXLAN-encapsulated ICMP packets.

## Quickstart deployment

- The username for the deployed VMs is `localadmin`.
- The passwords are stored in a Key Vault deployed to the same resource group.

### Task 1: start the deployment

1. Open Cloud Shell and retrieve your signed-in user ID (used to grant access to Key Vault):

   ```sh
   az ad signed-in-user show --query id -o tsv
   ```

2. Click the **Deploy to Azure** button above and supply the signed-in user ID from step 1.
3. Using Azure Bastion, log in to the VMs with the username `localadmin` and the passwords from Key Vault.
4. Log in as root with `sudo su`.
5. You may have different IP addresses and interfaces than the outputs throughout this series; this is expected.

> **Note:** SSH directly to the VMs is possible, but it is best security practice not to expose VMs to the internet for SSH. Tenants managed by corporations commonly restrict direct SSH from the internet. See the [FAQ](#faq--troubleshooting).

## Lab: VXLAN between two Linux hosts

### Before: host 1 has no VXLAN interface

```console
root@linux-host-1:~# ip add sh
1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000
    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
    inet 127.0.0.1/8 scope host lo
       valid_lft forever preferred_lft forever
    inet6 ::1/128 scope host
       valid_lft forever preferred_lft forever
2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 1000
    link/ether 00:22:48:1e:1a:09 brd ff:ff:ff:ff:ff:ff
    inet 172.16.24.4/24 brd 172.16.24.255 scope global eth0
       valid_lft forever preferred_lft forever
    inet6 fe80::222:48ff:fe1e:1a09/64 scope link
       valid_lft forever preferred_lft forever
```

### Challenge 1: configure VXLAN between the two hosts

Use VXLAN port **4789**, per [RFC 7348](https://datatracker.ietf.org/doc/html/rfc7348):

> Destination Port: IANA has assigned the value 4789 for the VXLAN UDP port, and this value SHOULD be used by default as the destination UDP port. Some early implementations of VXLAN have used other values for the destination port. To enable interoperability with these implementations, the destination port SHOULD be configurable.

**Host 1:**

```console
root@linux-host-1:~# ip link add vxlan-demo type vxlan id 5001 remote 172.16.24.5 local 172.16.24.4 dev eth0 dstport 4789
root@linux-host-1:~# ip addr add 192.168.100.1/24 dev vxlan-demo
root@linux-host-1:~# ip link set up dev vxlan-demo

root@linux-host-1:~# ip add
1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000
    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
    inet 127.0.0.1/8 scope host lo
       valid_lft forever preferred_lft forever
    inet6 ::1/128 scope host
       valid_lft forever preferred_lft forever
2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 1000
    link/ether 00:22:48:1e:1a:09 brd ff:ff:ff:ff:ff:ff
    inet 172.16.24.4/24 brd 172.16.24.255 scope global eth0
       valid_lft forever preferred_lft forever
    inet6 fe80::222:48ff:fe1e:1a09/64 scope link
       valid_lft forever preferred_lft forever
3: vxlan-demo: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1450 qdisc noqueue state UNKNOWN group default qlen 1000
    link/ether 7a:1e:dd:83:9f:2f brd ff:ff:ff:ff:ff:ff
    inet 192.168.100.1/24 scope global vxlan-demo
       valid_lft forever preferred_lft forever
    inet6 fe80::781e:ddff:fe83:9f2f/64 scope link
       valid_lft forever preferred_lft forever
```

**Host 2:**

```console
root@linux-host-2:~# ip link add vxlan-demo type vxlan id 5001 remote 172.16.24.4 local 172.16.24.5 dev eth0 dstport 4789
root@linux-host-2:~# ip addr add 192.168.100.2/24 dev vxlan-demo
root@linux-host-2:~# ip link set up dev vxlan-demo

root@linux-host-2:~# ip add
1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000
    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
    inet 127.0.0.1/8 scope host lo
       valid_lft forever preferred_lft forever
    inet6 ::1/128 scope host
       valid_lft forever preferred_lft forever
2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 1000
    link/ether 00:0d:3a:8b:a6:59 brd ff:ff:ff:ff:ff:ff
    inet 172.16.24.5/24 brd 172.16.24.255 scope global eth0
       valid_lft forever preferred_lft forever
    inet6 fe80::20d:3aff:fe8b:a659/64 scope link
       valid_lft forever preferred_lft forever
3: vxlan-demo: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1450 qdisc noqueue state UNKNOWN group default qlen 1000
    link/ether 3e:7e:88:c7:e4:ef brd ff:ff:ff:ff:ff:ff
    inet 192.168.100.2/24 scope global vxlan-demo
       valid_lft forever preferred_lft forever
    inet6 fe80::3c7e:88ff:fec7:e4ef/64 scope link
       valid_lft forever preferred_lft forever
```

Note the `mtu 1450` on `vxlan-demo`: 50 bytes less than `eth0` to make room for the VXLAN encapsulation headers.

### Challenge 2: validate that VXLAN works

**On host 1 — initiate the ping:**

```console
root@linux-host-1:~# ping 192.168.100.2
PING 192.168.100.2 (192.168.100.2) 56(84) bytes of data.
64 bytes from 192.168.100.2: icmp_seq=1 ttl=64 time=1.40 ms
64 bytes from 192.168.100.2: icmp_seq=2 ttl=64 time=0.613 ms
```

**On host 2 — capture the VXLAN-encapsulated packets.** First comes the ARP exchange, then the ICMP packets — all encapsulated in VXLAN with VNI 5001:

```console
root@linux-host-2:~# tcpdump -ni eth0 port 4789
tcpdump: verbose output suppressed, use -v or -vv for full protocol decode
listening on eth0, link-type EN10MB (Ethernet), capture size 262144 bytes
18:46:53.049613 IP 172.16.24.4.51838 > 172.16.24.5.4789: VXLAN, flags [I] (0x08), vni 5001
ARP, Request who-has 192.168.100.2 tell 192.168.100.1, length 28
18:46:53.049847 IP 172.16.24.5.51362 > 172.16.24.4.4789: VXLAN, flags [I] (0x08), vni 5001
ARP, Reply 192.168.100.2 is-at 3e:7e:88:c7:e4:ef, length 28
18:46:53.050916 IP 172.16.24.4.36997 > 172.16.24.5.4789: VXLAN, flags [I] (0x08), vni 5001
IP 192.168.100.1 > 192.168.100.2: ICMP echo request, id 18662, seq 1, length 64
18:46:53.050959 IP 172.16.24.5.53459 > 172.16.24.4.4789: VXLAN, flags [I] (0x08), vni 5001
IP 192.168.100.2 > 192.168.100.1: ICMP echo reply, id 18662, seq 1, length 64
18:46:54.050780 IP 172.16.24.4.36997 > 172.16.24.5.4789: VXLAN, flags [I] (0x08), vni 5001
IP 192.168.100.1 > 192.168.100.2: ICMP echo request, id 18662, seq 2, length 64
18:46:54.050934 IP 172.16.24.5.53459 > 172.16.24.4.4789: VXLAN, flags [I] (0x08), vni 5001
IP 192.168.100.2 > 192.168.100.1: ICMP echo reply, id 18662, seq 2, length 64
18:46:55.065840 IP 172.16.24.4.36997 > 172.16.24.5.4789: VXLAN, flags [I] (0x08), vni 5001
IP 192.168.100.1 > 192.168.100.2: ICMP echo request, id 18662, seq 3, length 64
18:46:55.065933 IP 172.16.24.5.53459 > 172.16.24.4.4789: VXLAN, flags [I] (0x08), vni 5001
IP 192.168.100.2 > 192.168.100.1: ICMP echo reply, id 18662, seq 3, length 64
18:46:56.089847 IP 172.16.24.4.36997 > 172.16.24.5.4789: VXLAN, flags [I] (0x08), vni 5001
IP 192.168.100.1 > 192.168.100.2: ICMP echo request, id 18662, seq 4, length 64
18:46:56.089940 IP 172.16.24.5.53459 > 172.16.24.4.4789: VXLAN, flags [I] (0x08), vni 5001
IP 192.168.100.2 > 192.168.100.1: ICMP echo reply, id 18662, seq 4, length 64
18:46:57.090192 IP 172.16.24.4.36997 > 172.16.24.5.4789: VXLAN, flags [I] (0x08), vni 5001
IP 192.168.100.1 > 192.168.100.2: ICMP echo request, id 18662, seq 5, length 64
```

## FAQ / troubleshooting

Original FAQ: [azure-cross-solution-network-architectures](https://github.com/nehalineogi/azure-cross-solution-network-architectures/blob/main/advanced-linux-networking/linux-vxlan.md#faqtroubleshooting)

### I am unable to SSH to the hosts. What do I need to do?

The deployment includes Azure Bastion so you can connect to the VMs via the portal. This is the recommended practice.

Alternatively, the subnet hosting the VMs has a Network Security Group (NSG) called `Allow-tunnel-traffic` with a rule `allow-ssh-inbound`, set to **Deny** by default. To allow SSH directly to the hosts, edit this rule: change the source from `127.0.0.1` to your current public IP address, and set the rule from Deny to Allow. Corporate policies may restrict SSH; if you are in a corporate environment, check with them first.

Steps to enable SSH to the VMs:

1. Locate the NSG `Allow-tunnel-traffic` and amend rule `allow-ssh-inbound`: change `127.0.0.1` to your current public IP address and change the rule from Deny to Allow.
2. Retrieve the public IP address (or DNS label) for each VM.
3. Retrieve the VM passwords from Key Vault.
4. SSH to your VMs:

   ```sh
   ssh localadmin@[VM Public IP or DNS]
   ```

5. Log in as root with `sudo su`.

### I followed the steps above but still cannot log in over SSH

- Make sure you edited the NSG correctly to allow port 22: the rule needs your current public IP address and must be set to **Allow** rather than Deny.
- **VPNs:** if you use a VPN for outbound internet access, the public IP you are assigned may differ from the one used to connect on the internet (VPN services often NAT public-to-public for efficient use of their addresses). Entering your public IP on the NSG will then not work. You could open the rule to a range provided by the VPN service (e.g. `a.a.a.a/24`) — but that makes your service reachable to any other VPN customer currently assigned an IP in that range.
- **Find the connecting IP from the host side:** connect via Bastion, run this, then view `/var/log/syslog`:

  ```sh
  iptables -I INPUT -p tcp -m tcp --dport 22 -m state --state NEW -j LOG --log-level 1 --log-prefix "SSH Log"
  ```

- Finally, check that your company is not blocking or restricting port 22.

### What are the logins for the VMs?

The credentials are stored in an Azure Key Vault.

### Are the passwords cryptographically secure?

No. They are generated deterministically and should be changed on the VMs after deployment. They are auto-generated this way for convenience, to support a proof-of-concept or learning environment only — not production use.

### I cannot run the deployment — what is the ADuserID?

To give your signed-in account access to Key Vault, the deployment needs your Azure Active Directory (AAD) signed-in user **ObjectID**. You can run the Azure CLI method in Cloud Shell; the PowerShell method must be run from your own device with the Azure PowerShell module.

Azure CLI or Cloud Shell:

```sh
az ad signed-in-user show --query id -o tsv
```

> With older versions of the az CLI you may need `--query Objectid` instead of `--query id`.

Azure PowerShell:

```powershell
(Get-AzContext).Account.ExtendedProperties.HomeAccountId.Split('.')[0]
```

### Can I get the host's DNS and password, and change the NSG rule, from the CLI instead of the portal?

Yes:

**Obtain a password from Key Vault** (example for `linux-host-1` in the default resource group; change the name to match a different host):

```sh
az keyvault secret show --name "linux-host-1-admin-password" --vault-name $(az keyvault list -g linuxhost --query "[].name" -o tsv) --query "value" -o tsv
```

If this times out and you are using WSL referencing the Windows-based `az`, see [azure-cli#13573](https://github.com/Azure/azure-cli/issues/13573). Use PowerShell or Cloud Shell instead to avoid this known bug.

**Obtain the DNS label for a host's public IP:**

```sh
az network public-ip show -g linuxhost -n linux-host-1nic-pip --query "dnsSettings.fqdn" -o tsv
```

**Allow SSH inbound from a specific public IP** (replace `a.a.a.a` with your public IP):

```sh
az network nsg rule update -g linuxhost --nsg-name Allow-tunnel-traffic -n allow-ssh-inbound --access allow --source-address-prefix "a.a.a.a"
```

## Further reading

- [VXLAN (SUSE documentation)](https://documentation.suse.com/smart/virtualization-cloud/html/vxlan/index.html)
- [VXLAN (Huawei video)](https://e.huawei.com/eu/videolist/networking/dcswitch/9c26f9929118451f91900a2bc9bb6af6)
