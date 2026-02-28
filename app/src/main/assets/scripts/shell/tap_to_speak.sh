#!/bin/bash
# Tap to Speak Script for mobileKinetic:Agent
# Records audio, converts to text, sends to Claude

AUDIO_DIR="/data/user/0/com.mobilekinetic.agent/cache"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
AUDIO_FILE="${AUDIO_DIR}/voice_input_${TIMESTAMP}.m4a"

echo "Starting voice recording..."

# Start recording with vibration feedback
curl -s -X POST 'http://localhost:5563/vibrate/effect' \
  -H 'Content-Type: application/json' \
  -d '{"effect":"click"}'

# Record audio for up to 10 seconds
curl -s -X POST 'http://localhost:5563/audio/record/start' \
  -H 'Content-Type: application/json' \
  -d '{"max_duration_seconds":10,"output_format":"mp4"}' > /tmp/record_response.json

# Wait for user to stop speaking (simplified - you'd tap again to stop)
sleep 3

# Stop recording with vibration feedback
curl -s -X POST 'http://localhost:5563/audio/record/stop' > /tmp/stop_response.json

curl -s -X POST 'http://localhost:5563/vibrate/effect' \
  -H 'Content-Type: application/json' \
  -d '{"effect":"double_click"}'

# Extract file path
FILE_PATH=$(cat /tmp/stop_response.json | grep -o '"file_path":"[^"]*' | cut -d'"' -f4)

echo "Recording saved to: $FILE_PATH"

# TODO: Add speech-to-text processing here
# For now, just notify that recording is complete
curl -s -X POST 'http://localhost:5563/toast' \
  -H 'Content-Type: application/json' \
  -d '{"text":"Voice recording complete","duration":"short"}'
