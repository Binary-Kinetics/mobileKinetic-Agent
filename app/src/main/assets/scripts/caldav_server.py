#!/usr/bin/env python3
"""
Simple CalDAV/CardDAV compatible server for mK:a
Provides a local endpoint for DAVx5 to sync with
"""

import os
import json
import uuid
from datetime import datetime
from pathlib import Path
from fastapi import FastAPI, Request, Response, HTTPException
from fastapi.responses import PlainTextResponse
import uvicorn

# Base directory for CalDAV data
CALDAV_BASE = Path.home() / "caldav_data"
CALDAV_BASE.mkdir(exist_ok=True)

# Collections
CALENDAR_PATH = CALDAV_BASE / "calendars"
TASKS_PATH = CALDAV_BASE / "tasks"
CALENDAR_PATH.mkdir(exist_ok=True)
TASKS_PATH.mkdir(exist_ok=True)

app = FastAPI()

# WebDAV/CalDAV headers
CALDAV_HEADERS = {
    "DAV": "1, 2, calendar-access",
    "Allow": "OPTIONS, GET, HEAD, POST, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, MKCALENDAR, REPORT",
}

@app.options("/{path:path}")
async def options(path: str):
    """Handle OPTIONS requests for DAVx5"""
    return Response(headers=CALDAV_HEADERS)

@app.api_route("/.well-known/caldav", methods=["GET", "PROPFIND"])
async def well_known_caldav():
    """Redirect to actual CalDAV endpoint"""
    return Response(
        status_code=301,
        headers={"Location": "/caldav/"}
    )

@app.api_route("/caldav/", methods=["PROPFIND"])
async def caldav_root(request: Request):
    """Root PROPFIND response"""
    xml_response = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:response>
    <d:href>/caldav/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype>
          <d:collection/>
          <c:calendar/>
        </d:resourcetype>
        <d:displayname>mK:a CalDAV</d:displayname>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/caldav/tasks/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype>
          <d:collection/>
          <c:calendar/>
        </d:resourcetype>
        <d:displayname>mK:a Tasks</d:displayname>
        <c:supported-calendar-component-set>
          <c:comp name="VTODO"/>
        </c:supported-calendar-component-set>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/caldav/calendar/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype>
          <d:collection/>
          <c:calendar/>
        </d:resourcetype>
        <d:displayname>mK:a Calendar</d:displayname>
        <c:supported-calendar-component-set>
          <c:comp name="VEVENT"/>
        </c:supported-calendar-component-set>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""

    return Response(
        content=xml_response,
        media_type="application/xml",
        headers=CALDAV_HEADERS
    )

@app.put("/caldav/tasks/{filename}")
async def create_task(filename: str, request: Request):
    """Store a VTODO task"""
    body = await request.body()
    task_file = TASKS_PATH / filename
    task_file.write_bytes(body)

    # Also store in JSON format for easy access
    content = body.decode('utf-8')
    if 'BEGIN:VTODO' in content:
        # Extract basic info
        summary = ""
        for line in content.split('\n'):
            if line.startswith('SUMMARY:'):
                summary = line.replace('SUMMARY:', '').strip()
                break

        metadata = {
            "filename": filename,
            "created": datetime.now().isoformat(),
            "summary": summary
        }
        meta_file = TASKS_PATH / f"{filename}.json"
        meta_file.write_text(json.dumps(metadata, indent=2))

    return Response(status_code=201, headers={"ETag": f'"{uuid.uuid4()}"'})

@app.get("/caldav/tasks/{filename}")
async def get_task(filename: str):
    """Retrieve a VTODO task"""
    task_file = TASKS_PATH / filename
    if not task_file.exists():
        raise HTTPException(status_code=404)

    return Response(
        content=task_file.read_text(),
        media_type="text/calendar",
        headers={"ETag": f'"{uuid.uuid4()}"'}
    )

@app.put("/caldav/calendar/{filename}")
async def create_event(filename: str, request: Request):
    """Store a VEVENT"""
    body = await request.body()
    event_file = CALENDAR_PATH / filename
    event_file.write_bytes(body)

    # Extract and store metadata
    content = body.decode('utf-8')
    if 'BEGIN:VEVENT' in content:
        summary = ""
        location = ""
        for line in content.split('\n'):
            if line.startswith('SUMMARY:'):
                summary = line.replace('SUMMARY:', '').strip()
            elif line.startswith('LOCATION:'):
                location = line.replace('LOCATION:', '').strip()

        metadata = {
            "filename": filename,
            "created": datetime.now().isoformat(),
            "summary": summary,
            "location": location
        }
        meta_file = CALENDAR_PATH / f"{filename}.json"
        meta_file.write_text(json.dumps(metadata, indent=2))

    return Response(status_code=201, headers={"ETag": f'"{uuid.uuid4()}"'})

@app.get("/caldav/calendar/{filename}")
async def get_event(filename: str):
    """Retrieve a VEVENT"""
    event_file = CALENDAR_PATH / filename
    if not event_file.exists():
        raise HTTPException(status_code=404)

    return Response(
        content=event_file.read_text(),
        media_type="text/calendar",
        headers={"ETag": f'"{uuid.uuid4()}"'}
    )

@app.api_route("/caldav/{collection}/{path:path}", methods=["PROPFIND", "REPORT"])
async def collection_propfind(collection: str, path: str = ""):
    """Handle PROPFIND/REPORT for collections"""
    if collection not in ["tasks", "calendar"]:
        raise HTTPException(status_code=404)

    # Simple response for now
    xml_response = f"""<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/caldav/{collection}/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype>
          <d:collection/>
        </d:resourcetype>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""

    return Response(
        content=xml_response,
        media_type="application/xml",
        headers=CALDAV_HEADERS
    )

@app.get("/health")
async def health():
    """Health check endpoint"""
    return {
        "status": "healthy",
        "server": "mK:a CalDAV",
        "collections": {
            "tasks": len(list(TASKS_PATH.glob("*.ics"))),
            "events": len(list(CALENDAR_PATH.glob("*.ics")))
        }
    }

if __name__ == "__main__":
    print(f"Starting CalDAV server on port 8080")
    print(f"CalDAV URL: http://localhost:8080/caldav/")
    print(f"Data directory: {CALDAV_BASE}")
    uvicorn.run(app, host="0.0.0.0", port=8080)
