"""Desktop tests for the Android adapter, run against the real upstream core.

The repository root provides ``proxy/`` exactly as upstream ships it; the
adapter lives in ``android/app/src/main/python``.
"""
import os
import sys

_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
_ADAPTER = os.path.join(_ROOT, "android", "app", "src", "main", "python")
for path in (_ROOT, _ADAPTER):
    if path not in sys.path:
        sys.path.insert(0, path)
