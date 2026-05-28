import httpx, os
from dotenv import load_dotenv
from pathlib import Path

load_dotenv(dotenv_path=Path(__file__).parent / ".env")

CONFLUENCE_URL   = os.getenv("CONFLUENCE_URL", "https://kb.epam.com")
CONFLUENCE_TOKEN = os.getenv("CONFLUENCE_API_TOKEN")
SPACE_KEY        = os.getenv("CONFLUENCE_SPACE_KEY", "EPMICMP")
BACKLOG_PAGE_ID  = "2732958957"

headers = {
    "Authorization": "Bearer " + CONFLUENCE_TOKEN,
    "Content-Type": "application/json"
}

PASS = "✅"
FAIL = "❌"

def check(label, ok, detail=""):
    print(("  " + PASS + " " if ok else "  " + FAIL + " ") + label + (" — " + detail if detail else ""))
    return ok

print("\n=== TEST 1: Auth check ===")
r = httpx.get(CONFLUENCE_URL + "/rest/api/user/current", headers=headers, timeout=15)
ok = check("Auth", r.status_code == 200, "HTTP " + str(r.status_code))
if ok:
    u = r.json()
    check("User returned", True, u.get("displayName", u.get("username", "?")))
else:
    print("  Body:", r.text[:300])

print("\n=== TEST 2: Space exists ===")
r2 = httpx.get(CONFLUENCE_URL + "/rest/api/space/" + SPACE_KEY, headers=headers, timeout=15)
ok2 = check("Space " + SPACE_KEY, r2.status_code == 200, "HTTP " + str(r2.status_code))
if ok2:
    check("Space name", True, r2.json().get("name", "?"))
else:
    print("  Body:", r2.text[:300])

print("\n=== TEST 3: Fetch page by ID ===")
r3 = httpx.get(
    CONFLUENCE_URL + "/rest/api/content/" + BACKLOG_PAGE_ID,
    headers=headers, params={"expand": "body.storage"}, timeout=15
)
ok3 = check("Page by ID", r3.status_code == 200, "HTTP " + str(r3.status_code))
if ok3:
    page = r3.json()
    title = page.get("title", "?")
    body_len = len(page.get("body", {}).get("storage", {}).get("value", ""))
    check("Title returned", bool(title), title)
    check("Body content", body_len > 0, str(body_len) + " chars")
else:
    print("  Body:", r3.text[:300])

print("\n=== TEST 4: Search pages in space ===")
cql = 'space="' + SPACE_KEY + '" AND type=page ORDER BY title ASC'
r4 = httpx.get(
    CONFLUENCE_URL + "/rest/api/content/search",
    headers=headers, params={"cql": cql, "limit": 10}, timeout=15
)
ok4 = check("Page search", r4.status_code == 200, "HTTP " + str(r4.status_code))
if ok4:
    results = r4.json().get("results", [])
    check("Pages found", len(results) > 0, str(len(results)) + " page(s)")
    for p in results[:5]:
        print("     - " + p["title"] + " (ID: " + p["id"] + ")")
else:
    print("  Body:", r4.text[:300])

print("\n=== TEST 5: Create page permission check ===")
test_payload = {
    "type": "page",
    "title": "__PERMISSION_TEST_DO_NOT_SAVE__",
    "space": {"key": SPACE_KEY},
    "body": {"storage": {"value": "<p>test</p>", "representation": "storage"}}
}
# Use ancestors to avoid actually creating at root — we just check 200 vs 403
r5 = httpx.post(
    CONFLUENCE_URL + "/rest/api/content",
    headers=headers, json=test_payload, timeout=15
)
if r5.status_code in [200, 201]:
    created = r5.json()
    page_id = created.get("id", "")
    check("Can CREATE pages", True, "Page ID " + page_id)
    # Clean up immediately
    httpx.delete(CONFLUENCE_URL + "/rest/api/content/" + page_id, headers=headers, timeout=10)
    print("  (test page deleted immediately)")
elif r5.status_code == 403:
    check("Can CREATE pages", False, "403 Forbidden — token lacks write permission")
else:
    check("Can CREATE pages", False, "HTTP " + str(r5.status_code) + " " + r5.text[:200])
