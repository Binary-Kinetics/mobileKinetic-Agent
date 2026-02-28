#!/usr/bin/env python3
"""
Smart Camera Tool - Captures photos with sensor data for spatial understanding
Reduces resolution by half and captures orientation/environment data
"""

import json
import requests
import base64
from datetime import datetime
from PIL import Image
import io
import os

class SmartCamera:
    def __init__(self):
        self.tier1_url = "http://localhost:5563"
        self.tier2_url = "http://localhost:5564"
        self.rag_url = "http://127.0.0.1:5562"

    def capture_with_sensors(self):
        """Capture photo with simultaneous sensor readings"""
        result = {
            "timestamp": datetime.now().isoformat(),
            "photo": None,
            "sensors": {},
            "errors": []
        }

        try:
            # 1. Capture photo
            photo_resp = requests.post(f"{self.tier1_url}/camera/photo",
                                      json={}, timeout=10)
            if photo_resp.status_code == 200:
                photo_data = photo_resp.json()
                result["photo"] = photo_data

                # 2. Read and resize the image to half resolution
                if "file_path" in photo_data:
                    self.resize_image(photo_data["file_path"])

            else:
                result["errors"].append(f"Photo capture failed: {photo_resp.text}")

        except Exception as e:
            result["errors"].append(f"Photo error: {str(e)}")

        try:
            # 3. Get sensor readings simultaneously
            sensor_types = [
                "android.sensor.accelerometer",
                "android.sensor.gyroscope",
                "android.sensor.orientation",
                "android.sensor.gravity",
                "android.sensor.rotation_vector",
                "android.sensor.light",
                "android.sensor.pressure",
                "android.sensor.magnetic_field",
                "android.sensor.hinge_angle"
            ]

            # Get current sensor values
            for sensor_type in sensor_types:
                try:
                    sensor_resp = requests.get(f"{self.tier1_url}/sensor/{sensor_type}",
                                              timeout=2)
                    if sensor_resp.status_code == 200:
                        result["sensors"][sensor_type] = sensor_resp.json()
                except:
                    # Try reading via shell if API doesn't support real-time
                    pass

            # Fallback: try getting location for spatial context
            try:
                loc_resp = requests.get(f"{self.tier1_url}/location", timeout=5)
                if loc_resp.status_code == 200:
                    result["sensors"]["location"] = loc_resp.json()
            except:
                pass

            # Get battery and screen state for environment context
            try:
                battery_resp = requests.get(f"{self.tier1_url}/battery", timeout=2)
                if battery_resp.status_code == 200:
                    result["sensors"]["battery"] = battery_resp.json()
            except:
                pass

        except Exception as e:
            result["errors"].append(f"Sensor error: {str(e)}")

        # 4. Store to RAG for future reference
        self.store_to_rag(result)

        return result

    def resize_image(self, file_path):
        """Resize image to half resolution"""
        try:
            # Open image
            img = Image.open(file_path)

            # Calculate half size
            new_width = img.width // 2
            new_height = img.height // 2

            # Resize
            resized = img.resize((new_width, new_height), Image.Resampling.LANCZOS)

            # Save with _half suffix
            base_name = os.path.splitext(file_path)[0]
            new_path = f"{base_name}_half.jpg"
            resized.save(new_path, quality=85)

            return new_path

        except Exception as e:
            print(f"Resize error: {e}")
            return None

    def store_to_rag(self, capture_data):
        """Store capture metadata to RAG for learning"""
        try:
            # Create memory text
            memory = f"Photo captured at {capture_data['timestamp']}"

            if capture_data.get("photo"):
                photo = capture_data["photo"]
                memory += f" - Resolution: {photo.get('width')}x{photo.get('height')}"
                memory += f" - File: {photo.get('file_path')}"

            if capture_data.get("sensors"):
                sensors = capture_data["sensors"]
                if "orientation" in sensors:
                    memory += f" - Orientation: {sensors['orientation']}"
                if "location" in sensors:
                    loc = sensors["location"]
                    memory += f" - Location: {loc.get('latitude')}, {loc.get('longitude')}"
                if "android.sensor.hinge_angle" in sensors:
                    memory += f" - Hinge angle: {sensors['android.sensor.hinge_angle']}"

            # Store to RAG
            requests.post(f"{self.rag_url}/memory",
                         json={
                             "text": memory,
                             "category": "photo_capture",
                             "metadata": json.dumps({
                                 "timestamp": capture_data["timestamp"],
                                 "has_sensors": bool(capture_data.get("sensors")),
                                 "errors": capture_data.get("errors", [])
                             })
                         },
                         timeout=5)

        except Exception as e:
            print(f"RAG storage error: {e}")

def main():
    camera = SmartCamera()
    result = camera.capture_with_sensors()

    print(json.dumps(result, indent=2))

    if result["photo"] and not result["errors"]:
        print("\nSuccess! Photo captured with sensor data.")
        print(f"Original: {result['photo'].get('file_path')}")

        # Check for resized version
        if result["photo"].get("file_path"):
            half_path = result["photo"]["file_path"].replace(".jpg", "_half.jpg")
            if os.path.exists(half_path):
                print(f"Half-res: {half_path}")
    else:
        print("\nPartial success. Check errors.")

if __name__ == "__main__":
    main()
