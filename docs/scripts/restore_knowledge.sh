#!/bin/bash
# Knowledge Base Restore System
# ==============================
# Restores RAG database, configs, scripts from backup
# Usage: ./restore_knowledge.sh <backup_name>

set -e

if [ -z "$1" ]; then
    echo "Usage: ./restore_knowledge.sh <backup_name>"
    echo ""
    echo "Available backups:"
    ls -1dt "$HOME/backups/knowledge"/knowledge_backup_* 2>/dev/null | head -10
    exit 1
fi

BACKUP_NAME="$1"
BACKUP_DIR="$HOME/backups/knowledge/$BACKUP_NAME"

if [ ! -d "$BACKUP_DIR" ]; then
    echo "Error: Backup not found: $BACKUP_DIR"
    exit 1
fi

echo "==================================="
echo "Knowledge Base Restore"
echo "==================================="
echo "Backup: $BACKUP_NAME"
echo ""
cat "$BACKUP_DIR/MANIFEST.txt" 2>/dev/null || echo "No manifest found"
echo ""
read -p "Restore from this backup? (yes/no): " CONFIRM

if [ "$CONFIRM" != "yes" ]; then
    echo "Restore cancelled"
    exit 0
fi

# Create restore backup of current state first
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
PRE_RESTORE_BACKUP="$HOME/backups/pre_restore_$TIMESTAMP"
echo ""
echo "Creating pre-restore backup..."
mkdir -p "$PRE_RESTORE_BACKUP"
cp -r "$HOME/.rag" "$PRE_RESTORE_BACKUP/" 2>/dev/null || true
cp -r "$HOME/.secure" "$PRE_RESTORE_BACKUP/" 2>/dev/null || true
cp "$HOME"/*.py "$PRE_RESTORE_BACKUP/" 2>/dev/null || true
echo "  Saved to: $PRE_RESTORE_BACKUP"

# Restore RAG database
echo ""
echo "[1/5] Restoring RAG database..."
if [ -d "$BACKUP_DIR/rag_db" ]; then
    rm -rf "$HOME/.rag"
    cp -r "$BACKUP_DIR/rag_db" "$HOME/.rag"
    echo "  ✓ RAG database restored"
fi

# Restore scripts
echo "[2/5] Restoring scripts..."
if [ -d "$BACKUP_DIR/scripts" ]; then
    cp -f "$BACKUP_DIR/scripts"/* "$HOME/" 2>/dev/null || true
    chmod +x "$HOME"/*.sh 2>/dev/null || true
    echo "  ✓ Scripts restored"
fi

# Restore configs
echo "[3/5] Restoring configurations..."
if [ -d "$BACKUP_DIR/configs" ]; then
    cp -r "$BACKUP_DIR/configs"/* "$HOME/" 2>/dev/null || true
    echo "  ✓ Configs restored"
fi

# Restore secure vault
echo "[4/5] Restoring secure vault..."
if [ -d "$BACKUP_DIR/vault" ]; then
    rm -rf "$HOME/.secure"
    cp -r "$BACKUP_DIR/vault" "$HOME/.secure"
    chmod 700 "$HOME/.secure"
    echo "  ✓ Vault restored (encrypted)"
fi

# Restore mobileKinetic
echo "[5/5] Restoring mobileKinetic..."
if [ -d "$BACKUP_DIR/mobilekinetic" ]; then
    rm -rf "$HOME/mobileKinetic"
    cp -r "$BACKUP_DIR/mobilekinetic" "$HOME/mobileKinetic"
    echo "  ✓ mobileKinetic restored"
fi

echo ""
echo "==================================="
echo "Restore Complete!"
echo "==================================="
echo "Restored from: $BACKUP_DIR"
echo "Pre-restore backup: $PRE_RESTORE_BACKUP"
echo ""
echo "Next steps:"
echo "  1. Restart RAG service if needed"
echo "  2. Verify restored files"
echo "  3. Test functionality"
