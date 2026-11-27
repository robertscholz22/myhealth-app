#!/usr/bin/env python3
"""P27 iOS walkthrough: seeds "Anna" into a running Firebase emulator (tools/firestore-emu.sh serve).

Loads the app's rules with a fresh join code, signs Anna up anonymously, registers her and creates the
items of docs/testassets/share_seed.json (written by ShareWalkthroughSeed), then prints the connection
code the app joins with. Project "demo-family" only: it never talks to the cloud.

    python3 tools/share-seed.py docs/testassets/share_seed.json > code.txt

With --code (a real library, for the test with the owner): Anna joins with that connection code over
the production endpoints and shares the same items; the rules are the ones published in the console.
The session is kept in build/anna-session.json for the follow-up actions:

    python3 tools/share-seed.py --code "<message with the code>"
    python3 tools/share-seed.py --code "<…>" update      # Anna changes her recipe's note (version 2)
    python3 tools/share-seed.py --code "<…>" overwrite ID # Anna tries to change someone else's item
    python3 tools/share-seed.py --code "<…>" stop         # Anna stops sharing her workout
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
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
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


def decode(text):
    import re
    body = re.search(r"myhealth-share:1:([A-Za-z0-9_-]+)", text).group(1)
    return json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))


def real(code_text, action, arg, fixture_path="docs/testassets/share_seed.json"):
    import os
    w = decode(code_text)
    docs = f"https://firestore.googleapis.com/v1/projects/{w['p']}/databases/(default)/documents"
    state_path = "build/anna-session.json"
    if action == "seed" and not os.path.exists(state_path):
        anna = call("POST", f"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={w['k']}",
                    {"returnSecureToken": True})
        state = {"uid": anna["localId"], "refresh": anna["refreshToken"]}
        os.makedirs("build", exist_ok=True)
        token = anna["idToken"]
    else:
        state = json.load(open(state_path))
        r = call("POST", f"https://securetoken.googleapis.com/v1/token?key={w['k']}",
                 {"grant_type": "refresh_token", "refresh_token": state["refresh"]})
        token = r["id_token"]
    uid, now = state["uid"], int(time.time() * 1000)
    fixture = json.load(open(fixture_path))
    # Fresh share ids per library, so a phone that knows the fixture's ids from a test library
    # sees new items; kept in the session for the follow-up actions.
    if action == "seed":
        import uuid
        state["ids"] = {i["id"]: str(uuid.uuid4()) for i in fixture["items"]}
        json.dump(state, open(state_path, "w"))
    for item in fixture["items"]:
        for old, new in state.get("ids", {}).items():
            item["payload"] = item["payload"].replace(old, new)
        item["id"] = state.get("ids", {}).get(item["id"], item["id"])
    by_type = {i["type"]: i for i in fixture["items"]}

    def item_doc(item, version, payload):
        return fields(type=item["type"], ownerUid=uid, authorName="Anna", name=item["name"], version=version,
                      updatedAt=now, schema=item["schema"], payload=payload)

    if action == "seed":
        call("PATCH", f"{docs}/members/{uid}", fields(name="Anna", joinCode=w["j"], joinedAt=now), token)
        for item in fixture["items"]:
            call("PATCH", f"{docs}/items/{item['id']}?currentDocument.exists=false",
                 item_doc(item, 1, item["payload"]), token)
        print(f"Anna joined and shared {len(fixture['items'])} items")
    elif action == "update":
        meal = by_type["MEAL"]
        payload = meal["payload"].replace("Top with seeds", "Top with seeds and honey")
        call("PATCH", f"{docs}/items/{meal['id']}", item_doc(meal, 2, payload), token)
        print("Anna updated her recipe to version 2")
    elif action == "overwrite":
        try:
            call("PATCH", f"{docs}/items/{arg}?updateMask.fieldPaths=name", fields(name="Hacked by Anna"), token)
            print("FAIL: the overwrite was accepted")
        except urllib.error.HTTPError as e:
            print(f"refused as expected: HTTP {e.code}")
    elif action == "clear":
        for item_id in arg.split(","):
            call("DELETE", f"{docs}/items/{item_id}", None, token)
        print("Anna removed", arg.count(",") + 1, "items")
    elif action == "stop":
        call("DELETE", f"{docs}/items/{by_type['WORKOUT']['id']}", None, token)
        print("Anna stopped sharing her workout")


if __name__ == "__main__":
    import urllib.error
    if len(sys.argv) > 2 and sys.argv[1] == "--code":
        real(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "seed", sys.argv[4] if len(sys.argv) > 4 else None)
    else:
        main(sys.argv[1] if len(sys.argv) > 1 else "docs/testassets/share_seed.json")
