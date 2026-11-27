#!/usr/bin/env python3
"""P27 iOS walkthrough: seeds "Anna" into a running Firebase emulator (tools/firestore-emu.sh serve).

Loads the app's rules with a fresh join code, signs Anna up anonymously, registers her and creates the
items of docs/testassets/share_seed.json (written by ShareWalkthroughSeed), then prints the connection
code the app joins with. Project "demo-family" only: it never talks to the cloud.

    python3 tools/share-seed.py docs/testassets/share_seed.json > code.txt
"""
import base64
import json
import random
import string
import sys
import time
import urllib.request

PROJECT = "demo-family"
API_KEY = "fake-api-key"
FIRESTORE = "http://127.0.0.1:8080"
AUTH = "http://127.0.0.1:9099/identitytoolkit.googleapis.com"
DOCS = f"{FIRESTORE}/v1/projects/{PROJECT}/databases/(default)/documents"
JOIN_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def call(method, url, body, token=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read() or b"{}")


def fields(**values):
    out = {}
    for key, value in values.items():
        out[key] = {"integerValue": str(value)} if isinstance(value, int) else {"stringValue": value}
    return {"fields": out}


def main(fixture_path):
    fixture = json.load(open(fixture_path))
    join = "".join(random.choice(JOIN_CHARS) for _ in range(24))
    rules = fixture["rules"].replace("__JOIN_CODE__", join)
    call("PUT", f"{FIRESTORE}/emulator/v1/projects/{PROJECT}:securityRules",
         {"rules": {"files": [{"content": rules}]}})
    anna = call("POST", f"{AUTH}/v1/accounts:signUp?key={API_KEY}", {"returnSecureToken": True})
    uid, token = anna["localId"], anna["idToken"]
    now = int(time.time() * 1000)
    call("PATCH", f"{DOCS}/members/{uid}", fields(name="Anna", joinCode=join, joinedAt=now), token)
    for item in fixture["items"]:
        doc = fields(type=item["type"], ownerUid=uid, authorName="Anna", name=item["name"], version=1,
                     updatedAt=now, schema=item["schema"], payload=item["payload"])
        call("PATCH", f"{DOCS}/items/{item['id']}?currentDocument.exists=false", doc, token)
    wire = json.dumps({"p": PROJECT, "k": API_KEY, "j": join}, separators=(",", ":"))
    print("myhealth-share:1:" + base64.urlsafe_b64encode(wire.encode()).decode().rstrip("="))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/testassets/share_seed.json")
