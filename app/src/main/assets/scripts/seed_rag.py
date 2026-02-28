import urllib.request, json
data = json.load(open("/data/data/com.mobilekinetic.agent/files/home/seed_tasker_docs.json"))
req = urllib.request.Request("http://127.0.0.1:5562/memory", data=json.dumps(data).encode(), headers={"Content-Type": "application/json"})
print(urllib.request.urlopen(req).read().decode())
