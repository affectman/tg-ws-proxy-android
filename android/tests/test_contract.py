"""What the adapter needs from upstream's core.

When upstream renames or removes one of these, this file fails with a message
that says what to look at, instead of the app failing on the phone. Things the
adapter can live without only raise warnings.
"""
import dataclasses
import inspect
import threading
import warnings

from proxy import bridge, tg_ws_proxy
from proxy import config as core_config
from proxy.config import ProxyConfig, proxy_config
from proxy.pool import cf_worker_pool, ws_pool
from proxy.stats import stats

from tgws_android import settings

# Fields the adapter validates or the app shows on its main screens.
REQUIRED_FIELDS = {"port", "host", "secret", "pool_size", "dc_redirects", "fake_tls_domain"}


def test_proxy_config_has_required_fields():
    names = {f.name for f in dataclasses.fields(ProxyConfig)}
    missing = REQUIRED_FIELDS - names
    assert not missing, (
        f"Upstream ProxyConfig lost {sorted(missing)}; update tgws_android/settings.py "
        "and the settings screen")


def test_proxy_config_is_shared_instance():
    assert isinstance(proxy_config, ProxyConfig)


def test_run_accepts_stop_event():
    params = inspect.signature(tg_ws_proxy._run).parameters
    assert "stop_event" in params, "tg_ws_proxy._run() no longer takes stop_event; see engine._serve"
    assert inspect.iscoroutinefunction(tg_ws_proxy._run)
    assert hasattr(tg_ws_proxy, "_server_instance"), "engine waits on tg_ws_proxy._server_instance"


def test_pools_can_be_reset_and_warmed():
    for pool in (ws_pool, cf_worker_pool):
        assert callable(getattr(pool, "reset", None)), f"{type(pool).__name__}.reset() is gone"
        assert inspect.iscoroutinefunction(getattr(pool, "warmup", None)), \
            f"{type(pool).__name__}.warmup() is gone or no longer async"


def test_tcp_backoff_reset_exists():
    assert callable(getattr(bridge, "reset_tcp_backoff", None))


def test_stats_counters_are_ints():
    counters = {k: v for k, v in vars(stats).items() if isinstance(v, int)}
    for key in ("connections_total", "connections_active", "bytes_up", "bytes_down"):
        assert key in counters, f"stats.{key} is gone; the home screen shows it"


def test_cf_refresh_can_be_stopped():
    event = getattr(core_config, "_refresh_stop", None)
    if not isinstance(event, threading.Event):
        warnings.warn("proxy.config._refresh_stop is gone; engine can't stop the hourly "
                      "CF domain refresh after stop() any more")


def test_every_field_has_a_known_kind():
    unknown = [f["name"] for f in settings.schema() if f["kind"] == settings.KIND_UNKNOWN]
    if unknown:
        warnings.warn(f"ProxyConfig fields with types the app can't edit yet: {unknown}; "
                      "they keep upstream defaults")
