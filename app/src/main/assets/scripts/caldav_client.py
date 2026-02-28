#!/usr/bin/env python3
"""
CalDAV Client for mK:a
Adds tasks and events to your CalDAV server
"""

import requests
import uuid
from datetime import datetime
from typing import Optional, List, Dict, Any

class CalDAVClient:
    """Simple CalDAV client for creating tasks and events"""

    def __init__(self, base_url: str, username: str = "", password: str = ""):
        """Initialize CalDAV client

        Args:
            base_url: Base URL of CalDAV server (e.g., https://example.com/caldav/)
            username: Optional username for auth
            password: Optional password for auth
        """
        self.base_url = base_url.rstrip('/')
        self.auth = (username, password) if username else None
        self.headers = {
            'Content-Type': 'text/calendar; charset=utf-8',
            'User-Agent': 'mK:a/1.0'
        }

    def create_task(self,
                    summary: str,
                    description: str = "",
                    due_date: Optional[datetime] = None,
                    priority: int = 0,
                    checklist: Optional[List[Dict[str, Any]]] = None,
                    collection: str = "tasks") -> str:
        """Create a VTODO in CalDAV

        Args:
            summary: Task title
            description: Task description
            due_date: Optional due date
            priority: 0-9 (0=undefined, 1=highest, 9=lowest)
            checklist: List of checklist items
            collection: Collection name (default: "tasks")

        Returns:
            UID of created task
        """
        uid = f"{uuid.uuid4()}@mobilekinetic"
        now = datetime.utcnow()

        # Format checklist in description
        if checklist:
            desc_lines = [description] if description else []
            desc_lines.append("")  # Empty line
            for item in checklist:
                checkbox = "[x]" if item.get('completed', False) else "[ ]"
                desc_lines.append(f"{checkbox} {item['item']}")
            description = "\\n".join(desc_lines)

        # Build VTODO
        ical_lines = [
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//mK:a//CalDAV Client//EN",
            "BEGIN:VTODO",
            f"UID:{uid}",
            f"DTSTAMP:{now.strftime('%Y%m%dT%H%M%SZ')}",
            f"CREATED:{now.strftime('%Y%m%dT%H%M%SZ')}",
            f"SUMMARY:{summary}"
        ]

        if description:
            # Properly escape description
            desc_escaped = description.replace('\\n', '\\\\n')
            ical_lines.append(f"DESCRIPTION:{desc_escaped}")

        if due_date:
            ical_lines.append(f"DUE:{due_date.strftime('%Y%m%dT%H%M%SZ')}")

        if priority > 0:
            ical_lines.append(f"PRIORITY:{priority}")

        ical_lines.extend([
            "STATUS:NEEDS-ACTION",
            "END:VTODO",
            "END:VCALENDAR"
        ])

        ical_content = "\r\n".join(ical_lines)

        # PUT to CalDAV server
        url = f"{self.base_url}/{collection}/{uid}.ics"
        response = requests.put(
            url,
            data=ical_content.encode('utf-8'),
            headers=self.headers,
            auth=self.auth
        )

        if response.status_code in [200, 201, 204]:
            return uid
        else:
            raise Exception(f"Failed to create task: {response.status_code} {response.text}")

    def create_event(self,
                     summary: str,
                     start: datetime,
                     end: datetime,
                     description: str = "",
                     location: str = "",
                     collection: str = "calendar") -> str:
        """Create a VEVENT in CalDAV

        Args:
            summary: Event title
            start: Start datetime
            end: End datetime
            description: Optional description
            location: Optional location
            collection: Collection name (default: "calendar")

        Returns:
            UID of created event
        """
        uid = f"{uuid.uuid4()}@mobilekinetic"
        now = datetime.utcnow()

        # Build VEVENT
        ical_lines = [
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//mK:a//CalDAV Client//EN",
            "BEGIN:VEVENT",
            f"UID:{uid}",
            f"DTSTAMP:{now.strftime('%Y%m%dT%H%M%SZ')}",
            f"CREATED:{now.strftime('%Y%m%dT%H%M%SZ')}",
            f"DTSTART:{start.strftime('%Y%m%dT%H%M%SZ')}",
            f"DTEND:{end.strftime('%Y%m%dT%H%M%SZ')}",
            f"SUMMARY:{summary}"
        ]

        if description:
            ical_lines.append(f"DESCRIPTION:{description}")

        if location:
            ical_lines.append(f"LOCATION:{location}")

        ical_lines.extend([
            "END:VEVENT",
            "END:VCALENDAR"
        ])

        ical_content = "\r\n".join(ical_lines)

        # PUT to CalDAV server
        url = f"{self.base_url}/{collection}/{uid}.ics"
        response = requests.put(
            url,
            data=ical_content.encode('utf-8'),
            headers=self.headers,
            auth=self.auth
        )

        if response.status_code in [200, 201, 204]:
            return uid
        else:
            raise Exception(f"Failed to create event: {response.status_code} {response.text}")


# Configuration for your CalDAV server
# Update these values with your actual CalDAV server details
CALDAV_CONFIG = {
    "base_url": "https://your-caldav-server.com/caldav/",  # Update this!
    "username": "your-username",  # Update this!
    "password": "your-password",  # Update this!
    "tasks_collection": "tasks",  # Update if different
    "calendar_collection": "calendar"  # Update if different
}

def add_task_to_caldav(summary: str, description: str = "", checklist: Optional[List[Dict[str, Any]]] = None):
    """Helper function to add a task to your CalDAV server"""
    client = CalDAVClient(
        CALDAV_CONFIG["base_url"],
        CALDAV_CONFIG["username"],
        CALDAV_CONFIG["password"]
    )

    try:
        uid = client.create_task(
            summary=summary,
            description=description,
            checklist=checklist,
            collection=CALDAV_CONFIG["tasks_collection"]
        )
        print(f"Task created successfully with UID: {uid}")
        return uid
    except Exception as e:
        print(f"Error creating task: {e}")
        return None

def add_event_to_caldav(summary: str, start: datetime, end: datetime, location: str = "", description: str = ""):
    """Helper function to add an event to your CalDAV server"""
    client = CalDAVClient(
        CALDAV_CONFIG["base_url"],
        CALDAV_CONFIG["username"],
        CALDAV_CONFIG["password"]
    )

    try:
        uid = client.create_event(
            summary=summary,
            start=start,
            end=end,
            location=location,
            description=description,
            collection=CALDAV_CONFIG["calendar_collection"]
        )
        print(f"Event created successfully with UID: {uid}")
        return uid
    except Exception as e:
        print(f"Error creating event: {e}")
        return None


if __name__ == "__main__":
    print("CalDAV Client Ready")
    print("Please update CALDAV_CONFIG with your server details before using")
    print("Available functions: add_task_to_caldav(), add_event_to_caldav()")
