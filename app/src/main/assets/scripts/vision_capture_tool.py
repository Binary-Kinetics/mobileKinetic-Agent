#!/usr/bin/env python3
"""
Vision Capture Tool for Claude
Captures images with full environmental context for AI analysis
"""

import json
import requests
from datetime import datetime
import base64

class VisionCapture:
    def __init__(self):
        self.tier1_url = "http://localhost:5563"
        self.tier2_url = "http://localhost:5564"

    def capture_with_context(self, camera_id=0, quality=50):
        """Capture photo with full environmental context"""

        # Take photo
        photo_resp = requests.post(f"{self.tier1_url}/camera/photo",
                                   json={"camera_id": str(camera_id), "quality": quality})
        photo_data = photo_resp.json()

        # Gather sensor data
        sensor_types = ["accelerometer", "gyroscope", "gravity", "rotation_vector",
                       "light", "magnetic_field", "hinge_angle"]
        sensor_resp = requests.post(f"{self.tier1_url}/sensors/read",
                                   json={"sensor_types": sensor_types})
        sensor_data = sensor_resp.json()

        # Get location
        location_resp = requests.get(f"{self.tier1_url}/location")
        location_data = location_resp.json()

        # Get battery status
        battery_resp = requests.get(f"{self.tier1_url}/battery")
        battery_data = battery_resp.json()

        # Compile vision context
        context = {
            "timestamp": datetime.now().isoformat(),
            "image": {
                "path": photo_data.get("file_path"),
                "size_bytes": photo_data.get("file_size_bytes"),
                "dimensions": f"{photo_data.get('width')}x{photo_data.get('height')}",
                "quality": photo_data.get("quality"),
                "camera": "back" if camera_id == 0 else "front"
            },
            "environment": {
                "light_level": sensor_data["readings"].get("light", {}).get("value", "unknown"),
                "light_condition": self._interpret_light(sensor_data["readings"].get("light", {}).get("value", 0))
            },
            "device_orientation": self._interpret_orientation(sensor_data["readings"]),
            "location": {
                "lat": location_data.get("latitude"),
                "lon": location_data.get("longitude"),
                "accuracy_m": location_data.get("accuracy_meters")
            },
            "device_state": {
                "hinge_angle": sensor_data["readings"].get("hinge_angle", {}).get("angle", "unknown"),
                "battery_level": battery_data.get("percentage"),
                "charging": battery_data.get("is_charging")
            },
            "raw_sensors": sensor_data["readings"]
        }

        return context

    def _interpret_light(self, lux):
        """Interpret light level in human terms"""
        if lux < 1:
            return "very dark (night/no lights)"
        elif lux < 10:
            return "dark (dim room)"
        elif lux < 50:
            return "low light (normal indoor evening)"
        elif lux < 400:
            return "normal indoor lighting"
        elif lux < 1000:
            return "bright indoor/shaded outdoor"
        else:
            return "bright daylight"

    def _interpret_orientation(self, sensors):
        """Interpret device orientation from sensors"""
        gravity = sensors.get("gravity", {})
        rotation = sensors.get("rotation_vector", {})

        # Analyze gravity vector to determine phone position
        gx = gravity.get("x", 0)
        gy = gravity.get("y", 0)
        gz = gravity.get("z", 0)

        orientation = {
            "gravity_direction": f"x:{gx:.2f} y:{gy:.2f} z:{gz:.2f}",
            "phone_tilt": "unknown"
        }

        # Determine basic orientation
        if abs(gz) > 8:
            if gz > 0:
                orientation["phone_tilt"] = "facing up (back camera down)"
            else:
                orientation["phone_tilt"] = "facing down (screen down)"
        elif abs(gx) > 8:
            orientation["phone_tilt"] = "vertical/portrait"
        elif abs(gy) > 8:
            orientation["phone_tilt"] = "horizontal/landscape"
        else:
            orientation["phone_tilt"] = "angled/tilted"

        # Add rotation quaternion for precise 3D orientation
        if rotation:
            orientation["rotation_quaternion"] = {
                "x": rotation.get("x"),
                "y": rotation.get("y"),
                "z": rotation.get("z"),
                "w": rotation.get("w")
            }

        return orientation

    def describe_scene(self, context):
        """Generate human-readable scene description"""
        desc = f"Image captured at {context['timestamp']}\n"
        desc += f"Camera: {context['image']['camera']} ({context['image']['dimensions']})\n"
        desc += f"Lighting: {context['environment']['light_condition']} ({context['environment']['light_level']} lux)\n"
        desc += f"Device: {context['device_orientation']['phone_tilt']}\n"
        desc += f"Fold state: {context['device_state']['hinge_angle']}° open\n"
        desc += f"File: {context['image']['path']}\n"
        return desc

if __name__ == "__main__":
    vision = VisionCapture()
    context = vision.capture_with_context()

    # Save context to file
    with open("/data/user/0/com.mobilekinetic.agent/files/home/last_vision_context.json", "w") as f:
        json.dump(context, f, indent=2)

    # Print summary
    print(vision.describe_scene(context))
    print("\nFull context saved to: last_vision_context.json")
