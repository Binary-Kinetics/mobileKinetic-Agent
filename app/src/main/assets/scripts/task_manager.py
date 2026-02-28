#!/usr/bin/env python3
"""Task and Calendar Manager Tool for mK:a

This tool provides easy functions to manage tasks and calendar events
using the Tier 1 API endpoints.
"""

import json
import requests
from datetime import datetime, timezone
from typing import Optional, Dict, Any, List

API_BASE = "http://localhost:5563"

class TaskManager:
    """Manage JTX Board tasks"""

    @staticmethod
    def create_task(
        summary: str,
        description: str = "",
        status: str = "NEEDS-ACTION",
        priority: int = 5,
        percent: int = 0,
        checklist: Optional[List[Dict[str, Any]]] = None
    ) -> Dict[str, Any]:
        """Create a new task

        Args:
            summary: Task title
            description: Task description
            status: IN-PROCESS, COMPLETED, or NEEDS-ACTION
            priority: 1-9 (5 is normal)
            percent: 0-100 completion percentage
            checklist: Optional list of dicts with 'item' and 'completed' keys

        Returns:
            API response dict
        """
        # Format checklist in description if provided
        if checklist:
            checklist_text = "\n\n"
            for item in checklist:
                checkbox = "\u2611" if item.get('completed', False) else "\u2610"
                checklist_text += f"{checkbox} {item['item']}\n"
            description += checklist_text.rstrip()

        data = {
            "summary": summary,
            "description": description,
            "status": status,
            "priority": priority,
            "percent": percent
        }

        response = requests.post(f"{API_BASE}/tasks/create", json=data)
        return response.json()

    @staticmethod
    def list_tasks(limit: int = 100) -> Dict[str, Any]:
        """List all tasks"""
        response = requests.get(f"{API_BASE}/tasks/list", params={"limit": limit})
        return response.json()

    @staticmethod
    def update_task(task_id: int, **kwargs) -> Dict[str, Any]:
        """Update a task by ID"""
        data = {"id": task_id, **kwargs}
        response = requests.post(f"{API_BASE}/tasks/update", json=data)
        return response.json()

    @staticmethod
    def delete_task(task_id: int) -> Dict[str, Any]:
        """Delete a task by ID"""
        response = requests.delete(f"{API_BASE}/tasks/delete", params={"task_id": task_id})
        return response.json()


class CalendarManager:
    """Manage calendar events"""

    @staticmethod
    def list_calendars() -> Dict[str, Any]:
        """List all available calendars"""
        response = requests.get(f"{API_BASE}/calendar/list")
        return response.json()

    @staticmethod
    def create_event(
        calendar_id: int,
        title: str,
        start_time: datetime,
        end_time: datetime,
        description: str = "",
        location: str = ""
    ) -> Dict[str, Any]:
        """Create a calendar event

        Args:
            calendar_id: Calendar ID from list_calendars()
            title: Event title
            start_time: Start datetime
            end_time: End datetime
            description: Optional description
            location: Optional location

        Returns:
            API response dict
        """
        # Convert datetime to epoch milliseconds
        start_ms = int(start_time.timestamp() * 1000)
        end_ms = int(end_time.timestamp() * 1000)

        data = {
            "calendar_id": calendar_id,
            "title": title,
            "start_time": start_ms,
            "end_time": end_ms
        }
        if description:
            data["description"] = description
        if location:
            data["location"] = location

        response = requests.post(f"{API_BASE}/calendar/create", json=data)
        return response.json()

    @staticmethod
    def list_events(start_time: datetime, end_time: datetime) -> Dict[str, Any]:
        """List events in a time range"""
        params = {
            "start_time": int(start_time.timestamp() * 1000),
            "end_time": int(end_time.timestamp() * 1000)
        }
        response = requests.get(f"{API_BASE}/calendar/events", params=params)
        return response.json()

    @staticmethod
    def delete_event(event_id: int) -> Dict[str, Any]:
        """Delete an event by ID"""
        response = requests.delete(f"{API_BASE}/calendar/delete", params={"event_id": event_id})
        return response.json()


# Example usage functions
def example_task_with_checklist():
    """Example: Create a task with a checklist"""
    checklist = [
        {"item": "Review project status", "completed": False},
        {"item": "Prepare agenda", "completed": False},
        {"item": "Update dashboard", "completed": True},
        {"item": "Send invites", "completed": False}
    ]

    result = TaskManager.create_task(
        summary="Team Meeting Prep",
        description="Weekly sync preparation",
        status="IN-PROCESS",
        priority=5,
        percent=25,
        checklist=checklist
    )
    print(f"Task created: {json.dumps(result, indent=2)}")
    return result


def example_appointment_with_location():
    """Example: Create an appointment with location"""
    from datetime import timedelta

    # First, list calendars to find the right one
    calendars = CalendarManager.list_calendars()

    # Use the first personal calendar found
    calendar_id = None
    for cal in calendars.get('calendars', []):
        if 'gmail.com' in cal.get('name', ''):
            calendar_id = cal['id']
            break

    if not calendar_id:
        print("No suitable calendar found")
        return None

    # Create an event tomorrow at 12:30 PM
    tomorrow = datetime.now(timezone.utc) + timedelta(days=1)
    start = tomorrow.replace(hour=12, minute=30, second=0, microsecond=0)
    end = start + timedelta(hours=1, minutes=30)

    result = CalendarManager.create_event(
        calendar_id=calendar_id,
        title="Client Lunch",
        start_time=start,
        end_time=end,
        description="Quarterly review",
        location="123 Main St, New York, NY 10001"
    )
    print(f"Event created: {json.dumps(result, indent=2)}")
    return result


if __name__ == "__main__":
    # Quick test of the APIs
    print("Task Manager Tool Ready")
    print("\nAvailable functions:")
    print("- TaskManager: create_task, list_tasks, update_task, delete_task")
    print("- CalendarManager: list_calendars, create_event, list_events, delete_event")
    print("\nExamples: example_task_with_checklist(), example_appointment_with_location()")
