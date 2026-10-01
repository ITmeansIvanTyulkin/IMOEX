#!/usr/bin/env python3
"""Shared localhost desk auth for observe scripts (Basic operator).

Reads IMOEX_AUTH_USER / IMOEX_AUTH_PASSWORD, else imoex.auth from
application-local.yml (gitignored). Never logs the password.
"""
from __future__ import annotations

import base64
import json
import os
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESK_URL = "http://127.0.0.1:8080/api/trend/desk"


def _yaml_auth_pair() -> tuple[str, str] | None:
    for rel in ("application-local.yml", "trinity-app/src/main/resources/application-local.yml"):
        p = ROOT / rel
        if not p.is_file():
            continue
        user = password = None
        in_imoex = in_auth = False
        for raw in p.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.split("#", 1)[0].rstrip()
            if not line.strip():
                continue
            if line.startswith("imoex:"):
                in_imoex = True
                in_auth = False
                continue
            if not in_imoex:
                continue
            # left top-level key → leave imoex
            if len(line) >= 1 and not line[0].isspace() and line.endswith(":"):
                break
            stripped = line.strip()
            indent = len(line) - len(line.lstrip(" "))
            if indent == 2 and stripped.startswith("auth:"):
                in_auth = True
                continue
            if in_auth and indent == 2 and stripped.endswith(":") and not stripped.startswith("auth:"):
                in_auth = False
                continue
            if not in_auth:
                continue
            if stripped.startswith("username:"):
                user = stripped.split(":", 1)[1].strip().strip("\"'")
            elif stripped.startswith("password:"):
                password = stripped.split(":", 1)[1].strip().strip("\"'")
        if user and password:
            return user, password
    return None


def operator_basic_header() -> str | None:
    user = (os.environ.get("IMOEX_AUTH_USER") or "").strip() or "imoex"
    password = (os.environ.get("IMOEX_AUTH_PASSWORD") or "").strip()
    if not password:
        pair = _yaml_auth_pair()
        if pair:
            user, password = pair
    if not password:
        return None
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    return f"Basic {token}"


def desk_request(url: str = DESK_URL, timeout: float = 20):
    headers = {"Accept": "application/json"}
    auth = operator_basic_header()
    if auth:
        headers["Authorization"] = auth
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)
