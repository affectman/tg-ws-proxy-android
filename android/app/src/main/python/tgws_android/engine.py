"""Proxy lifecycle for the Android app, on top of the unmodified core.

Kotlin (``ProxyEngine.kt``) calls the module-level functions below from a
single thread; config, options, status and events travel as JSON strings.

Power use is the reason this file exists in this shape:

* State changes are pushed to Kotlin through ``listener.onEvent(json)``, so
  nothing polls the proxy while the UI is closed.
* ``set_power_mode("idle")`` (screen off) drops upstream's warm WebSocket pool
  to zero. With the default ``pool_size=4`` the core keeps about 16 spare TLS
  connections and re-creates each one every 120 s (``proxy/pool.py``); live
  Telegram connections are not touched.
* Back in ``"active"`` the pool size is restored but, unless the
  ``eager_rewarm`` option is on, nothing is connected yet: the pool refills
  for a DC on the first real request to it. Turning the screen on just to see
  the time then costs no handshakes at all.
* ``on_network_changed()`` drops pooled sockets of the old network at once
  instead of letting them time out one by one.
* ``stop()`` also stops upstream's hourly CF domain refresh thread, which the
  desktop app leaves running for its whole lifetime.
"""
from __future__ import annotations

import asyncio
import errno
import json
import logging
import socket
import threading
import time
from typing import Any, Dict, Optional, Tuple

from proxy import __version__ as core_version_str
from proxy import bridge
from proxy import config as core_config
from proxy import tg_ws_proxy
from proxy.config import proxy_config
from proxy.pool import cf_worker_pool, ws_pool
from proxy.stats import stats

from . import logbuffer, settings
from .settings import ConfigError

log = logging.getLogger("tg-mtproto-proxy")

STATE_STOPPED = "stopped"
STATE_STARTING = "starting"
STATE_RUNNING = "running"
STATE_ERROR = "error"

POWER_ACTIVE = "active"
POWER_IDLE = "idle"

# How long stop() waits for a clean shutdown before aborting client connections.
_GRACEFUL_STOP_S = 2.0
# How far above a busy port to look for a free one.
_PORT_SEARCH_RANGE = 50
# Poll step while waiting for the core to bind; only during startup.
_LISTEN_POLL_S = 0.1

_lock = threading.RLock()
_thread: Optional[threading.Thread] = None
_stop_requested: Optional[threading.Event] = None
_loop_state: Optional[Tuple[asyncio.AbstractEventLoop, asyncio.Event]] = None
_listener: Any = None
_last_error: Optional[Dict[str, Any]] = None
_listening_since: Optional[float] = None
_power_mode = POWER_ACTIVE
_user_pool_size = proxy_config.pool_size
_eager_rewarm = False


# ---------------------------------------------------------------- events

def _state() -> str:
    if _is_running():
        return STATE_RUNNING if _listening_since is not None else STATE_STARTING
    return STATE_ERROR if _last_error else STATE_STOPPED


def _send(state: str, error: Optional[Dict[str, Any]]) -> None:
    """Sends a state event to Kotlin; never raises into the core."""
    listener = _listener
    if listener is None:
        return
    try:
        listener.onEvent(json.dumps({
            "state": state,
            "error": error,
            "port": proxy_config.port,
        }))
    except Exception as exc:  # a broken listener must not kill the proxy
        log.debug("Listener failed: %r", exc)


def _emit() -> None:
    _send(_state(), _last_error)


# ---------------------------------------------------------------- errors

def _error(code: str, detail: Any = None, suggested_port: Optional[int] = None,
           field: Optional[str] = None) -> Dict[str, Any]:
    return {
        "code": code,
        "field": field,
        "detail": None if detail is None else str(detail),
        "suggested_port": suggested_port,
    }


def _errno_in(exc: OSError, *names: str) -> bool:
    codes = {getattr(errno, n) for n in names if hasattr(errno, n)}
    return exc.errno in codes or getattr(exc, "winerror", None) in codes


def _describe_error(exc: BaseException) -> Dict[str, Any]:
    port, host = proxy_config.port, proxy_config.host
    if isinstance(exc, OSError):
        if _errno_in(exc, "EADDRINUSE", "WSAEADDRINUSE"):
            return _error("port_in_use", port, _find_free_port(host, port))
        if _errno_in(exc, "EACCES", "WSAEACCES"):
            return _error("permission", port)
        if _errno_in(exc, "EADDRNOTAVAIL", "WSAEADDRNOTAVAIL"):
            return _error("address", host)
    return _error("unknown", repr(exc))


def _find_free_port(host: str, busy: int) -> Optional[int]:
    """First port above ``busy`` that can be bound on ``host``, or None."""
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    for port in range(busy + 1, min(busy + _PORT_SEARCH_RANGE, 65535) + 1):
        with socket.socket(family, socket.SOCK_STREAM) as s:
            try:
                s.bind((host, port))
            except OSError:
                continue
            return port
    return None


