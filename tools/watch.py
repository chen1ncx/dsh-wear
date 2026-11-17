#!/usr/bin/env python3
"""A tiny ADB host client that talks to an already-running adb server.

The phone has an ADB server listening on 127.0.0.1:5037 (some background ADB
app). Installing the distro `adb` client would risk that client killing the
server because of a version mismatch, so this speaks the wire protocol
directly: length-prefixed host requests, then the device services (`shell:`,
`sync:`).

Usage:
  python3 watch.py devices
  python3 watch.py shell "getprop ro.build.version.sdk"
  python3 watch.py push local.apk /data/local/tmp/local.apk
  python3 watch.py install dist/dsh-watch.apk
  python3 watch.py pull /sdcard/x.png ./x.png
  python3 watch.py shot shot.png
  python3 watch.py start --url http://1.2.3.4:8787 --code 123456
"""

import os
import socket
import struct
import sys
import time

HOST = os.environ.get("ADB_HOST", "127.0.0.1")
PORT = int(os.environ.get("ADB_PORT", "5037"))
SERIAL = os.environ.get("ADB_SERIAL", "")


class AdbError(RuntimeError):
    pass


class Connection:
    def __init__(self, timeout=20, attempts=4):
        last = None
        for _ in range(attempts):
            try:
                self.sock = socket.create_connection((HOST, PORT), timeout=timeout)
                break
            except OSError as err:  # the server app can stall briefly
                last = err
                time.sleep(0.8)
        else:
            raise AdbError("连不上 adb server %s:%d (%s)" % (HOST, PORT, last))
        self.sock.settimeout(timeout)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def send(self, payload: bytes):
        self.sock.sendall(("%04x" % len(payload)).encode() + payload)

    def status(self):
        data = self._read(4)
        if data == b"OKAY":
            return
        if data == b"FAIL":
            length = int(self._read(4), 16)
            raise AdbError(self._read(length).decode("utf-8", "replace"))
        raise AdbError("bad status %r" % data)

    def _read(self, size):
        buf = b""
        while len(buf) < size:
            chunk = self.sock.recv(size - len(buf))
            if not chunk:
                raise AdbError("connection closed")
            buf += chunk
        return buf

    def request(self, service: str):
        self.send(service.encode())
        self.status()

    def raw(self):
        while True:
            chunk = self.sock.recv(65536)
            if not chunk:
                return
            yield chunk


def strip_length_prefix(data: bytes) -> bytes:
    """Some servers send a 4-hex-digit length before the payload; drop it."""
    if len(data) > 4:
        head = data[:4]
        try:
            declared = int(head, 16)
        except ValueError:
            return data
        if declared == len(data) - 4:
            return data[4:]
    return data


def devices():
    conn = Connection()
    try:
        conn.request("host:devices-l")
        text = strip_length_prefix(b"".join(conn.raw())).decode("utf-8", "replace")
    finally:
        conn.close()
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        serial = parts[0]
        state = parts[1] if len(parts) > 1 else "?"
        model = ""
        for part in parts[2:]:
            if part.startswith("model:"):
                model = part.split(":", 1)[1]
        rows.append((serial, state, model))
    return rows


def pick_serial():
    if SERIAL:
        return SERIAL
    rows = [r for r in devices() if r[1] == "device"]
    if not rows:
        raise AdbError("没有在线设备")
    return rows[0][0]


def open_device(serial):
    conn = Connection()
    conn.request("host:transport:%s" % serial)
    return conn


def shell(serial, command, timeout=60):
    conn = open_device(serial)
    try:
        conn.sock.settimeout(timeout)
        conn.request("shell:%s" % command)
        data = b"".join(conn.raw())
    finally:
        conn.close()
    return strip_length_prefix(data).decode("utf-8", "replace").replace("\r\n", "\n")


