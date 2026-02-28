#!/usr/bin/env python3
"""
Add sensor reading endpoint to Tier 2 Device API
This extends the FastAPI server at port 5564 with /sensors/read endpoint
"""

import json
import requests
import time
from typing import Dict, List, Optional

def add_sensor_endpoint_to_tier2():
    """
    Adds a /sensors/read endpoint to the Tier 2 API.
    This endpoint reads sensors on-demand and immediately releases them.
    """

    endpoint_code = '''
@app.post("/sensors/read")
async def read_sensors(sensor_types: List[str] = None):
    """
    Read current sensor values on-demand.
    Only activates sensors briefly to get readings, then releases them.
    No continuous background monitoring.
    """
    import subprocess
    import json

    if sensor_types is None:
        sensor_types = ["accelerometer", "gyroscope", "light", "magnetic_field", "pressure"]

    try:
        # Use the sensor_reader module we created
        result = subprocess.run(
            ["python3", "/data/user/0/com.mobilekinetic.agent/files/home/sensor_reader.py"],
            capture_output=True,
            text=True,
            timeout=5
        )

        if result.returncode == 0:
            # Parse the output to get sensor readings
            output_lines = result.stdout.split("\\n")
            for i, line in enumerate(output_lines):
                if "Sensor readings:" in line:
                    # JSON data starts after this line
                    json_str = "\\n".join(output_lines[i+1:])
                    sensor_data = json.loads(json_str)
                    return {
                        "success": True,
                        "readings": sensor_data,
                        "timestamp": time.time(),
                        "mode": "on-demand",
                        "note": "Sensors were activated briefly and immediately released"
                    }

        # If we can't get real sensor data, return mock data for testing
        mock_readings = {}
        for sensor in sensor_types:
            if sensor == "accelerometer":
                mock_readings[sensor] = {"x": 0.1, "y": -9.8, "z": 0.2, "unit": "m/s\u00b2"}
            elif sensor == "gyroscope":
                mock_readings[sensor] = {"x": 0.01, "y": 0.02, "z": 0.0, "unit": "rad/s"}
            elif sensor == "light":
                mock_readings[sensor] = {"value": 250.0, "unit": "lux"}
            elif sensor == "magnetic_field":
                mock_readings[sensor] = {"x": 30.0, "y": -20.0, "z": 40.0, "unit": "\u03bcT"}
            elif sensor == "pressure":
                mock_readings[sensor] = {"value": 1013.25, "unit": "hPa"}
            elif sensor == "hinge_angle":
                mock_readings[sensor] = {"angle": 90.0, "unit": "degrees"}
            else:
                mock_readings[sensor] = {"status": "not_available"}

        return {
            "success": True,
            "readings": mock_readings,
            "timestamp": time.time(),
            "mode": "mock",
            "note": "Using mock data - real sensor API integration pending"
        }

    except Exception as e:
        return {
            "success": False,
            "error": str(e),
            "message": "Sensor reading failed - endpoint is prepared but needs native integration"
        }
'''

    print("Sensor endpoint code prepared.")
    print("\nTo add this to Tier 2 API:")
    print("1. The endpoint is designed to read sensors on-demand only")
    print("2. Sensors are activated briefly, read once, then immediately released")
    print("3. No continuous background monitoring or system overhead")
    print("4. Each request triggers a fresh sensor read")

    return endpoint_code


def test_sensor_endpoint():
    """Test if the sensor endpoint exists and works"""
    try:
        # Test if Tier 2 API is running
        response = requests.get("http://localhost:5564/health", timeout=2)
        if response.status_code == 200:
            print("Tier 2 API is running")

            # Try to call the sensor endpoint
            response = requests.post(
                "http://localhost:5564/sensors/read",
                json={"sensor_types": ["accelerometer", "light"]},
                timeout=5
            )

            if response.status_code == 200:
                print("Sensor endpoint exists!")
                print(json.dumps(response.json(), indent=2))
            elif response.status_code == 404:
                print("Sensor endpoint not yet implemented in Tier 2 API")
                print("The endpoint code is ready but needs to be integrated")
            else:
                print(f"Unexpected response: {response.status_code}")

    except requests.exceptions.ConnectionError:
        print("Tier 2 API is not running on port 5564")
    except Exception as e:
        print(f"Error testing endpoint: {e}")


if __name__ == "__main__":
    print("=== Sensor Endpoint Design ===")
    print("\nThis endpoint is designed for ON-DEMAND reading only:")
    print("- No continuous background monitoring")
    print("- Sensors activated only when requested")
    print("- Immediate release after reading")
    print("- Zero system overhead when not in use")
    print("\n" + "="*40 + "\n")

    code = add_sensor_endpoint_to_tier2()
    print("\nTesting current status...")
    test_sensor_endpoint()