# ---------------------------------------------------------------- proxy thread

def _is_running() -> bool:
    return _thread is not None and _thread.is_alive()


async def _announce_listening(run: "asyncio.Future[Any]") -> None:
    """Emits RUNNING once the core has bound its socket.

    ``_run()`` binds only after optional work such as the Fake TLS probe (up
    to 5 s), and has no callback for it, so this polls during startup only.
    """
    global _listening_since
    while not run.done():
        if tg_ws_proxy._server_instance is not None:
            _listening_since = time.monotonic()
            log.info("Proxy is listening on %s:%d", proxy_config.host, proxy_config.port)
            _emit()
            return
        await asyncio.sleep(_LISTEN_POLL_S)


async def _serve(stop_ev: asyncio.Event) -> None:
    run = asyncio.ensure_future(tg_ws_proxy._run(stop_event=stop_ev))
    stopped = asyncio.ensure_future(stop_ev.wait())
    announcer = asyncio.ensure_future(_announce_listening(run))
    try:
        await asyncio.wait({run, stopped}, return_when=asyncio.FIRST_COMPLETED)
        if not run.done() and tg_ws_proxy._server_instance is None:
            # Nothing listens yet, so there is nothing to shut down cleanly.
            log.info("Stopping before the proxy started listening")
            run.cancel()
        try:
            await run
        except asyncio.CancelledError:
            if not stop_ev.is_set():
                raise
        if not stop_ev.is_set():
            raise RuntimeError("core stopped on its own (listener could not be restored)")
    finally:
        stopped.cancel()
        announcer.cancel()


def _thread_main(stop_requested: threading.Event) -> None:
    global _loop_state, _last_error, _listening_since
    # The loop belongs to this thread; other threads reach it only through
    # call_soon_threadsafe().
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    stop_ev = asyncio.Event()
    with _lock:
        _loop_state = (loop, stop_ev)
        if stop_requested.is_set():
            stop_ev.set()  # stop() came before the loop existed
    error: Optional[Dict[str, Any]] = None
    try:
        loop.run_until_complete(_serve(stop_ev))
    except Exception as exc:
        log.error("Proxy stopped with an error: %r", exc)
        error = _describe_error(exc)
    finally:
        pending = [t for t in asyncio.all_tasks(loop) if not t.done()]
        for task in pending:
            task.cancel()
        if pending:
            loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
        loop.run_until_complete(loop.shutdown_asyncgens())
        loop.close()
        _stop_cfproxy_refresh()
        with _lock:
            _loop_state = None
            _listening_since = None
            if error is not None:
                _last_error = error
        # This thread is still alive, so _state() would still say "running".
        _send(STATE_ERROR if error else STATE_STOPPED, error)


def _stop_cfproxy_refresh() -> None:
    """Stops upstream's hourly CF domain refresh thread, if it exposes one."""
    event = getattr(core_config, "_refresh_stop", None)
    if isinstance(event, threading.Event):
        event.set()


def _abort_lingering_clients() -> None:
    """Unblocks shutdown when a client connection won't close.

    Since Python 3.12, ``Server.wait_closed()`` in ``_run()`` waits for every
    client connection; one that never finishes closing keeps the thread alive.
    """
    with _lock:
        state = _loop_state
    server = tg_ws_proxy._server_instance
    abort = getattr(server, "abort_clients", None)  # Python 3.13+
    if state is None or abort is None:
        return
    log.warning("Proxy is still closing connections; aborting them")
    try:
        state[0].call_soon_threadsafe(abort)
    except RuntimeError:
        pass  # loop already closed


# ---------------------------------------------------------------- pool control (loop thread)

def _warm_pools(loop: asyncio.AbstractEventLoop) -> None:
    """Pre-connects the pool, only when ``eager_rewarm`` is on.

    Otherwise ``ws_pool.get()`` schedules a refill on the first request to a
    DC, so only the DCs Telegram actually uses get spare connections.
    """
    if not _eager_rewarm:
        return
    if proxy_config.pool_size > 0 and tg_ws_proxy._server_instance is not None:
        loop.create_task(ws_pool.warmup())
        loop.create_task(cf_worker_pool.warmup())


def _drop_pools() -> None:
    ws_pool.reset()
    cf_worker_pool.reset()


def _apply_power_mode() -> None:
    loop = asyncio.get_running_loop()
    target = 0 if _power_mode == POWER_IDLE else _user_pool_size
    previous = proxy_config.pool_size
    if target == previous:
        return
    proxy_config.pool_size = target
    if target == 0:
        _drop_pools()
        log.info("Power mode %s: warm pool off", _power_mode)
    else:
        log.info("Power mode %s: warm pool %d", _power_mode, target)
        _warm_pools(loop)


