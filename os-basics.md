# OS Basics

## File descriptors

A **file descriptor (FD)** is a low-level integer handle the OS uses to identify and manage open files or I/O resources within a process. A socket is a file descriptor too.

When a program opens a file (`open()`, `fopen()`, etc.):

1. The OS checks whether the process has permission to access it.
2. It creates a kernel object representing the open file, and an entry in the process's file descriptor table.
3. The process gets back an integer — the file descriptor — that uniquely identifies the open file within the process.

Multiple file descriptors (even across processes) can refer to the same open file.

Standard descriptors — your own files start at 3:

| FD  | Stream          | Usually  |
| --- | --------------- | -------- |
| 0   | standard input  | keyboard |
| 1   | standard output | terminal |
| 2   | standard error  | terminal |

```c
write(1, "Hello, world\n", 13);
```

This writes directly to standard output (file descriptor 1).

## Virtual memory

- Virtual memory is organized into fixed-size blocks called **pages**.
- The **memory management unit (MMU)** maps virtual addresses to physical memory.
