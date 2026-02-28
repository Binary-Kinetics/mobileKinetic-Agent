# Build Promotion Template
# ========================
# Invoked when user confirms "yep all is good" after testing a staging build
# This template gets converted to a TodoWrite checklist for execution

## Pre-Promotion Verification
- [ ] Confirm staging build was tested and is stable
- [ ] Verify staging APK installed and running successfully
- [ ] Check no critical errors in logs
- [ ] Confirm user approval received

## Backup Current Production
- [ ] Create timestamped backup directory: ~/mK:a/backups/production.{timestamp}/
- [ ] Copy entire production/ directory to backup location
- [ ] Verify backup integrity (file count matches)
- [ ] Log backup location to build_history.log

## Promote Staging to Production
- [ ] Remove old production/ directory
- [ ] Copy staging/ directory to production/
- [ ] Verify production/ now contains new source code
- [ ] Update production/VERSION file with build number and date

## Reset Staging Environment
- [ ] Remove current staging/ directory
- [ ] Create fresh copy of production/ as new staging/
- [ ] Verify staging/ is clean duplicate of production/
- [ ] Clear staging/build/ directory if it exists

## Update Metadata
- [ ] Log promotion to build_history.log (timestamp, version, backed up location)
- [ ] Update ~/mK:a/CURRENT_VERSION file
- [ ] Save promotion details to RAG for future reference

## Cleanup Old Backups (Optional)
- [ ] Check number of backups in backups/ directory
- [ ] If more than 5 backups, remove oldest ones
- [ ] Keep at least 3 most recent backups

## Post-Promotion Verification
- [ ] Confirm production/ contains promoted code
- [ ] Confirm staging/ is fresh copy ready for next development
- [ ] Confirm backup exists and is accessible
- [ ] Report promotion completion to user

## Variables
- TIMESTAMP: {timestamp}
- VERSION: {version}
- BACKUP_DIR: ~/mK:a/backups/production.{timestamp}/
- PRODUCTION_DIR: ~/mK:a/production/
- STAGING_DIR: ~/mK:a/staging/