def _network_changed() -> None:
    if tg_ws_proxy._server_instance is None:
        return
    loop = asyncio.get_running_loop()
    log.info("Network changed: dropping pooled connections")
    bridge.reset_tcp_backoff()
    _drop_pools()
    _warm_pools(loop)


def _call_in_loop(fn) -> bool:
    with _lock:
        state = _loop_state
    if state is None:
        return False
    try:
        state[0].call_soon_threadsafe(fn)
        return True
    except RuntimeError:
        return False  # loop already closed


# ---------------------------------------------------------------- API for Kotlin

def start(config_json: str, options_json: str = "{}", listener: Any = None) -> Optional[str]:
    """Starts the proxy in a background thread.

    Returns None when starting (or already running), or the error as JSON when
    the config is invalid. Bind errors arrive later as an ``error`` event.
    ``options_json``: ``{"verbose": bool, "log_dir": str|null, "logcat": bool,
    "eager_rewarm": bool}``.
    """
    global _thread, _stop_requested, _listener, _last_error, _user_pool_size, _eager_rewarm
    with _lock:
        if listener is not None:
            _listener = listener
        if _is_running():
            return None
        options = json.loads(options_json or "{}")
        _eager_rewarm = bool(options.get("eager_rewarm"))
        logbuffer.configure(bool(options.get("verbose")), options.get("log_dir"),
                            bool(options.get("logcat")))
        try:
            settings.apply(json.loads(config_json or "{}"))
        except ConfigError as exc:
            _last_error = _error(exc.code, exc.detail, field=exc.field)
            log.error("Bad config: %s", exc)
            error_json = json.dumps(_last_error)
            thread = None
        else:
            _last_error = None
            error_json = None
            _user_pool_size = proxy_config.pool_size
            if _power_mode == POWER_IDLE:
                proxy_config.pool_size = 0
            stats.__init__()  # per-session counters; the core keeps this object
            log.info("TG WS Proxy for Android: core %s", core_version_str)
            _stop_requested = threading.Event()
            thread = threading.Thread(target=_thread_main, args=(_stop_requested,),
                                      daemon=True, name="tgws-proxy")
            _thread = thread
    if thread is None:
        _send(STATE_ERROR, _last_error)
    else:
        _send(STATE_STARTING, None)  # not alive yet, so _state() can't tell
        thread.start()
    return error_json


def stop(timeout: float = 5.0) -> None:
    """Stops the proxy and waits up to ``timeout`` seconds for it to exit."""
    global _thread, _last_error
    with _lock:
        thread = _thread
        if _stop_requested is not None:
            _stop_requested.set()
        state = _loop_state
        _last_error = None
    if state is not None:
        try:
            state[0].call_soon_threadsafe(state[1].set)
        except RuntimeError:
            pass
    _stop_cfproxy_refresh()
    if thread is None:
        _emit()
        return
    thread.join(timeout=min(_GRACEFUL_STOP_S, timeout))
    if thread.is_alive():
        _abort_lingering_clients()
        thread.join(timeout=max(0.0, timeout - _GRACEFUL_STOP_S))
    with _lock:
        if thread.is_alive():
            log.warning("Proxy thread did not stop within %.0fs", timeout)
            return
        if _thread is thread:
            _thread = None
    log.info("Proxy stopped")


def set_power_mode(mode: str) -> None:
    """``"idle"`` (screen off) turns the warm pool off, ``"active"`` restores it."""
    global _power_mode
    if mode not in (POWER_ACTIVE, POWER_IDLE):
        raise ValueError(mode)
    with _lock:
        if _power_mode == mode:
            return
        _power_mode = mode
    _call_in_loop(_apply_power_mode)


def on_network_changed() -> None:
    _call_in_loop(_network_changed)


def status() -> str:
    since = _listening_since
    return json.dumps({
        "state": _state(),
        "error": _last_error,
        "core_version": core_version_str,
        "port": proxy_config.port,
        "uptime_s": 0 if since is None else int(time.monotonic() - since),
        "power_mode": _power_mode,
        "pool_size": proxy_config.pool_size,
        "stats": {k: v for k, v in vars(stats).items() if isinstance(v, int)},
        "log_seq": logbuffer.buffer.seq,
    })


def schema() -> str:
    return json.dumps(settings.schema())


def proxy_link(config_json: str) -> Optional[str]:
    try:
        return settings.link(json.loads(config_json or "{}"))
    except ConfigError:
        return None


def logs(max_lines: int = 300) -> str:
    return "\n".join(logbuffer.buffer.tail(int(max_lines)))


def clear_logs() -> None:
    logbuffer.buffer.clear()


def core_version() -> str:
    return core_version_str
