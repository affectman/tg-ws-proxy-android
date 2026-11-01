"""Config mapping driven by upstream's ``ProxyConfig`` dataclass.

The app stores settings under the ``ProxyConfig`` field names themselves, so
an option that upstream adds to the dataclass shows up in :func:`schema` and is
applied by :func:`apply` without any change here. Only fields that need real
validation (secret, port, Fake TLS domain, DC map) are special-cased.
"""
from __future__ import annotations

import dataclasses
import socket
import typing
from typing import Any, Dict, List, Optional

from proxy.config import ProxyConfig, coerce_domain_list, parse_dc_ip_list, proxy_config

# Value kinds understood by the Kotlin settings screen.
KIND_BOOL = "bool"
KIND_INT = "int"
KIND_FLOAT = "float"
KIND_STR = "str"
KIND_STR_LIST = "str_list"
KIND_DC_MAP = "dc_map"
KIND_UNKNOWN = "unknown"


class ConfigError(ValueError):
    """Bad user input; ``code`` is stable and translated by the app."""

    def __init__(self, code: str, field: Optional[str] = None, detail: Any = None):
        super().__init__(f"{code} ({field}): {detail}" if field else code)
        self.code = code
        self.field = field
        self.detail = None if detail is None else str(detail)

    def to_dict(self) -> Dict[str, Any]:
        return {"code": self.code, "field": self.field, "detail": self.detail}


def _hints() -> Dict[str, Any]:
    return typing.get_type_hints(ProxyConfig)


def _kind_of(tp: Any) -> str:
    if tp is bool:
        return KIND_BOOL
    if tp is int:
        return KIND_INT
    if tp is float:
        return KIND_FLOAT
    if tp is str:
        return KIND_STR
    origin = typing.get_origin(tp)
    args = typing.get_args(tp)
    if origin in (list, List) and args == (str,):
        return KIND_STR_LIST
    if origin in (dict, Dict) and args == (int, str):
        return KIND_DC_MAP
    return KIND_UNKNOWN


def _dc_map_to_list(value: Dict[int, str]) -> List[str]:
    return [f"{dc}:{ip}" for dc, ip in sorted(value.items())]


def _json_default(name: str, kind: str, value: Any) -> Any:
    if name == "secret":
        return ""  # a random default must never leak into the UI
    if kind == KIND_DC_MAP:
        return _dc_map_to_list(value)
    if kind == KIND_UNKNOWN:
        return None
    return value


def schema() -> List[Dict[str, Any]]:
    """Every ``ProxyConfig`` field as ``{"name", "kind", "default"}``."""
    hints = _hints()
    defaults = ProxyConfig()
    result = []
    for f in dataclasses.fields(ProxyConfig):
        kind = _kind_of(hints.get(f.name, f.type))
        result.append({
            "name": f.name,
            "kind": kind,
            "default": _json_default(f.name, kind, getattr(defaults, f.name)),
        })
    return result


def _as_bool(name: str, value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str) and value.strip().lower() in ("1", "true", "yes", "on"):
        return True
    if isinstance(value, str) and value.strip().lower() in ("0", "false", "no", "off", ""):
        return False
    raise ConfigError("bad_value", name, value)


def _as_int(name: str, value: Any) -> int:
    if isinstance(value, bool):
        raise ConfigError("bad_value", name, value)
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        raise ConfigError("bad_value", name, value) from None


def _as_float(name: str, value: Any) -> float:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        raise ConfigError("bad_value", name, value) from None


def _secret(value: Any) -> str:
    secret = str(value or "").strip().lower()
    if len(secret) != 32:
        raise ConfigError("bad_secret", "secret")
    try:
        bytes.fromhex(secret)
    except ValueError:
        raise ConfigError("bad_secret", "secret") from None
    return secret


def _port(value: Any) -> int:
    port = _as_int("port", value)
    if not 1 <= port <= 65535:
        raise ConfigError("bad_port", "port", value)
    return port


