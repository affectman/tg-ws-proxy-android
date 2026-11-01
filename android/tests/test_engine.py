"""Lifecycle tests: the real core listens on a free local port, no internet."""
import json
import socket
import threading
import time

import pytest

from proxy import tg_ws_proxy
from proxy.config import proxy_config
from proxy.pool import cf_worker_pool, ws_pool
from tgws_android import engine

SECRET = "0123456789abcdef0123456789abcdef"


class Recorder:
    """Stands in for the Kotlin CoreListener."""

    def __init__(self):
        self.events = []
        self._cond = threading.Condition()

    def onEvent(self, payload):  # noqa: N802 - Java method name
        with self._cond:
            self.events.append(json.loads(payload))
            self._cond.notify_all()

    def states(self):
        return [e["state"] for e in self.events]

    def wait_for(self, state, timeout=10.0):
        deadline = time.monotonic() + timeout
        with self._cond:
            while not any(e["state"] == state for e in self.events):
                left = deadline - time.monotonic()
                if left <= 0:
                    raise AssertionError(f"no {state!r} event, got {self.states()}")
                self._cond.wait(left)
        return next(e for e in self.events if e["state"] == state)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def calls(monkeypatch):
    """Keeps the core offline and records pool control calls."""
    record = {"reset": 0, "warmup": 0}

    async def warmup():
        record["warmup"] += 1

    def reset():
        record["reset"] += 1

    monkeypatch.setattr(tg_ws_proxy, "start_cfproxy_domain_refresh", lambda: None)
    for pool in (ws_pool, cf_worker_pool):
        monkeypatch.setattr(pool, "warmup", warmup)
        monkeypatch.setattr(pool, "reset", reset)
    yield record
    engine.stop()
    engine.set_power_mode(engine.POWER_ACTIVE)


def config(port, **extra):
    cfg = {"secret": SECRET, "port": port, "pool_size": 2,
           "fallback_cfproxy": False, "cfproxy_h2_media": False}
    cfg.update(extra)
    return json.dumps(cfg)


def wait_until(predicate, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return False


def test_start_listen_stop(calls):
    port = free_port()
    rec = Recorder()
    assert engine.start(config(port), "{}", rec) is None
    rec.wait_for("running")
    assert rec.states()[0] == "starting"

    with socket.create_connection(("127.0.0.1", port), timeout=3):
        pass
    status = json.loads(engine.status())
    assert status["state"] == "running"
    assert status["port"] == port
    assert status["pool_size"] == 2
    assert "connections_total" in status["stats"]

    started = time.monotonic()
    engine.stop()
    assert time.monotonic() - started < 5
    rec.wait_for("stopped")
    assert json.loads(engine.status())["state"] == "stopped"
    assert "listening" in engine.logs()


def test_bad_config_reports_error(calls):
    rec = Recorder()
    error = engine.start(json.dumps({"secret": "nope"}), "{}", rec)
    assert json.loads(error)["code"] == "bad_secret"
    assert rec.wait_for("error")["error"]["code"] == "bad_secret"


def test_port_in_use_suggests_another(calls):
    with socket.socket() as busy:
        busy.bind(("127.0.0.1", 0))
        busy.listen()
        port = busy.getsockname()[1]
        rec = Recorder()
        assert engine.start(config(port), "{}", rec) is None
        error = rec.wait_for("error")["error"]
    assert error["code"] == "port_in_use"
    assert error["suggested_port"] and error["suggested_port"] > port


def test_idle_mode_drops_pool_and_active_restores_lazily(calls):
    rec = Recorder()
    engine.start(config(free_port()), "{}", rec)
    rec.wait_for("running")
    warmups, resets = calls["warmup"], calls["reset"]

    engine.set_power_mode(engine.POWER_IDLE)
    assert wait_until(lambda: proxy_config.pool_size == 0)
    assert wait_until(lambda: calls["reset"] > resets)

    engine.set_power_mode(engine.POWER_ACTIVE)
    assert wait_until(lambda: proxy_config.pool_size == 2)
    time.sleep(0.3)
    assert calls["warmup"] == warmups  # refills on the first real request instead


def test_eager_rewarm_preconnects_on_screen_on(calls):
    rec = Recorder()
    engine.start(config(free_port()), json.dumps({"eager_rewarm": True}), rec)
    rec.wait_for("running")
    engine.set_power_mode(engine.POWER_IDLE)
    assert wait_until(lambda: proxy_config.pool_size == 0)
    warmups = calls["warmup"]
    engine.set_power_mode(engine.POWER_ACTIVE)
    assert wait_until(lambda: calls["warmup"] > warmups)


def test_start_while_idle_keeps_pool_off(calls):
    engine.set_power_mode(engine.POWER_IDLE)
    rec = Recorder()
    engine.start(config(free_port()), "{}", rec)
    rec.wait_for("running")
    assert proxy_config.pool_size == 0
    engine.set_power_mode(engine.POWER_ACTIVE)
    assert wait_until(lambda: proxy_config.pool_size == 2)


def test_network_change_drops_pooled_sockets(calls):
    rec = Recorder()
    engine.start(config(free_port()), "{}", rec)
    rec.wait_for("running")
    resets = calls["reset"]
    engine.on_network_changed()
    assert wait_until(lambda: calls["reset"] > resets)


def test_network_change_rewarms_when_eager(calls):
    rec = Recorder()
    engine.start(config(free_port()), json.dumps({"eager_rewarm": True}), rec)
    rec.wait_for("running")
    warmups, resets = calls["warmup"], calls["reset"]
    engine.on_network_changed()
    assert wait_until(lambda: calls["reset"] > resets and calls["warmup"] > warmups)


def test_stop_without_start_is_harmless(calls):
    rec = Recorder()
    engine.start(json.dumps({"secret": "bad"}), "{}", rec)  # registers listener only
    engine.stop()
    assert json.loads(engine.status())["state"] == "stopped"


def test_link_and_schema():
    assert engine.proxy_link(json.dumps({"secret": SECRET})).startswith("tg://proxy?server=127.0.0.1")
    assert engine.proxy_link(json.dumps({"secret": "bad"})) is None
    assert any(f["name"] == "pool_size" for f in json.loads(engine.schema()))
