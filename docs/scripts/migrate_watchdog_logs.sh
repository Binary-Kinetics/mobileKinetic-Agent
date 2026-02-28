#!/bin/bash
# Migrate old watchdog log entries to new diagnostic format

OLD_LOG="$HOME/watchdog_errors.log"
PYTHON_SCRIPT="$HOME/watchdog_diagnostic.py"

# Read old log and create diagnostic entries for historical data
while IFS= read -r line; do
    # Skip comments and empty lines
    [[ "$line" =~ ^# ]] && continue
    [[ -z "$line" ]] && continue
    
    # Extract timestamp, PID, and description
    if [[ "$line" =~ \[([0-9-]+\ [0-9:]+)\]\ PID:\ ([0-9]+)\ -\ (.+) ]]; then
        timestamp="${BASH_REMATCH[1]}"
        pid="${BASH_REMATCH[2]}"
        description="${BASH_REMATCH[3]}"
        
        # Create diagnostic entry (no command details available for historical entries)
        python3 "$PYTHON_SCRIPT" "$pid" "unknown (historical)" "$description" 120 "" ""
    fi
done < "$OLD_LOG"

echo "Migration complete. Historical entries logged to diagnostic system."