def _host(value: Any) -> str:
    host = str(value or "").strip()
    for family in (socket.AF_INET, socket.AF_INET6):
        try:
            socket.inet_pton(family, host)
            return host
        except (OSError, ValueError):
            continue
    raise ConfigError("bad_host", "host", value)


def fake_tls_domain(value: Any) -> str:
    """The Fake TLS domain as ASCII; IDN domains are converted to punycode."""
    domain = str(value or "").strip().rstrip(".")
    if not domain:
        return ""
    try:
        return domain.encode("idna").decode("ascii").lower()
    except UnicodeError:
        raise ConfigError("bad_fake_tls", "fake_tls_domain", domain) from None


def _dc_map(value: Any) -> Dict[int, str]:
    if isinstance(value, dict):
        value = [f"{dc}:{ip}" for dc, ip in value.items()]
    if isinstance(value, str):
        value = coerce_domain_list(value)
    try:
        return parse_dc_ip_list([str(v).strip() for v in value or [] if str(v).strip()])
    except ValueError as exc:
        raise ConfigError("bad_dc_ip", "dc_redirects", getattr(exc, "entry", exc)) from None


_SPECIAL = {
    "secret": _secret,
    "port": _port,
    "host": _host,
    "fake_tls_domain": fake_tls_domain,
}

# Lower bounds for numeric fields that upstream's tray also clamps.
_MIN_INT = {"buffer_size": 4 * 1024, "pool_size": 0}


def build(cfg: Dict[str, Any]) -> ProxyConfig:
    """A validated ``ProxyConfig`` from app settings; unknown keys are ignored.

    Fields missing from ``cfg`` keep upstream's defaults, except ``secret``,
    which is required so the proxy never starts with a random one.
    """
    hints = _hints()
    result = ProxyConfig()
    if "secret" not in cfg:
        raise ConfigError("bad_secret", "secret")
    for f in dataclasses.fields(ProxyConfig):
        if f.name not in cfg:
            continue
        raw = cfg[f.name]
        kind = _kind_of(hints.get(f.name, f.type))
        if f.name in _SPECIAL:
            value = _SPECIAL[f.name](raw)
        elif kind == KIND_BOOL:
            value = _as_bool(f.name, raw)
        elif kind == KIND_INT:
            value = max(_MIN_INT.get(f.name, -(2 ** 31)), _as_int(f.name, raw))
        elif kind == KIND_FLOAT:
            value = _as_float(f.name, raw)
        elif kind == KIND_STR:
            value = "" if raw is None else str(raw).strip()
        elif kind == KIND_STR_LIST:
            value = coerce_domain_list(raw)
        elif kind == KIND_DC_MAP:
            value = _dc_map(raw)
        else:
            continue  # a field type this adapter can't map yet keeps its default
        setattr(result, f.name, value)
    return result


def apply(cfg: Dict[str, Any]) -> ProxyConfig:
    """Validates ``cfg`` and copies it onto upstream's shared ``proxy_config``.

    Upstream modules hold references to that one object, so it is updated in
    place rather than replaced.
    """
    built = build(cfg)
    for f in dataclasses.fields(ProxyConfig):
        setattr(proxy_config, f.name, getattr(built, f.name))
    return proxy_config


def link(cfg: Dict[str, Any]) -> str:
    """``tg://proxy`` link: ``dd`` + secret, or ``ee`` + secret + domain with Fake TLS."""
    from proxy.utils import get_link_host

    secret = _secret(cfg.get("secret"))
    port = _port(cfg.get("port", ProxyConfig.port))
    host = get_link_host(_host(cfg.get("host", ProxyConfig.host)))
    try:
        domain = fake_tls_domain(cfg.get("fake_tls_domain", ""))
    except ConfigError:
        domain = ""
    full = ("ee" + secret + domain.encode("ascii").hex()) if domain else ("dd" + secret)
    return f"tg://proxy?server={host}&port={port}&secret={full}"
