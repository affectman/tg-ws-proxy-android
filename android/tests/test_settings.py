import dataclasses

import pytest

from proxy.config import ProxyConfig, proxy_config
from tgws_android import settings
from tgws_android.settings import ConfigError

SECRET = "0123456789abcdef0123456789abcdef"


def test_schema_lists_every_field_without_leaking_secret():
    fields = {f["name"]: f for f in settings.schema()}
    assert set(fields) == {f.name for f in dataclasses.fields(ProxyConfig)}
    assert fields["secret"]["default"] == ""
    assert fields["port"] == {"name": "port", "kind": "int", "default": 1443}
    assert fields["dc_redirects"]["kind"] == "dc_map"
    assert fields["dc_redirects"]["default"] == ["2:149.154.167.220", "4:149.154.167.220"]
    assert fields["cfproxy_user_domains"]["kind"] == "str_list"
    assert fields["fallback_cfproxy"]["kind"] == "bool"


def test_build_requires_valid_secret():
    with pytest.raises(ConfigError) as exc:
        settings.build({})
    assert exc.value.code == "bad_secret"
    with pytest.raises(ConfigError):
        settings.build({"secret": "xyz"})
    assert settings.build({"secret": SECRET.upper()}).secret == SECRET


@pytest.mark.parametrize("port", [0, 70000, "abc", True])
def test_build_rejects_bad_port(port):
    with pytest.raises(ConfigError) as exc:
        settings.build({"secret": SECRET, "port": port})
    assert exc.value.code in ("bad_port", "bad_value")


def test_build_coerces_types_and_keeps_defaults():
    cfg = settings.build({
        "secret": SECRET,
        "port": "1500",
        "pool_size": -3,
        "buffer_size": 1,
        "fallback_cfproxy": "false",
        "cfproxy_user_domains": "a.example.com, b.example.com;a.example.com",
        "dc_redirects": ["2:1.2.3.4", "4:5.6.7.8"],
        "something_upstream_removed": 1,
    })
    assert cfg.port == 1500
    assert cfg.pool_size == 0
    assert cfg.buffer_size == 4 * 1024
    assert cfg.fallback_cfproxy is False
    assert cfg.cfproxy_user_domains == ["a.example.com", "b.example.com"]
    assert cfg.dc_redirects == {2: "1.2.3.4", 4: "5.6.7.8"}
    assert cfg.cfproxy_h2_media == ProxyConfig().cfproxy_h2_media


def test_build_rejects_bad_dc_ip():
    with pytest.raises(ConfigError) as exc:
        settings.build({"secret": SECRET, "dc_redirects": ["2:not-an-ip"]})
    assert exc.value.code == "bad_dc_ip"


def test_fake_tls_domain_idna():
    assert settings.fake_tls_domain(" Пример.рф. ") == "xn--e1afmkfd.xn--p1ai"
    assert settings.fake_tls_domain("") == ""


def test_apply_updates_shared_instance_in_place():
    before = id(proxy_config)
    settings.apply({"secret": SECRET, "port": 1777, "pool_size": 1})
    assert id(proxy_config) == before
    assert proxy_config.port == 1777 and proxy_config.pool_size == 1
    settings.apply({"secret": SECRET})
    assert proxy_config.port == 1443  # a removed key goes back to the default


def test_link_plain_and_fake_tls():
    assert settings.link({"secret": SECRET, "port": 1443}) == (
        f"tg://proxy?server=127.0.0.1&port=1443&secret=dd{SECRET}")
    link = settings.link({"secret": SECRET, "port": 1443, "fake_tls_domain": "example.com"})
    assert link.endswith("secret=ee" + SECRET + "example.com".encode().hex())
