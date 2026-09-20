#!/usr/bin/env python3
"""
TCP proxy with a switchable "black hole": reproduces a half-open SSH link without touching the server.

    python3 tools/blackhole_proxy.py --listen 0.0.0.0:2222 --target my.server:22

Point the app's SSH profile at this machine's LAN address and port 2222 (the phone and this machine on
the same Wi-Fi). To the server it is an ordinary SSH connection from this machine. Commands on stdin:

    b  black hole: bytes stop in both directions, connections stay open, nobody gets a FIN or RST.
       This is what an expired NAT mapping, a changed IP or a lost radio looks like to both ends.
    u  restore the path (data held in the buffers is delivered)
    r  reset every connection with RST (an ordinary, honest failure)
    s  status
    q  quit

The same one-letter commands are accepted on an optional control port (--control 127.0.0.1:PORT),
one command per connection, for scripts. Standard library only.
"""
import argparse
import socket
import struct
import sys
import threading

flowing = threading.Event()
flowing.set()
conns_lock = threading.Lock()
conns = set()
stats = {"up": 0, "down": 0, "accepted": 0}


def pump(src, dst, key):
    try:
        while True:
            flowing.wait()
            data = src.recv(65536)
            if not data:
                break
            flowing.wait()  # the black hole may have opened while we waited in recv: hold, do not deliver
            dst.sendall(data)
            stats[key] += len(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass
        with conns_lock:
            conns.discard(src)
            conns.discard(dst)


def handle(client, target, rcvbuf):
    try:
        upstream = socket.create_connection(target, timeout=10)
        upstream.settimeout(None)
    except OSError as e:
        print(f"[proxy] upstream connect failed: {e}", flush=True)
        client.close()
        return
    for s in (client, upstream):
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        if rcvbuf:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
    with conns_lock:
        conns.update((client, upstream))
    threading.Thread(target=pump, args=(client, upstream, "up"), daemon=True).start()
    threading.Thread(target=pump, args=(upstream, client, "down"), daemon=True).start()


def reset_all():
    with conns_lock:
        victims = list(conns)
        conns.clear()
    for s in victims:
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))  # close() -> RST
        except OSError:
            pass
        try:
            s.close()
        except OSError:
            pass


def command(c):
    c = c.strip().lower()[:1]
    if c == "b":
        flowing.clear()
        return "black hole ON"
    if c == "u":
        flowing.set()
        return "black hole off"
    if c == "r":
        reset_all()
        flowing.set()
        return "all connections reset"
    if c == "s":
        with conns_lock:
            n = len(conns) // 2
        return (
            f"flowing={flowing.is_set()} connections={n} up={stats['up']}B "
            f"down={stats['down']}B accepted={stats['accepted']}"
        )
    return "commands: b u r s q"


def control_server(addr):
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(addr)
    srv.listen(8)
    while True:
        c, _ = srv.accept()
        try:
            c.settimeout(2)
            reply = command(c.recv(16).decode("ascii", "ignore"))
            c.sendall((reply + "\n").encode())
        except OSError:
            pass
        finally:
            c.close()


def hostport(v):
    host, _, port = v.rpartition(":")
    return host or "0.0.0.0", int(port)


def main():
    ap = argparse.ArgumentParser(description="TCP proxy with a switchable black hole (half-open link emulation)")
    ap.add_argument("--listen", required=True, type=hostport, metavar="HOST:PORT")
    ap.add_argument("--target", required=True, type=hostport, metavar="HOST:PORT")
    ap.add_argument("--control", type=hostport, metavar="HOST:PORT", help="optional control port for scripts")
    ap.add_argument(
        "--rcvbuf", type=int, default=0,
        help="SO_RCVBUF for proxied sockets (a small value makes writers block sooner)",
    )
    args = ap.parse_args()

    if args.control:
        threading.Thread(target=control_server, args=(args.control,), daemon=True).start()

    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if args.rcvbuf:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, args.rcvbuf)
    srv.bind(args.listen)
    srv.listen(64)
    print(
        f"[proxy] {args.listen[0]}:{args.listen[1]} -> {args.target[0]}:{args.target[1]}  "
        "(b=black hole u=restore r=reset s=status q=quit)",
        flush=True,
    )

    def accept_loop():
        while True:
            client, _ = srv.accept()
            stats["accepted"] += 1
            threading.Thread(target=handle, args=(client, args.target, args.rcvbuf), daemon=True).start()

    threading.Thread(target=accept_loop, daemon=True).start()

    if sys.stdin is None or not sys.stdin.isatty():
        threading.Event().wait()  # under a script: live until killed
    for line in sys.stdin:
        if line.strip().lower().startswith("q"):
            break
        print("[proxy] " + command(line), flush=True)


if __name__ == "__main__":
    main()
