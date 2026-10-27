# Docker

## Containers are just processes

Docker containers are just processes running on the host machine. The kernel isolates them with **namespaces** (what a process can see) and limits them with **cgroups** (what it can use).

```sh
docker run -d --name web nginx
```

What happens:

1. Docker creates a new container from the `nginx` image.
2. It launches a new process on the host: `/usr/sbin/nginx -g daemon off;`
3. The kernel:
   - gives that process its own namespace view,
   - applies cgroup limits,
   - mounts the container's filesystem,
   - connects it to a virtual network.

Confirm with `ps aux | grep nginx` — on a Linux host you'll see something like `root  2543  0.0  nginx: master process`: your container's process, running directly on the host. (On Docker Desktop for macOS the containers run inside a VM, so `ps` on the Mac only finds the `grep` itself:)

```console
$ docker run -d --name web nginx
351cba5d9589cb360a5b4d513588b56f735af4d30295643809376e924ec4984f
$ ps aux | grep nginx
username           63365   0.0  0.0 410724320   1392 s016  S+   12:21PM   0:00.00 grep nginx
```

`docker top` shows the processes as the host (VM) sees them:

```console
$ docker top web
UID                 PID                 PPID                C                   STIME               TTY                 TIME                CMD
root                636                 615                 0                   08:21               ?                   00:00:00            nginx: master process nginx -g daemon off;
statd               673                 636                 0                   08:21               ?                   00:00:00            nginx: worker process
statd               674                 636                 0                   08:21               ?                   00:00:00            nginx: worker process
statd               675                 636                 0                   08:21               ?                   00:00:00            nginx: worker process
statd               676                 636                 0                   08:21               ?                   00:00:00            nginx: worker process
statd               677                 636                 0                   08:21               ?                   00:00:00            nginx: worker process
statd               678                 636                 0                   08:21               ?                   00:00:00            nginx: worker process
statd               679                 636                 0                   08:21               ?                   00:00:00            nginx: worker process
statd               680                 636                 0                   08:21               ?                   00:00:00            nginx: worker process
```

### PID namespace

From inside a container, PID 1 looks like the first process, but it's really just another process on the host (maybe PID 5324). You can't see host processes because you're in a different PID namespace.

```sh
# Inside the container: only the container's processes
ps -ef

# On the host: all container processes, listed as normal PIDs
ps -ef | grep docker
```

### Cgroups

Cgroups control resource usage — CPU, memory, I/O, etc. — so one container can't hog all system resources.

```sh
docker run -m 256m --cpus 1 ubuntu
```

→ The kernel enforces memory and CPU limits on that container's processes via cgroups.

## Namespaces

A namespace is a Linux kernel feature that isolates a particular kind of global resource, so that a process (or group of processes) sees its own independent version of that resource.

### Network namespace

- Isolates network resources: interfaces, routing tables, firewall rules, etc.
- **Use case:** each container has its own networking environment, with a unique IP address and ports.
- **Example:** containers can run independent web servers bound to the same port (e.g. 80) without conflict.

See [Linux network namespaces](linux-namespaces.md).

### UTS namespace (UNIX Timesharing System)

- Isolates the hostname and NIS domain name.
- **Use case:** containers have their own hostnames, separate from the host and each other.
- **Example:** `hostname` inside a container returns a unique hostname.

### User namespace

- Isolates user and group ID mappings. Processes can run as root inside the namespace while being mapped to an unprivileged user on the host.
- **Use case:** security — root inside the container can't affect the host.
- **Example:** UID 0 (root) inside the container may be mapped to a non-root UID on the host.

User namespaces are created via `unshare`, `clone`, or container runtimes, and existing users are mapped into them.

```sh
unshare --user bash
```

This does **not** create a new Unix user. It creates a new user namespace — a new view of UIDs/GIDs. Inside it:

```console
$ id
uid=65534(nobody) gid=65534(nogroup)
```

Why? No UID mapping exists yet, and unmapped IDs appear as `nobody`. To map your user to root inside the namespace:

```sh
unshare --user --map-root-user bash
```

### Mount namespace

Processes don't own filesystems. Filesystems exist in the kernel; what a process has is a **view** of a filesystem — a namespace that tells it which files and directories it can see.

