#!/usr/bin/env python3
"""
Sensor Reader for Android via SL4A (Scripting Layer for Android)
Provides on-demand sensor reading without continuous background polling
"""

import json
import time
import subprocess
from typing import Dict, List, Optional, Any

class SensorReader:
    """
    Reads Android sensors on-demand using SL4A Python bindings.
    Only activates sensors when explicitly requested, then immediately unregisters.
    """

    # Map friendly names to Android sensor type constants
    SENSOR_TYPES = {
        "accelerometer": 1,
        "magnetic_field": 2,
        "orientation": 3,  # Deprecated but still works
        "gyroscope": 4,
        "light": 5,
        "pressure": 6,
        "temperature": 7,  # Deprecated
        "proximity": 8,
        "gravity": 9,
        "linear_acceleration": 10,
        "rotation_vector": 11,
        "relative_humidity": 12,
        "ambient_temperature": 13,
        "uncalibrated_magnetic_field": 14,
        "game_rotation_vector": 15,
        "uncalibrated_gyroscope": 16,
        "significant_motion": 17,
        "step_detector": 18,
        "step_counter": 19,
        "geomagnetic_rotation_vector": 20,
        "heart_rate": 21,
        "hinge_angle": 36,  # For foldables
    }

    def __init__(self):
        try:
            # Try to import Android SL4A if available
            import androidhelper
            self.droid = androidhelper.Android()
            self.sl4a_available = True
        except ImportError:
            self.sl4a_available = False
            self.droid = None

    def read_sensors_sl4a(self, sensor_types: List[str], timeout_ms: int = 1000) -> Dict[str, Any]:
        """
        Read sensors using SL4A (if available in Termux environment).
        This method registers listeners, waits briefly for data, then unregisters.
        """
        if not self.sl4a_available:
            return {"error": "SL4A not available in current environment"}

        results = {}

        for sensor_name in sensor_types:
            sensor_type = self.SENSOR_TYPES.get(sensor_name)
            if sensor_type is None:
                results[sensor_name] = {"error": f"Unknown sensor type: {sensor_name}"}
                continue

            try:
                # Start sensing for this specific sensor
                self.droid.startSensingTimed(sensor_type, 250)  # 250ms delay between readings

                # Wait briefly to get a reading
                time.sleep(timeout_ms / 1000.0)

                # Get the sensor reading
                sensor_data = self.droid.sensorsReadAccelerometer().result
                if sensor_name == "accelerometer" and sensor_data:
                    results[sensor_name] = {
                        "x": sensor_data[0],
                        "y": sensor_data[1],
                        "z": sensor_data[2],
                        "timestamp": time.time()
                    }
                elif sensor_name == "magnetic_field":
                    sensor_data = self.droid.sensorsReadMagnetometer().result
                    if sensor_data:
                        results[sensor_name] = {
                            "x": sensor_data[0],
                            "y": sensor_data[1],
                            "z": sensor_data[2],
                            "timestamp": time.time()
                        }
                elif sensor_name == "orientation":
                    sensor_data = self.droid.sensorsReadOrientation().result
                    if sensor_data:
                        results[sensor_name] = {
                            "azimuth": sensor_data[0],
                            "pitch": sensor_data[1],
                            "roll": sensor_data[2],
                            "timestamp": time.time()
                        }
                elif sensor_name == "light":
                    sensor_data = self.droid.sensorsGetLight().result
                    if sensor_data:
                        results[sensor_name] = {
                            "lux": sensor_data,
                            "timestamp": time.time()
                        }
                else:
                    # Generic sensor read
                    results[sensor_name] = {
                        "status": "registered",
                        "message": "Sensor type registered but specific reader not implemented",
                        "timestamp": time.time()
                    }

                # Stop sensing to prevent background overhead
                self.droid.stopSensing()

            except Exception as e:
                results[sensor_name] = {"error": str(e)}

        return results

    def read_sensors_shell(self, sensor_types: List[str]) -> Dict[str, Any]:
        """
        Alternative method using shell commands to read sensor data.
        This works even without SL4A but has limited sensor support.
        """
        results = {}

        # Try to get battery temperature as a proxy sensor reading
        if "temperature" in sensor_types or "battery_temp" in sensor_types:
            try:
                # Read battery temperature from sys interface
                with open("/sys/class/power_supply/battery/temp", "r") as f:
                    temp = int(f.read().strip()) / 10.0  # Convert to Celsius
                    results["battery_temperature"] = {
                        "celsius": temp,
                        "timestamp": time.time()
                    }
            except:
                results["battery_temperature"] = {"error": "Could not read battery temperature"}

        # Try to get light sensor from display brightness (approximation)
        if "light" in sensor_types or "brightness" in sensor_types:
            try:
                with open("/sys/class/leds/lcd-backlight/brightness", "r") as f:
                    brightness = int(f.read().strip())
                    results["screen_brightness"] = {
                        "value": brightness,
                        "max": 255,
                        "timestamp": time.time()
                    }
            except:
                results["screen_brightness"] = {"error": "Could not read screen brightness"}

        # Note about other sensors
        if any(s in sensor_types for s in ["accelerometer", "gyroscope", "magnetic_field"]):
            results["motion_sensors"] = {
                "error": "Motion sensors require native Android API access",
                "suggestion": "Use Tier 1 API with native implementation"
            }

        return results

    def read_sensors(self, sensor_types: Optional[List[str]] = None) -> Dict[str, Any]:
        """
        Main method to read sensors on-demand.
        Tries SL4A first, falls back to shell methods.
        """
        if sensor_types is None:
            sensor_types = ["accelerometer", "gyroscope", "light", "magnetic_field"]

        # Try SL4A first
        if self.sl4a_available:
            return self.read_sensors_sl4a(sensor_types)

        # Fall back to shell methods
        return self.read_sensors_shell(sensor_types)


def main():
    """Test sensor reading"""
    reader = SensorReader()

    print("Testing sensor reader...")
    print(f"SL4A available: {reader.sl4a_available}")

    # Read some common sensors
    results = reader.read_sensors(["accelerometer", "light", "temperature", "brightness"])

    print("\nSensor readings:")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
