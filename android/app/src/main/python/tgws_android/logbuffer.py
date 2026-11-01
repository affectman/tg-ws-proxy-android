"""Logging for the adapter: an in-memory ring buffer, optional file and logcat.

The buffer is the default sink, so a running proxy doesn't keep writing to
flash. Every handler goes through upstream's ``DomainCensorFilter``.
"""
from __future__ import annotations

import collections
import logging
import logging.handlers
import os
import sys
import threading
from typing import Deque, List, Optional

from proxy.utils import DomainCensorFilter

_FORMAT = logging.Formatter("%(asctime)s  %(levelname)-5s  %(message)s", datefmt="%H:%M:%S")
_QUIET_LOGGERS = ("asyncio", "httpx", "httpcore", "hpack", "h2")


class RingBufferHandler(logging.Handler):
    def __init__(self, capacity: int = 500):
        super().__init__()
        self._lines: Deque[str] = collections.deque(maxlen=capacity)
        self._lock = threading.Lock()
        self._seq = 0

    def emit(self, record: logging.LogRecord) -> None:
        try:
            line = self.format(record)
        except Exception:
            self.handleError(record)
            return
        with self._lock:
            self._lines.append(line)
            self._seq += 1

    def tail(self, max_lines: int) -> List[str]:
        with self._lock:
            lines = list(self._lines)
        return lines[-max_lines:] if max_lines > 0 else lines

    @property
    def seq(self) -> int:
        """Grows with every record; lets the UI skip a refresh when nothing changed."""
        return self._seq

    def clear(self) -> None:
        with self._lock:
            self._lines.clear()
            self._seq += 1


buffer = RingBufferHandler()
_file_handler: Optional[logging.Handler] = None
_logcat_handler: Optional[logging.Handler] = None
_installed = False


def _prepare(handler: logging.Handler) -> logging.Handler:
    handler.setFormatter(_FORMAT)
    handler.addFilter(DomainCensorFilter())
    return handler


def configure(verbose: bool, log_dir: Optional[str], logcat: bool) -> None:
    """(Re)configures the root logger; safe to call before every start."""
    global _installed, _file_handler, _logcat_handler
    root = logging.getLogger()
    if not _installed:
        root.addHandler(_prepare(buffer))
        for name in _QUIET_LOGGERS:
            logging.getLogger(name).setLevel(logging.WARNING)
        _installed = True
    root.setLevel(logging.DEBUG if verbose else logging.INFO)

    if _file_handler is not None:
        root.removeHandler(_file_handler)
        _file_handler.close()
        _file_handler = None
    if log_dir:
        os.makedirs(log_dir, exist_ok=True)
        _file_handler = _prepare(logging.handlers.RotatingFileHandler(
            os.path.join(log_dir, "proxy.log"), maxBytes=1024 * 1024,
            backupCount=1, encoding="utf-8"))
        root.addHandler(_file_handler)

    if logcat and _logcat_handler is None:
        # Chaquopy forwards stderr to logcat.
        _logcat_handler = _prepare(logging.StreamHandler(sys.stderr))
        root.addHandler(_logcat_handler)
    elif not logcat and _logcat_handler is not None:
        root.removeHandler(_logcat_handler)
        _logcat_handler = None