- By default (outside Docker, chroot, etc.), all normal processes share one global mount namespace. That's why if you `touch /tmp/abc` in one terminal, you immediately see it in another: both processes see the same `/tmp` tree.
- Linux can create different mount namespaces, so a process sees a different view of the filesystem.
- The tree starting at `/` is actually built from multiple filesystems mounted together.

Using the mount namespace, Docker attaches the container's filesystem as `/` for that process:

```console
$ docker exec -it web bash
root@351cba5d9589:/# mount | head
overlay on / type overlay (rw,relatime,lowerdir=/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/580/fs:/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/45/fs:/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/44/fs:/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/43/fs:/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/42/fs:/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/41/fs:/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/37/fs:/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/24/fs,upperdir=/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/581/fs,workdir=/var/lib/desktop-containerd/daemon/io.containerd.snapshotter.v1.overlayfs/snapshots/581/work)
proc on /proc type proc (rw,nosuid,nodev,noexec,relatime)
tmpfs on /dev type tmpfs (rw,nosuid,size=65536k,mode=755)
devpts on /dev/pts type devpts (rw,nosuid,noexec,relatime,gid=5,mode=620,ptmxmode=666)
sysfs on /sys type sysfs (ro,nosuid,nodev,noexec,relatime)
cgroup on /sys/fs/cgroup type cgroup2 (ro,nosuid,nodev,noexec,relatime)
mqueue on /dev/mqueue type mqueue (rw,nosuid,nodev,noexec,relatime)
shm on /dev/shm type tmpfs (rw,nosuid,nodev,noexec,relatime,size=65536k)
/dev/vda1 on /etc/resolv.conf type ext4 (rw,relatime,discard)
/dev/vda1 on /etc/hostname type ext4 (rw,relatime,discard)
```

**The root filesystem (OverlayFS):**

```text
overlay on / type overlay (rw,relatime,
lowerdir=/var/lib/desktop-containerd/.../snapshots/580/fs:...:/snapshots/24/fs,
upperdir=/var/lib/desktop-containerd/.../snapshots/581/fs,
workdir=/var/lib/desktop-containerd/.../snapshots/581/work)
```

| Term       | Meaning                                                                                                   |
| ---------- | --------------------------------------------------------------------------------------------------------- |
| `overlay`  | The filesystem driver type (OverlayFS).                                                                   |
| `on /`     | Mounted as the container's root `/`.                                                                      |
| `lowerdir` | Colon-separated list of read-only layers (the image layers), from the base image like Ubuntu or Python.   |
| `upperdir` | The writable layer for this specific container. Any file you create or modify goes here.                 |
| `workdir`  | Used internally by the kernel for OverlayFS operations (temporary workspace).                             |

So the `lowerdir` paths are the image's layers (stored by containerd), the `upperdir` is the container's writable layer (unique per container), and OverlayFS merges them into the single filesystem the process sees as `/`.

**Standard virtual filesystems:**

```text
proc on /proc type proc (...)
tmpfs on /dev type tmpfs (...)
devpts on /dev/pts type devpts (...)
sysfs on /sys type sysfs (...)
cgroup on /sys/fs/cgroup type cgroup2 (...)
mqueue on /dev/mqueue type mqueue (...)
shm on /dev/shm type tmpfs (...)
```

**Bind mounts from the host:**

- `/etc/resolv.conf` → the container's DNS configuration
- `/etc/hostname` → the container's hostname

```console
root@351cba5d9589:/# cat /etc/resolv.conf
# Generated by Docker Engine.
# This file can be edited; Docker Engine will not make further changes once it
# has been modified.

nameserver 192.168.65.7

# Based on host file: '/etc/resolv.conf' (legacy)
# Overrides: []
```

```console
# hostname is the container id
root@351cba5d9589:/# cat /etc/hostname
351cba5d9589
```

## Image layers

### What a layer is

A layer is created every time Docker executes a Dockerfile instruction that changes the filesystem — adds, deletes, or modifies files. It is a **read-only, immutable snapshot** of the filesystem after that instruction.

A layer is not a complete copy of the filesystem — it's a **diff** (delta): the files added, modified, or deleted compared to the layer below. Both adding and removing files produce a new layer. Think of Git commits: each commit stores only the changes since the last one; Docker layers work the same way for the filesystem.

