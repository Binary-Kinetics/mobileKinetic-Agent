package com.mobilekinetic.agent.shared

import android.content.Context

/**
 * Constants for mK:a application paths and configuration.
 *
 * Path hierarchy (at runtime):
 *   /data/data/com.mobilekinetic.agent/files/
 *     usr/          <- PREFIX (Termux bootstrap environment)
 *       bin/        <- Executables (bash, python, etc.)
 *       lib/        <- Shared libraries
 *       etc/        <- Configuration files
 *       include/    <- Header files
 *       share/      <- Shared data
 *       var/        <- Variable data (package cache, logs)
 *       libexec/    <- Internal executables
 *       tmp/        <- Temporary files
 *     home/         <- HOME directory
 *       .bashrc     <- Shell config
 *       .termux/    <- Termux-specific config
 *     usr-staging/  <- Staging dir during bootstrap install (temporary)
 */
object MobileKineticConstants {
    const val PACKAGE_NAME = "com.mobilekinetic.agent"
    const val APP_NAME = "mK:a"
    const val VERSION = "0.1.0"
    const val RAG_PORT = 5562

    // ---- Bootstrap / filesystem path constants (relative to Context.filesDir) ----

    /** Relative path from filesDir to the prefix root (the Termux-compatible sysroot). */
    const val PREFIX_REL = "usr"

    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    // DO NOT MOVE HOME TO EXTERNAL STORAGE (/storage/emulated/0/...)
    //
    // Internal filesDir/home SURVIVES reinstalls (adb install -r).
    // External storage causes:
    //   - fs-guard.js EACCES (blocks /storage/emulated, trailing-slash whitelist bug)
    //   - Incomplete file migration (loses ClaudeShares/, Design/, RLM/, dotfiles)
    //   - Flattened timestamps on all migrated files
    // Reverted in badc7a0 after 2 hours of debugging (2026-02-24).
    // See also: fs-guard.js BLOCKED_PREFIXES at filesDir/home/fs-guard.js
    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    /** Relative path from filesDir to the home directory. */
    const val HOME_REL = "home"

    /** Relative path from filesDir to the staging prefix used during installation. */
    const val STAGING_PREFIX_REL = "usr-staging"

    /** Relative path from prefix to the bin directory. */
    const val BIN_REL = "usr/bin"

    /** Relative path from prefix to the lib directory. */
    const val LIB_REL = "usr/lib"

    /** Relative path from prefix to the etc directory. */
    const val ETC_REL = "usr/etc"

    /** Relative path from prefix to the share directory. */
    const val SHARE_REL = "usr/share"

    /** Relative path from prefix to the tmp directory. */
    const val TMP_REL = "usr/tmp"

    /** Relative path from prefix to the var directory. */
    const val VAR_REL = "usr/var"

    /** Relative path from prefix to the libexec directory. */
    const val LIBEXEC_REL = "usr/libexec"

    /** Relative path from prefix to the include directory. */
    const val INCLUDE_REL = "usr/include"

    /** Relative path from filesDir to the default shell. */
    const val SHELL_REL = "usr/bin/bash"

    // ---- Bootstrap asset configuration ----

    /** Name of the bootstrap ZIP file in assets/. */
    const val BOOTSTRAP_ASSET_NAME = "bootstrap-aarch64.zip"

    /** Name of the symlinks manifest inside the bootstrap ZIP. */
    const val BOOTSTRAP_SYMLINKS_FILE = "SYMLINKS.txt"

    /** Unicode left-arrow delimiter used in SYMLINKS.txt (U+2190). */
    const val SYMLINK_DELIMITER = "\u2190"
}

/**
 * Context-based path resolution for mK:a filesystem paths.
 *
 * Replaces the former hardcoded absolute path constants that were broken placeholders.
 * All paths are derived from [Context.getFilesDir] at runtime, ensuring correctness
 * regardless of package name or installation location.
 *
 * Path hierarchy:
 *   {filesDir}/
 *     usr/          <- prefix (Termux bootstrap sysroot)
 *       bin/        <- executables
 *       lib/        <- shared libraries
 *       etc/        <- configuration files
 *     home/         <- user home directory
 *     usr-staging/  <- temporary staging dir during bootstrap install
 */
object MobileKineticPaths {
    /** Absolute path to the app's internal files directory. */
    fun filesDir(context: Context): String = context.filesDir.absolutePath

    /** Absolute path to the prefix directory (Termux-compatible sysroot). */
    fun prefix(context: Context): String = "${filesDir(context)}/usr"

    /** Absolute path to the home directory. */
    fun home(context: Context): String = "${filesDir(context)}/home"

    /** Absolute path to the bin directory. */
    fun bin(context: Context): String = "${prefix(context)}/bin"

    /** Absolute path to the lib directory. */
    fun lib(context: Context): String = "${prefix(context)}/lib"

    /** Absolute path to the etc directory. */
    fun etc(context: Context): String = "${prefix(context)}/etc"

    /** Absolute path to the default shell (bash). */
    fun shell(context: Context): String = "${bin(context)}/bash"

    /** Absolute path to the staging prefix directory (used during bootstrap install). */
    fun stagingPrefix(context: Context): String = "${filesDir(context)}/usr-staging"
}
