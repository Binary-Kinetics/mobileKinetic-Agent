#!/usr/bin/env python3
"""
Watchdog Diagnostic Logger
==========================
Enhanced logging system for watchdog timeout events with full diagnostic details
"""

import json
import os
from datetime import datetime
from pathlib import Path

LOG_FILE = Path.home() / "watchdog_diagnostics.jsonl"
SUMMARY_FILE = Path.home() / "watchdog_summary.txt"


class WatchdogDiagnostics:
    """Enhanced diagnostic logging for watchdog timeout events"""

    @staticmethod
    def log_timeout(
        pid: int,
        command: str,
        description: str,
        duration_seconds: float = None,
        stdout: str = None,
        stderr: str = None,
        exit_code: int = None,
        parent_pid: int = None,
        session_id: str = None,
        additional_context: dict = None
    ):
        """Log a watchdog timeout event with full diagnostic details"""

        timestamp = datetime.now().isoformat()

        # Capture system state
        try:
            import subprocess

            # Get process info if still alive
            process_info = None
            try:
                ps_out = subprocess.run(
                    ["ps", "-p", str(pid), "-o", "pid,ppid,cmd,time,vsz,rss"],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                process_info = ps_out.stdout.strip() if ps_out.returncode == 0 else None
            except:
                pass

            # Get system load
            try:
                with open("/proc/loadavg", "r") as f:
                    load_avg = f.read().strip()
            except:
                load_avg = None

            # Get memory info
            try:
                with open("/proc/meminfo", "r") as f:
                    meminfo_lines = f.readlines()[:5]
                    mem_info = "".join(meminfo_lines).strip()
            except:
                mem_info = None

        except Exception as e:
            process_info = None
            load_avg = None
            mem_info = None

        # Build diagnostic record
        diagnostic = {
            "timestamp": timestamp,
            "pid": pid,
            "command": command,
            "description": description,
            "duration_seconds": duration_seconds,
            "stdout": stdout[:1000] if stdout else None,  # Truncate long output
            "stderr": stderr[:1000] if stderr else None,
            "exit_code": exit_code,
            "parent_pid": parent_pid,
            "session_id": session_id,
            "process_info": process_info,
            "system_load": load_avg,
            "memory_info": mem_info,
            "additional_context": additional_context or {}
        }

        # Write JSONL entry
        with open(LOG_FILE, "a") as f:
            f.write(json.dumps(diagnostic) + "\n")

        # Update human-readable summary
        WatchdogDiagnostics._update_summary(diagnostic)

        return diagnostic

    @staticmethod
    def _update_summary(diagnostic: dict):
        """Update human-readable summary file"""

        summary_lines = []

        summary_lines.append("=" * 80)
        summary_lines.append(f"WATCHDOG TIMEOUT: {diagnostic['timestamp']}")
        summary_lines.append("=" * 80)
        summary_lines.append(f"PID:         {diagnostic['pid']}")
        summary_lines.append(f"Parent PID:  {diagnostic.get('parent_pid', 'Unknown')}")
        summary_lines.append(f"Session:     {diagnostic.get('session_id', 'Unknown')}")
        summary_lines.append(f"Description: {diagnostic['description']}")
        summary_lines.append(f"Duration:    {diagnostic.get('duration_seconds', 'Unknown')}s")
        summary_lines.append("")
        summary_lines.append(f"Command:")
        summary_lines.append(f"  {diagnostic['command']}")
        summary_lines.append("")

        if diagnostic.get('stdout'):
            summary_lines.append(f"STDOUT (first 1000 chars):")
            summary_lines.append(diagnostic['stdout'])
            summary_lines.append("")

        if diagnostic.get('stderr'):
            summary_lines.append(f"STDERR (first 1000 chars):")
            summary_lines.append(diagnostic['stderr'])
            summary_lines.append("")

        if diagnostic.get('exit_code') is not None:
            summary_lines.append(f"Exit Code: {diagnostic['exit_code']}")
            summary_lines.append("")

        if diagnostic.get('process_info'):
            summary_lines.append("Process Info:")
            summary_lines.append(diagnostic['process_info'])
            summary_lines.append("")

        if diagnostic.get('system_load'):
            summary_lines.append(f"System Load: {diagnostic['system_load']}")

        if diagnostic.get('memory_info'):
            summary_lines.append("Memory Info:")
            summary_lines.append(diagnostic['memory_info'])

        summary_lines.append("")
        summary_lines.append("")

        # Append to summary file
        with open(SUMMARY_FILE, "a") as f:
            f.write("\n".join(summary_lines))

    @staticmethod
    def get_recent_timeouts(limit: int = 10):
        """Get recent timeout events from log"""

        if not LOG_FILE.exists():
            return []

        timeouts = []
        with open(LOG_FILE, "r") as f:
            for line in f:
                try:
                    timeouts.append(json.loads(line.strip()))
                except:
                    continue

        return timeouts[-limit:]

    @staticmethod
    def analyze_patterns():
        """Analyze timeout patterns for common issues"""

        if not LOG_FILE.exists():
            return None

        timeouts = WatchdogDiagnostics.get_recent_timeouts(limit=100)

        if not timeouts:
            return None

        # Count by command type
        command_counts = {}
        for t in timeouts:
            cmd = t.get('command', 'unknown')
            cmd_type = cmd.split()[0] if cmd else 'unknown'
            command_counts[cmd_type] = command_counts.get(cmd_type, 0) + 1

        # Count by description
        desc_counts = {}
        for t in timeouts:
            desc = t.get('description', 'unknown')
            desc_counts[desc] = desc_counts.get(desc, 0) + 1

        analysis = {
            "total_timeouts": len(timeouts),
            "command_breakdown": dict(sorted(command_counts.items(), key=lambda x: x[1], reverse=True)),
            "description_breakdown": dict(sorted(desc_counts.items(), key=lambda x: x[1], reverse=True)),
            "average_duration": sum(t.get('duration_seconds', 120) for t in timeouts) / len(timeouts),
            "earliest_timestamp": timeouts[0].get('timestamp'),
            "latest_timestamp": timeouts[-1].get('timestamp')
        }

        return analysis


def cli_log_timeout():
    """CLI interface for logging timeouts"""
    import sys

    if len(sys.argv) < 4:
        print("Usage: watchdog_diagnostic.py <pid> <command> <description> [duration] [stdout] [stderr]")
        sys.exit(1)

    pid = int(sys.argv[1])
    command = sys.argv[2]
    description = sys.argv[3]
    duration = float(sys.argv[4]) if len(sys.argv) > 4 else None
    stdout = sys.argv[5] if len(sys.argv) > 5 else None
    stderr = sys.argv[6] if len(sys.argv) > 6 else None

    result = WatchdogDiagnostics.log_timeout(
        pid=pid,
        command=command,
        description=description,
        duration_seconds=duration,
        stdout=stdout,
        stderr=stderr
    )

    print(f"Logged timeout event: {result['timestamp']}")


def cli_analyze():
    """CLI interface for analyzing patterns"""
    analysis = WatchdogDiagnostics.analyze_patterns()

    if not analysis:
        print("No timeout events found")
        return

    print("\n" + "=" * 80)
    print("WATCHDOG TIMEOUT ANALYSIS")
    print("=" * 80)
    print(f"Total timeouts: {analysis['total_timeouts']}")
    print(f"Time range: {analysis['earliest_timestamp']} to {analysis['latest_timestamp']}")
    print(f"Average duration: {analysis['average_duration']:.1f}s")
    print("\nCommand breakdown:")
    for cmd, count in list(analysis['command_breakdown'].items())[:10]:
        print(f"  {cmd}: {count}")
    print("\nDescription breakdown:")
    for desc, count in list(analysis['description_breakdown'].items())[:10]:
        print(f"  {desc}: {count}")
    print()


if __name__ == "__main__":
    import sys

    if len(sys.argv) > 1 and sys.argv[1] == "analyze":
        cli_analyze()
    else:
        cli_log_timeout()