- Each layer is stored as a compressed tar archive of filesystem changes.
- On the host, each layer is a directory containing the files created or changed by that one instruction.
- Docker uses a **union filesystem** (like OverlayFS) to stack the diffs into a single directory tree when the container runs.
- The base layer provides a root filesystem with `/bin`, `/etc`, `/usr`, `/var`, etc. — a read-only snapshot of, say, Ubuntu's filesystem.

```dockerfile
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y python3
COPY app/ /usr/src/app/
CMD ["python3", "/usr/src/app/main.py"]
```

This Dockerfile creates 4 layers:

```text
Layer 4: CMD instruction (metadata)
Layer 3: COPY app files
Layer 2: RUN apt-get install python3
Layer 1: FROM ubuntu:22.04
----------------------------
Final Image = Combined view
```

### Filesystem vs metadata layers

- `RUN`, `COPY` and `ADD` create **filesystem layers** that increase image size.
- `WORKDIR`, `ENV`, `CMD`, `ENTRYPOINT` and `LABEL` create **metadata layers** that don't significantly increase size, but still count for caching and appear in `docker history`.
- Every `RUN` creates a new layer. Chaining commands with `&&` in a single `RUN` produces one layer instead of several.

A directory created in a `RUN` is permanently part of the image:

```dockerfile
FROM ubuntu:22.04
RUN mkdir -p /app/logs
```

`WORKDIR` creates the directory automatically if it doesn't exist — no need for `RUN mkdir`:

```dockerfile
FROM ubuntu:22.04
WORKDIR /app/data
```

### Image layers vs the container layer

| Type             | Description                                                                                                  |
| ---------------- | ------------------------------------------------------------------------------------------------------------ |
| Image layers     | Read-only, stacked during the build from Dockerfile instructions (`FROM`, `RUN`, `COPY`, …). Frozen, immutable snapshots once the image is complete. |
| Container layer  | Not part of the image. Created only when a container runs: a temporary, writable overlay on top of all image layers, storing runtime changes (new/deleted files, logs, etc.). |

Because image layers are immutable, a running container that needs to write (e.g. temp files) gets a **transient read-write layer** on top of the stack. Writes go there seamlessly, but the changes are lost when the container is removed (unless you use volumes). This also lets many containers run from the same underlying image.

| Command         | Creates new layer? | Description                                                          |
| --------------- | ------------------ | -------------------------------------------------------------------- |
| `docker build`  | ✅                 | Builds an image; each relevant Dockerfile instruction adds a layer.  |
| `docker commit` | ✅                 | Saves the current state of a container as a new image layer.         |

### Stacking the layers

Layering is made possible by content-addressable storage and union filesystems:

1. After each layer is downloaded, it is extracted into its own directory on the host.
2. When you run a container, a union filesystem stacks the layers into a new, unified view, plus a directory specifically for the running container's changes.
3. When the container starts, its root directory is set to this unified directory (`chroot`-style; runtimes actually use `pivot_root`).

### Layer sharing across images

Layers are **content-addressed** (identified by SHA256 hashes). If multiple images use the same base, they share the identical layers — saving disk space and download time. E.g. image A (`ubuntu:22.04` + Python) and image B (`ubuntu:22.04` + Node.js) share the same Ubuntu base layer.

Registries only store layers they don't already have, and `docker pull` downloads only the layers missing from the local cache.

### Inspecting layers

```console
$ docker history <image-name>
IMAGE          CREATED         CREATED BY                      SIZE
abc1234        2 hours ago     COPY app/ /usr/src/app/         12MB
def5678        2 hours ago     RUN apt-get install -y python3  150MB
ubuntu:22.04   1 week ago                                      77MB
```

`docker inspect` lists the layer digests under `RootFS.Layers`:

