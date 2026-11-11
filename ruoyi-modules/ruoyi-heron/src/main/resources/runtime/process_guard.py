#!/usr/bin/env python3
"""受控子进程监护：新会话、资源上限、超时与 stdin 失联时终止整组。

仅 Linux 可用；缺 resource 模块直接拒绝启动，不降级为无限制执行。
子进程独立成组，guard 不向自身发送终止信号，保证结果总能写回。
"""
import argparse
import json
import os
import signal
import subprocess
import sys
import threading
import time

MEMORY_LIMIT = 8 * 1024 ** 3
CPU_LIMIT = 240
FILE_LIMIT = 200 * 1024 ** 2
STOP_GRACE_SECONDS = 5


def abort(message):
    print("process_guard: " + message, file=sys.stderr)
    sys.exit(2)


def apply_limits():
    try:
        import resource
    except ImportError:
        abort("需要 Linux resource 模块，当前平台不支持隔离执行")
    resource.setrlimit(resource.RLIMIT_AS, (MEMORY_LIMIT, MEMORY_LIMIT))
    resource.setrlimit(resource.RLIMIT_CPU, (CPU_LIMIT, CPU_LIMIT))
    resource.setrlimit(resource.RLIMIT_FSIZE, (FILE_LIMIT, FILE_LIMIT))


def start_ticks(pid):
    with open(f"/proc/{pid}/stat", "r", encoding="utf-8") as handle:
        data = handle.read()
    fields = data[data.rindex(")") + 2:].split()
    return int(fields[19])


def boot_id():
    try:
        with open("/proc/sys/kernel/random/boot_id", "r", encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return ""


def terminate_group(pgid):
    try:
        os.killpg(pgid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        return
    deadline = time.monotonic() + STOP_GRACE_SECONDS
    while time.monotonic() < deadline:
        try:
            os.killpg(pgid, 0)
        except ProcessLookupError:
            return
        except PermissionError:
            pass
        time.sleep(0.1)
    try:
        os.killpg(pgid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def write_identity(path, payload):
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(payload, handle)
    os.replace(temporary, path)


def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--timeout", type=int, required=True)
    parser.add_argument("--identity", required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    options = parser.parse_args()
    command = options.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        abort("缺少被监护命令")
    if options.timeout < 1 or options.timeout > CPU_LIMIT:
        abort("超时不在允许范围")

    apply_limits()
    os.setsid()
    guard_pid = os.getpid()

    child = subprocess.Popen(command, stdin=subprocess.DEVNULL, close_fds=False,
                             start_new_session=True)
    pgid = os.getpgid(child.pid)
    identity = {
        "guardPid": guard_pid,
        "childPid": child.pid,
        "pgid": pgid,
        "guardStartTicks": start_ticks(guard_pid),
        "childStartTicks": start_ticks(child.pid),
        "bootId": boot_id(),
        "startedAt": time.time(),
    }
    write_identity(options.identity, identity)

    stopped = {"value": False}
    stopping = {"value": False}

    def stop(_signum, _frame):
        if stopping["value"]:
            return
        stopping["value"] = True
        stopped["value"] = True
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        terminate_group(pgid)
        try:
            child.wait(timeout=STOP_GRACE_SECONDS)
        except subprocess.TimeoutExpired:
            pass
        identity["result"] = {"exitCode": child.returncode, "timedOut": False, "stopped": True}
        write_identity(options.identity, identity)
        os._exit(143)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    def watch_stdin():
        try:
            while sys.stdin.buffer.read(1):
                pass
        except (OSError, ValueError):
            pass
        if child.poll() is None and not stopping["value"]:
            stopping["value"] = True
            stopped["value"] = True
            terminate_group(pgid)

    threading.Thread(target=watch_stdin, daemon=True).start()

    timed_out = False
    try:
        child.wait(timeout=options.timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        terminate_group(pgid)
        child.wait()

    identity["result"] = {
        "exitCode": child.returncode,
        "timedOut": timed_out,
        "stopped": stopped["value"],
    }
    write_identity(options.identity, identity)
    if timed_out:
        sys.exit(124)
    sys.exit(0 if child.returncode == 0 else (child.returncode or 1))


if __name__ == "__main__":
    main()