def sync_push(serial, local, remote):
    size = os.path.getsize(local)
    conn = open_device(serial)
    try:
        conn.sock.settimeout(300)
        conn.request("sync:")
        # Legacy sync: SEND carries "path,mode" as one string.
        spec = ("%s,644" % remote).encode()
        conn.sock.sendall(b"SEND" + struct.pack("<I", len(spec)) + spec)
        sent = 0
        with open(local, "rb") as handle:
            while True:
                chunk = handle.read(65536)
                if not chunk:
                    break
                conn.sock.sendall(b"DATA" + struct.pack("<I", len(chunk)) + chunk)
                sent += len(chunk)
        conn.sock.sendall(b"DONE" + struct.pack("<I", 0))
        status = conn._read(4)
        if status != b"OKAY":
            detail = ""
            try:
                length = struct.unpack("<I", conn._read(4))[0]
                detail = conn._read(length).decode("utf-8", "replace")
            except Exception:
                pass
            raise AdbError("push failed (%s): %s" % (remote, detail))
        return sent, size
    finally:
        conn.close()


def sync_pull(serial, remote, local):
    conn = open_device(serial)
    try:
        conn.sock.settimeout(300)
        conn.request("sync:")
        path = remote.encode()
        conn.sock.sendall(b"RECV" + struct.pack("<I", len(path)) + path)
        os.makedirs(os.path.dirname(os.path.abspath(local)), exist_ok=True)
        total = 0
        with open(local, "wb") as out:
            while True:
                header = conn._read(8)
                tag, length = header[:4], struct.unpack("<I", header[4:])[0]
                if tag == b"DATA":
                    payload = conn._read(length)
                    out.write(payload)
                    total += len(payload)
                elif tag == b"DONE":
                    break
                else:
                    raise AdbError("pull: unexpected %r" % tag)
        return total
    finally:
        conn.close()


def install(serial, apk):
    name = "/data/local/tmp/dsh-wear-%d.apk" % int(time.time())
    print("push →", name)
    sync_push(serial, apk, name)
    print("pm install …")
    out = shell(serial, "pm install -r -t %s" % name, timeout=240)
    print(out.strip())
    shell(serial, "rm -f %s" % name)
    return "Success" in out


def screenshot(serial, local):
    remote = "/sdcard/_dsh_wear_shot.png"
    shell(serial, "screencap -p %s" % remote)
    size = sync_pull(serial, remote, local)
    shell(serial, "rm -f %s" % remote)
    return size


def reverse(serial, device_port, host_port=None):
    """adb reverse: the watch's localhost:<device_port> tunnels to this host's port."""
    host_port = host_port or device_port
    conn = open_device(serial)
    try:
        conn.request("reverse:forward:tcp:%d;tcp:%d" % (device_port, host_port))
        return True
    finally:
        conn.close()


def start(serial, url, code):
    cmd = "am start -n io.dsh.watch/.MainActivity"
    if url:
        cmd += " --es url %s" % url
    if code:
        cmd += " --es code %s" % code
    return shell(serial, cmd)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    action = sys.argv[1]
    args = sys.argv[2:]
    for attempt in range(3):
        try:
            return run(action, args)
        except (AdbError, TimeoutError, OSError) as err:
            if attempt == 2:
                print("ADB ERROR:", err, file=sys.stderr)
                return 1
            time.sleep(1.0)
    return 1


def run(action, args):
    if True:
        if action == "devices":
            for serial, state, model in devices():
                print("%-24s %-10s %s" % (serial, state, model))
            return 0
        serial = pick_serial()
        print("# device:", serial, file=sys.stderr)
        if action == "shell":
            print(shell(serial, " ".join(args)))
            return 0
        if action == "push":
            sent, _ = sync_push(serial, args[0], args[1])
            print("pushed %d bytes" % sent)
            return 0
        if action == "pull":
            size = sync_pull(serial, args[0], args[1])
            print("pulled %d bytes" % size)
            return 0
        if action == "install":
            ok = install(serial, args[0])
            return 0 if ok else 1
        if action == "shot":
            size = screenshot(serial, args[0] if args else "shot.png")
            print("screenshot %d bytes" % size)
            return 0
        if action == "reverse":
            device_port = int(args[0]) if args else 8787
            host_port = int(args[1]) if len(args) > 1 else device_port
            reverse(serial, device_port, host_port)
            print("reverse tcp:%d -> host:%d ok" % (device_port, host_port))
            return 0
        if action == "start":
            url = code = None
            rest = list(args)
            while rest:
                flag = rest.pop(0)
                if flag == "--url":
                    url = rest.pop(0)
                elif flag == "--code":
                    code = rest.pop(0)
            print(start(serial, url, code))
            return 0
        print("unknown action", action)
        return 2


if __name__ == "__main__":
    sys.exit(main())