```console
$ docker inspect nginx | jq
[
  {
    "Id": "sha256:fb39280b7b9eba5727c884a3c7810002e69e8f961cc373b89c92f14961d903a0",
    "RepoTags": [
      "nginx:latest"
    ],
    "RepoDigests": [
      "nginx@sha256:fb39280b7b9eba5727c884a3c7810002e69e8f961cc373b89c92f14961d903a0"
    ],
    "Parent": "",
    "Comment": "buildkit.dockerfile.v0",
    "Created": "2025-04-16T14:50:31Z",
    "DockerVersion": "",
    "Author": "",
    "Architecture": "arm64",
    "Variant": "v8",
    "Os": "linux",
    "Size": 68846241,
    "GraphDriver": {
      "Data": null,
      "Name": "overlayfs"
    },
    "RootFS": {
      "Type": "layers",
      "Layers": [
        "sha256:41d20f587704e5cf54b3319f41c96bc2463d350e3ffe2326d7643a488409936c",
        "sha256:c2284c4746eb7ed43539bf29fbce541b2dd2499da424b3a41d071f08b50bd23a",
        "sha256:b534cc4d04bf1e43093c150ff42337d75c85c78004ac7fa49e1df04b93575996",
        "sha256:d93efcb7b66f7a9deea3faff3f3c40d03ef963aa5441d38afe71d822488ef6a1",
        "sha256:1727cae455fd1e68906705a2816787611d0584d1a819bb99595eaf90ad2782d5",
        "sha256:cb61c9d607ef4850ea7b604a5b89849f2786634a601d900c5ebbfcd5495b3316",
        "sha256:3ef38621714a87c5fae2d1aac070c45aec8494ca6901b7e375b3694c6a78ee29"
      ]
    },
    "Metadata": {
      "LastTagTime": "2025-06-03T14:42:37.346353Z"
    },
    "Descriptor": {
      "mediaType": "application/vnd.oci.image.index.v1+json",
      "digest": "sha256:fb39280b7b9eba5727c884a3c7810002e69e8f961cc373b89c92f14961d903a0",
      "size": 10248
    },
    "Config": {
      "Cmd": [
        "nginx",
        "-g",
        "daemon off;"
      ],
      "Entrypoint": [
        "/docker-entrypoint.sh"
      ],
      "Env": [
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "NGINX_VERSION=1.27.5",
        "NJS_VERSION=0.8.10",
        "NJS_RELEASE=1~bookworm",
        "PKG_RELEASE=1~bookworm",
        "DYNPKG_RELEASE=1~bookworm"
      ],
      "ExposedPorts": {
        "80/tcp": {}
      },
      "Labels": {
        "maintainer": "NGINX Docker Maintainers <docker-maint@nginx.com>"
      },
      "OnBuild": null,
      "StopSignal": "SIGQUIT",
      "User": "",
      "Volumes": null,
      "WorkingDir": ""
    }
  }
]
```

With the classic `overlay2` storage driver, layers live under `/var/lib/docker/overlay2`. (The directory names there are internal cache IDs, not the `RootFS.Layers` digests themselves.) Inspecting one manually:

```console
$ cd /var/lib/docker/overlay2/53f4a1cb375f9a65b31a763f71c663de37193ff63bd336c04006d14f839cb7a9
$ ls
committed  diff  link  lower  work
```

- `diff/` — the filesystem changes for that layer.
- `lower` — references the layers below it in the stack.
- A container's own directory additionally has the writable `upper` layer.

## Build cache

When you rebuild an image, Docker checks whether each layer has changed; if not, it reuses the cached layer instead of rebuilding it.

Each layer depends on all the layers before it. Its cache key (SHA256) depends on:

- the instruction text itself,
- the files and directories it used (like copied files),
- all layers below it.

If a layer changes, Docker must rebuild it **and every layer after it**. That's why **the order of instructions matters**.

### Example: cache-busting `COPY`

```dockerfile
FROM python:3.11

COPY . /app
WORKDIR /app

RUN pip install -r requirements.txt

CMD ["python", "app.py"]
```

Every time any file in the project changes — even a README or test file — `COPY . /app` changes the build-context hash. Docker invalidates the cache for all subsequent layers, including the costly `pip install`, so it runs on every build even if dependencies haven't changed.

Optimized — least-changing steps first:

```dockerfile
FROM python:3.11

WORKDIR /app

# Copy only dependency file first
COPY requirements.txt .

# Install dependencies (runs only when requirements.txt changes)
RUN pip install -r requirements.txt

# Copy application code (changes frequently)
COPY . .

CMD ["python", "app.py"]
```

Rule of thumb:

1. Base image first → rarely changes.
2. Dependencies next → cache-friendly.
3. Application code last → changes most often.

Managing the cache:

```sh
docker history <image-name>   # inspect layers
docker builder prune          # clear the build cache
```

### Best practices for efficient layers

- Minimize layers: combine related commands with `&&`.
- Put least-changing steps first (to maximize caching).
- Clean up temporary files within the same `RUN` step.
- Use `.dockerignore` to exclude unnecessary files.
- Use multi-stage builds to avoid carrying build-time dependencies into final images.
