#!/bin/bash
# Knowledge Base Backup System
# ============================
# Backs up RAG database, configs, scripts, and knowledge to off-platform storage
# Supports: Local network share, cloud storage, USB, or git repository

set -e

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_NAME="knowledge_backup_$TIMESTAMP"
BACKUP_DIR="$HOME/backups/knowledge/$BACKUP_NAME"

# Source locations
RAG_DB="$HOME/.rag"  # RAG database location (adjust if different)
CONFIGS="$HOME/.claude $HOME/.config"
SCRIPTS="$HOME/*.py $HOME/*.sh $HOME/*.kt"
SECURE_VAULT="$HOME/.secure"
BINARY_AGENT="$HOME/mobileKinetic"

# Backup destinations (configure as needed)
LOCAL_BACKUP="$BACKUP_DIR"
NETWORK_SHARE="/mnt/nas/backups"  # Mount point for network share
CLOUD_SYNC="$HOME/cloud_sync"     # Synced folder (Syncthing, etc.)
GIT_REPO="$HOME/knowledge_repo"   # Git repository for version control

echo "==================================="
echo "Knowledge Base Backup"
echo "==================================="
echo "Timestamp: $TIMESTAMP"
echo ""

# Create backup directory
mkdir -p "$BACKUP_DIR"

# 1. Backup RAG database
echo "[1/6] Backing up RAG database..."
if [ -d "$HOME/.rag" ]; then
    cp -r "$HOME/.rag" "$BACKUP_DIR/rag_db/"
    RAG_SIZE=$(du -sh "$BACKUP_DIR/rag_db/" | cut -f1)
    echo "  RAG DB: $RAG_SIZE"
else
    echo "  RAG DB not found, checking alternate location..."
    # Check for RAG service data
    if pgrep -f rag_mcp_server > /dev/null; then
        echo "  RAG service running, attempting export..."
        curl -s -X POST http://127.0.0.1:5562/export > "$BACKUP_DIR/rag_export.json" 2>/dev/null || echo "  Export endpoint not available"
    fi
fi

# 2. Backup scripts and tools
echo "[2/6] Backing up scripts and tools..."
mkdir -p "$BACKUP_DIR/scripts"
cp -f "$HOME"/*.py "$BACKUP_DIR/scripts/" 2>/dev/null || true
cp -f "$HOME"/*.sh "$BACKUP_DIR/scripts/" 2>/dev/null || true
cp -f "$HOME"/*.kt "$BACKUP_DIR/scripts/" 2>/dev/null || true
cp -f "$HOME"/*.md "$BACKUP_DIR/scripts/" 2>/dev/null || true
SCRIPT_COUNT=$(ls -1 "$BACKUP_DIR/scripts/" 2>/dev/null | wc -l)
echo "  Scripts: $SCRIPT_COUNT files"

# 3. Backup configurations
echo "[3/6] Backing up configurations..."
mkdir -p "$BACKUP_DIR/configs"
cp -r "$HOME/.claude" "$BACKUP_DIR/configs/" 2>/dev/null || true
cp -f "$HOME"/.*.json "$BACKUP_DIR/configs/" 2>/dev/null || true
cp -f "$HOME"/mcp_*.py "$BACKUP_DIR/configs/" 2>/dev/null || true
echo "  Configs backed up"

# 4. Backup secure vault (encrypted data)
echo "[4/6] Backing up secure vault..."
if [ -d "$HOME/.secure" ]; then
    cp -r "$HOME/.secure" "$BACKUP_DIR/vault/"
    # Vault is already encrypted, safe to backup
    VAULT_SIZE=$(du -sh "$BACKUP_DIR/vault/" 2>/dev/null | cut -f1)
    echo "  Vault: $VAULT_SIZE (encrypted)"
fi

# 5. Backup mobileKinetic development
echo "[5/6] Backing up mobileKinetic development..."
if [ -d "$HOME/mobileKinetic" ]; then
    cp -r "$HOME/mobileKinetic" "$BACKUP_DIR/mobilekinetic/"
    BA_SIZE=$(du -sh "$BACKUP_DIR/mobilekinetic/" | cut -f1)
    echo "  mobileKinetic: $BA_SIZE"
fi

# 6. Create backup manifest
echo "[6/6] Creating backup manifest..."
cat > "$BACKUP_DIR/MANIFEST.txt" << MANIFEST
Knowledge Base Backup
=====================
Timestamp: $TIMESTAMP
Backup Name: $BACKUP_NAME
Host: $(hostname)
User: $(whoami)

Contents:
---------
RAG Database:     $(du -sh "$BACKUP_DIR/rag_db/" 2>/dev/null | cut -f1 || echo "N/A")
Scripts:          $SCRIPT_COUNT files
Configs:          $(ls -1 "$BACKUP_DIR/configs/" 2>/dev/null | wc -l) items
Secure Vault:     $(du -sh "$BACKUP_DIR/vault/" 2>/dev/null | cut -f1 || echo "N/A")
mobileKinetic:      $(du -sh "$BACKUP_DIR/mobilekinetic/" 2>/dev/null | cut -f1 || echo "N/A")

Total Size:       $(du -sh "$BACKUP_DIR" | cut -f1)

Restore Instructions:
--------------------
1. Copy backup to target device
2. Extract to ~/knowledge_restore/
3. Run: ./restore_knowledge.sh $BACKUP_NAME

MANIFEST

TOTAL_SIZE=$(du -sh "$BACKUP_DIR" | cut -f1)
echo "  Manifest created"

echo ""
echo "==================================="
echo "Local Backup Complete!"
echo "==================================="
echo "Location: $BACKUP_DIR"
echo "Size: $TOTAL_SIZE"
echo ""

# Optional: Sync to off-platform destinations
echo "Syncing to off-platform storage..."

# Option 1: Network share (if mounted)
if [ -d "$NETWORK_SHARE" ]; then
    echo "  [Network] Copying to $NETWORK_SHARE..."
    cp -r "$BACKUP_DIR" "$NETWORK_SHARE/" && echo "    ✓ Network backup complete"
fi

# Option 2: Cloud sync folder
if [ -d "$CLOUD_SYNC" ]; then
    echo "  [Cloud] Copying to $CLOUD_SYNC..."
    cp -r "$BACKUP_DIR" "$CLOUD_SYNC/" && echo "    ✓ Cloud sync initiated"
fi

# Option 3: Git repository
if [ -d "$GIT_REPO" ]; then
    echo "  [Git] Committing to repository..."
    cd "$GIT_REPO"
    cp -r "$BACKUP_DIR"/* .
    git add -A
    git commit -m "Knowledge backup: $TIMESTAMP" 2>/dev/null && echo "    ✓ Git commit complete"
fi

# Cleanup old local backups (keep last 10)
echo ""
echo "Cleaning up old backups..."
BACKUP_COUNT=$(ls -1d "$HOME/backups/knowledge"/knowledge_backup_* 2>/dev/null | wc -l)
if [ "$BACKUP_COUNT" -gt 10 ]; then
    ls -1dt "$HOME/backups/knowledge"/knowledge_backup_* | tail -n +11 | xargs rm -rf
    REMOVED=$((BACKUP_COUNT - 10))
    echo "  Removed $REMOVED old backup(s)"
else
    echo "  Keeping all $BACKUP_COUNT backup(s)"
fi

echo ""
echo "Backup process complete!"
echo "Latest backup: $BACKUP_DIR"
