/*
 * termux-exec.c - W^X execve() wrapper + filesystem path rewriter for mK:a
 *
 * Android 15+ (API 36) enforces W^X (Write XOR Execute): binaries stored in
 * the app's data directory (/data/user/0/com.mobilekinetic.agent/files/usr/bin/) cannot
 * be executed directly via execve() -- the kernel returns EACCES/Permission denied.
 *
 * Bash works because it's packaged as libbash.so in the APK's native library
 * directory, which the system linker trusts. But all other Termux binaries
 * (apt, dpkg, python3, etc.) live in the data dir and hit the W^X wall.
 *
 * Solution: This LD_PRELOAD library intercepts execve() calls. When the target
 * binary is an ELF file inside /data/, we rewrite the call to go through
 * /system/bin/linker64, which CAN execute ELF binaries from the data directory.
 *
 * Additionally, Termux packages are compiled with hardcoded paths referencing
 * /data/data/com.termux/. Since mK:a's actual package is com.mobilekinetic.agent,
 * we intercept filesystem syscalls and rewrite paths from com.termux to
 * com.mobilekinetic.agent so that all hardcoded paths resolve correctly at runtime.
 *
 * Usage: Set LD_PRELOAD=/path/to/libtermux-exec.so in the shell environment.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <stdarg.h>
#include <sys/stat.h>
#include <dirent.h>
#include <stdio.h>
#include <sys/statvfs.h>
#include <sys/vfs.h>        /* for statfs */
#include <sys/xattr.h>      /* for xattr functions */
#include <sys/inotify.h>    /* for inotify_add_watch */
#include <sys/time.h>       /* for utimes, lutimes */

/* environ is needed by execv/execvp hooks to pass environment to execve */
extern char **environ;

/* The system linker can execute ELF binaries from any location */
#ifdef __aarch64__
#define LINKER_PATH "/system/bin/linker64"
#else
#define LINKER_PATH "/system/bin/linker"
#endif

#define DATA_PREFIX "/data/"
#define DATA_PREFIX_LEN 6

/* Maximum shebang line length we'll parse */
#define SHEBANG_MAX 256

/* ========================================================================
 * PATH REWRITING - com.termux -> com.mobilekinetic.agent
 *
 * Handles absolute, dot-slash (./), and bare relative path prefixes to
 * cover both runtime hardcoded paths and dpkg .deb extraction paths.
 * ======================================================================== */

/* Base prefixes WITHOUT trailing slash - match up to package name boundary */
#define OLD_TERMUX      "com.termux"
#define NEW_TERMUX      "com.mobilekinetic.agent"
#define OLD_TERMUX_LEN  10  /* strlen("com.termux") */
#define NEW_TERMUX_LEN  15  /* strlen("com.mobilekinetic.agent") */

#define ABS_DATA_PREFIX     "/data/data/"
#define ABS_DATA_PREFIX_LEN 11
#define ABS_USER_PREFIX     "/data/user/0/"
#define ABS_USER_PREFIX_LEN 13
#define DOT_DATA_PREFIX     "./data/data/"
#define DOT_DATA_PREFIX_LEN 12
#define DOT_USER_PREFIX     "./data/user/0/"
#define DOT_USER_PREFIX_LEN 14
#define REL_DATA_PREFIX     "data/data/"
#define REL_DATA_PREFIX_LEN 10
#define REL_USER_PREFIX     "data/user/0/"
#define REL_USER_PREFIX_LEN 12

/**
 * Rewrite a path if it starts with a com.termux prefix.
 * Checks absolute, dot-slash (./), and bare relative (no leading /) variants
 * for both /data/data/com.termux and /data/user/0/com.termux.
 *
 * The dot-slash and bare relative variants handle dpkg .deb extraction paths
 * like "./data/data/com.termux/files/usr/..." which bypass the absolute-path
 * checks but still need rewriting to com.mobilekinetic.agent.
 *
 * Matches paths both WITH and WITHOUT a trailing slash after the package name,
 * so both "mkdir ./data/data/com.termux" and "open ./data/data/com.termux/files"
 * are handled correctly.
 *
 * Returns a malloc'd string if rewritten (caller must free), or NULL if no
 * rewrite was needed. free(NULL) is safe so callers can always free the result.
 */
static char *rewrite_path(const char *path) {
    if (!path) return NULL;

    /* Try each prefix variant to find where "com.termux" starts */
    const char *termux_start = NULL;
    size_t prefix_len = 0;

    if (strncmp(path, ABS_DATA_PREFIX, ABS_DATA_PREFIX_LEN) == 0) {
        termux_start = path + ABS_DATA_PREFIX_LEN;
        prefix_len = ABS_DATA_PREFIX_LEN;
    } else if (strncmp(path, ABS_USER_PREFIX, ABS_USER_PREFIX_LEN) == 0) {
        termux_start = path + ABS_USER_PREFIX_LEN;
        prefix_len = ABS_USER_PREFIX_LEN;
    } else if (strncmp(path, DOT_DATA_PREFIX, DOT_DATA_PREFIX_LEN) == 0) {
        termux_start = path + DOT_DATA_PREFIX_LEN;
        prefix_len = DOT_DATA_PREFIX_LEN;
    } else if (strncmp(path, DOT_USER_PREFIX, DOT_USER_PREFIX_LEN) == 0) {
        termux_start = path + DOT_USER_PREFIX_LEN;
        prefix_len = DOT_USER_PREFIX_LEN;
    } else if (strncmp(path, REL_DATA_PREFIX, REL_DATA_PREFIX_LEN) == 0) {
        termux_start = path + REL_DATA_PREFIX_LEN;
        prefix_len = REL_DATA_PREFIX_LEN;
    } else if (strncmp(path, REL_USER_PREFIX, REL_USER_PREFIX_LEN) == 0) {
        termux_start = path + REL_USER_PREFIX_LEN;
        prefix_len = REL_USER_PREFIX_LEN;
    }

    if (!termux_start) return NULL;

    /* Check if "com.termux" follows, and next char is '/' or '\0' */
    if (strncmp(termux_start, OLD_TERMUX, OLD_TERMUX_LEN) != 0) return NULL;

    char after = termux_start[OLD_TERMUX_LEN];
    if (after != '/' && after != '\0') return NULL;

    /* Build rewritten path: [prefix][com.mobilekinetic.agent][rest] */
    const char *rest = termux_start + OLD_TERMUX_LEN; /* points to '/' or '\0' */
    size_t rest_len = strlen(rest);

    char *new_path = malloc(prefix_len + NEW_TERMUX_LEN + rest_len + 1);
    if (!new_path) return NULL;

    memcpy(new_path, path, prefix_len);                          /* copy prefix */
    memcpy(new_path + prefix_len, NEW_TERMUX, NEW_TERMUX_LEN);  /* copy "com.mobilekinetic.agent" */
    memcpy(new_path + prefix_len + NEW_TERMUX_LEN, rest, rest_len + 1); /* copy rest + null */

    return new_path;
}

/* ========================================================================
 * UNIX PATH REWRITING - Standard Unix paths -> Termux prefix
 *
 * Rewrites standard Unix paths (e.g. /usr/bin/env, /bin/sh) to the
 * mK:a Termux prefix so that shebangs like #!/usr/bin/env node
 * resolve correctly on Android. Only used in exec hooks.
 * ======================================================================== */

/* Termux prefix for Unix path rewriting */
#define TERMUX_PREFIX "/data/data/com.mobilekinetic.agent/files/usr"
#define TERMUX_PREFIX_LEN 36

/**
 * Rewrite a standard Unix path to the mK:a Termux prefix.
 *
 * Examples:
 *   /usr/bin/env   -> /data/data/com.mobilekinetic.agent/files/usr/bin/env
 *   /bin/sh        -> /data/data/com.mobilekinetic.agent/files/usr/bin/sh
 *   /etc/profile   -> /data/data/com.mobilekinetic.agent/files/usr/etc/profile
 *   /tmp           -> /data/data/com.mobilekinetic.agent/files/usr/tmp
 *
 * Returns a malloc'd string if rewritten (caller must free), or NULL if no
 * rewrite was needed. free(NULL) is safe so callers can always free the result.
 *
 * This function is intended ONLY for use in exec hooks, not file ops.
 */
static char *rewrite_unix_path(const char *path) {
    if (!path || path[0] != '/') return NULL;

    /* Don't rewrite paths already under /data/ (includes our prefix) */
    if (strncmp(path, "/data/", 6) == 0) return NULL;
    /* Don't rewrite Android system paths */
    if (strncmp(path, "/system/", 8) == 0) return NULL;
    if (strncmp(path, "/proc/", 6) == 0) return NULL;
    if (strncmp(path, "/sys/", 5) == 0) return NULL;
    if (strncmp(path, "/dev/", 5) == 0) return NULL;

    /*
     * Rewrite standard Unix paths to Termux prefix.
     * Match longer prefixes first to avoid partial matches.
     *
     * /usr/bin/X, /usr/lib/X, /usr/share/X, /usr/include/X
     *   -> TERMUX_PREFIX + /bin/X, /lib/X, /share/X, /include/X
     *   (strip "/usr", keep the rest)
     *
     * /bin/X, /lib/X, /etc/X, /tmp/X, /var/X
     *   -> TERMUX_PREFIX + /bin/X, /lib/X, /etc/X, /tmp/X, /var/X
     *   (keep the whole path as suffix)
     */
    const char *suffix = NULL;

    /* Check /usr/ sub-paths first (longer prefix, must match before /usr alone) */
    if (strncmp(path, "/usr/", 5) == 0) {
        /* Only rewrite specific /usr/ sub-paths, not /usr/ itself */
        if (strncmp(path + 4, "/bin", 4) == 0 && (path[8] == '/' || path[8] == '\0')) {
            suffix = path + 4; /* skip "/usr", keep "/bin/X" */
        } else if (strncmp(path + 4, "/lib", 4) == 0 && (path[8] == '/' || path[8] == '\0')) {
            suffix = path + 4; /* skip "/usr", keep "/lib/X" */
        } else if (strncmp(path + 4, "/share", 6) == 0 && (path[10] == '/' || path[10] == '\0')) {
            suffix = path + 4; /* skip "/usr", keep "/share/X" */
        } else if (strncmp(path + 4, "/include", 8) == 0 && (path[12] == '/' || path[12] == '\0')) {
            suffix = path + 4; /* skip "/usr", keep "/include/X" */
        }
    } else if (strncmp(path, "/bin", 4) == 0 && (path[4] == '/' || path[4] == '\0')) {
        /* /bin/X -> TERMUX_PREFIX + /bin/X */
        suffix = path;
    } else if (strncmp(path, "/lib", 4) == 0 && (path[4] == '/' || path[4] == '\0')) {
        /* /lib/X -> TERMUX_PREFIX + /lib/X */
        suffix = path;
    } else if (strncmp(path, "/etc", 4) == 0 && (path[4] == '/' || path[4] == '\0')) {
        /* /etc/X -> TERMUX_PREFIX + /etc/X */
        suffix = path;
    } else if (strncmp(path, "/tmp", 4) == 0 && (path[4] == '/' || path[4] == '\0')) {
        /* /tmp or /tmp/X -> TERMUX_PREFIX + /tmp or /tmp/X */
        suffix = path;
    } else if (strncmp(path, "/var", 4) == 0 && (path[4] == '/' || path[4] == '\0')) {
        /* /var/X -> TERMUX_PREFIX + /var/X */
        suffix = path;
    }

    if (!suffix) return NULL;

    size_t suffix_len = strlen(suffix);
    char *new_path = malloc(TERMUX_PREFIX_LEN + suffix_len + 1);
    if (!new_path) return NULL;

    memcpy(new_path, TERMUX_PREFIX, TERMUX_PREFIX_LEN);
    memcpy(new_path + TERMUX_PREFIX_LEN, suffix, suffix_len + 1);

    return new_path;
}

/* ========================================================================
 * ORIGINAL FUNCTION POINTERS
 * ======================================================================== */

static int     (*original_execve)(const char *, char *const[], char *const[]) = NULL;
static int     (*original_open)(const char *, int, ...) = NULL;
static int     (*original_openat)(int, const char *, int, ...) = NULL;
static int     (*original_access)(const char *, int) = NULL;
static int     (*original_stat)(const char *, struct stat *) = NULL;
static int     (*original_lstat)(const char *, struct stat *) = NULL;
static DIR    *(*original_opendir)(const char *) = NULL;
static ssize_t (*original_readlink)(const char *, char *, size_t) = NULL;
static int     (*original_unlink)(const char *) = NULL;
static int     (*original_rename)(const char *, const char *) = NULL;
static int     (*original_mkdir)(const char *, mode_t) = NULL;
static int     (*original_chdir)(const char *) = NULL;
static int     (*original_chmod)(const char *, mode_t) = NULL;
static int     (*original_fchmodat)(int, const char *, mode_t, int) = NULL;
static int     (*original_chown)(const char *, uid_t, gid_t) = NULL;
static int     (*original_lchown)(const char *, uid_t, gid_t) = NULL;
static int     (*original_fstatat)(int, const char *, struct stat *, int) = NULL;
static int     (*original_symlink)(const char *, const char *) = NULL;
static int     (*original_link)(const char *, const char *) = NULL;
static FILE   *(*original_fopen)(const char *, const char *) = NULL;
static FILE   *(*original_freopen)(const char *, const char *, FILE *) = NULL;
static int     (*original_creat)(const char *, mode_t) = NULL;
static int     (*original_execv)(const char *, char *const[]) = NULL;
static int     (*original_execvp)(const char *, char *const[]) = NULL;
static char   *(*original_realpath)(const char *, char *) = NULL;
static char   *(*original_canonicalize_file_name)(const char *) = NULL;
static char   *(*original___realpath_chk)(const char *, char *, size_t) = NULL;

/* GROUP 1: "at" variants and single-path hooks */
static int     (*original_faccessat)(int, const char *, int, int) = NULL;
static int     (*original_rmdir)(const char *) = NULL;
static int     (*original_mkdirat)(int, const char *, mode_t) = NULL;
static int     (*original_unlinkat)(int, const char *, int) = NULL;
static ssize_t (*original_readlinkat)(int, const char *, char *, size_t) = NULL;
static int     (*original_fchownat)(int, const char *, uid_t, gid_t, int) = NULL;
static int     (*original_mknod)(const char *, mode_t, dev_t) = NULL;
static int     (*original_mknodat)(int, const char *, mode_t, dev_t) = NULL;
static int     (*original_mkfifo)(const char *, mode_t) = NULL;
static int     (*original_mkfifoat)(int, const char *, mode_t) = NULL;
static int     (*original_chroot)(const char *) = NULL;
static int     (*original_truncate)(const char *, off_t) = NULL;
static int     (*original_statfs)(const char *, struct statfs *) = NULL;
static int     (*original_statvfs)(const char *, struct statvfs *) = NULL;
static long    (*original_pathconf)(const char *, int) = NULL;
static int     (*original_utimes)(const char *, const struct timeval *) = NULL;
static int     (*original_lutimes)(const char *, const struct timeval *) = NULL;
static int     (*original_utimensat)(int, const char *, const struct timespec *, int) = NULL;
static int     (*original_inotify_add_watch)(int, const char *, uint32_t) = NULL;
static void   *(*original_dlopen)(const char *, int) = NULL;
static int     (*original_scandir)(const char *, struct dirent ***, int (*)(const struct dirent *), int (*)(const struct dirent **, const struct dirent **)) = NULL;
static char   *(*original_tempnam)(const char *, const char *) = NULL;

/* GROUP 2: Two-path hooks */
static int     (*original_linkat)(int, const char *, int, const char *, int) = NULL;
static int     (*original_symlinkat)(const char *, int, const char *) = NULL;
static int     (*original_renameat)(int, const char *, int, const char *) = NULL;
static int     (*original_renameat2)(int, const char *, int, const char *, unsigned int) = NULL;

/* GROUP 3: Extended attribute hooks */
static ssize_t (*original_getxattr)(const char *, const char *, void *, size_t) = NULL;
static ssize_t (*original_lgetxattr)(const char *, const char *, void *, size_t) = NULL;
static int     (*original_setxattr)(const char *, const char *, const void *, size_t, int) = NULL;
static int     (*original_lsetxattr)(const char *, const char *, const void *, size_t, int) = NULL;
static int     (*original_removexattr)(const char *, const char *) = NULL;
static int     (*original_lremovexattr)(const char *, const char *) = NULL;
static ssize_t (*original_listxattr)(const char *, char *, size_t) = NULL;
static ssize_t (*original_llistxattr)(const char *, char *, size_t) = NULL;

/* GROUP 4: Exec + W^X */
static int     (*original_execvpe)(const char *, char *const[], char *const[]) = NULL;

/* GROUP 5: Bionic fortified variants */
static int     (*original___open_2)(const char *, int) = NULL;
static int     (*original___openat_2)(int, const char *, int) = NULL;

/**
 * Initialize all original function pointers via dlsym(RTLD_NEXT, ...).
 * Safe to call multiple times; each pointer is only resolved once.
 */
static void init_originals(void) {
    if (!original_execve)
        original_execve = dlsym(RTLD_NEXT, "execve");
    if (!original_open)
        original_open = dlsym(RTLD_NEXT, "open");
    if (!original_openat)
        original_openat = dlsym(RTLD_NEXT, "openat");
    if (!original_access)
        original_access = dlsym(RTLD_NEXT, "access");
    if (!original_stat)
        original_stat = dlsym(RTLD_NEXT, "stat");
    if (!original_lstat)
        original_lstat = dlsym(RTLD_NEXT, "lstat");
    if (!original_opendir)
        original_opendir = dlsym(RTLD_NEXT, "opendir");
    if (!original_readlink)
        original_readlink = dlsym(RTLD_NEXT, "readlink");
    if (!original_unlink)
        original_unlink = dlsym(RTLD_NEXT, "unlink");
    if (!original_rename)
        original_rename = dlsym(RTLD_NEXT, "rename");
    if (!original_mkdir)
        original_mkdir = dlsym(RTLD_NEXT, "mkdir");
    if (!original_chdir)
        original_chdir = dlsym(RTLD_NEXT, "chdir");
    if (!original_chmod)
        original_chmod = dlsym(RTLD_NEXT, "chmod");
    if (!original_fchmodat)
        original_fchmodat = dlsym(RTLD_NEXT, "fchmodat");
    if (!original_chown)
        original_chown = dlsym(RTLD_NEXT, "chown");
    if (!original_lchown)
        original_lchown = dlsym(RTLD_NEXT, "lchown");
    if (!original_fstatat)
        original_fstatat = dlsym(RTLD_NEXT, "fstatat");
    if (!original_symlink)
        original_symlink = dlsym(RTLD_NEXT, "symlink");
    if (!original_link)
        original_link = dlsym(RTLD_NEXT, "link");
    if (!original_fopen)
        original_fopen = dlsym(RTLD_NEXT, "fopen");
    if (!original_freopen)
        original_freopen = dlsym(RTLD_NEXT, "freopen");
    if (!original_creat)
        original_creat = dlsym(RTLD_NEXT, "creat");
    if (!original_execv)
        original_execv = dlsym(RTLD_NEXT, "execv");
    if (!original_execvp)
        original_execvp = dlsym(RTLD_NEXT, "execvp");
    if (!original_realpath)
        original_realpath = dlsym(RTLD_NEXT, "realpath");
    if (!original_canonicalize_file_name)
        original_canonicalize_file_name = dlsym(RTLD_NEXT, "canonicalize_file_name");
    if (!original___realpath_chk)
        original___realpath_chk = dlsym(RTLD_NEXT, "__realpath_chk");

    /* GROUP 1: "at" variants and single-path hooks */
    if (!original_faccessat)
        original_faccessat = dlsym(RTLD_NEXT, "faccessat");
    if (!original_rmdir)
        original_rmdir = dlsym(RTLD_NEXT, "rmdir");
    if (!original_mkdirat)
        original_mkdirat = dlsym(RTLD_NEXT, "mkdirat");
    if (!original_unlinkat)
        original_unlinkat = dlsym(RTLD_NEXT, "unlinkat");
    if (!original_readlinkat)
        original_readlinkat = dlsym(RTLD_NEXT, "readlinkat");
    if (!original_fchownat)
        original_fchownat = dlsym(RTLD_NEXT, "fchownat");
    if (!original_mknod)
        original_mknod = dlsym(RTLD_NEXT, "mknod");
    if (!original_mknodat)
        original_mknodat = dlsym(RTLD_NEXT, "mknodat");
    if (!original_mkfifo)
        original_mkfifo = dlsym(RTLD_NEXT, "mkfifo");
    if (!original_mkfifoat)
        original_mkfifoat = dlsym(RTLD_NEXT, "mkfifoat");
    if (!original_chroot)
        original_chroot = dlsym(RTLD_NEXT, "chroot");
    if (!original_truncate)
        original_truncate = dlsym(RTLD_NEXT, "truncate");
    if (!original_statfs)
        original_statfs = dlsym(RTLD_NEXT, "statfs");
    if (!original_statvfs)
        original_statvfs = dlsym(RTLD_NEXT, "statvfs");
    if (!original_pathconf)
        original_pathconf = dlsym(RTLD_NEXT, "pathconf");
    if (!original_utimes)
        original_utimes = dlsym(RTLD_NEXT, "utimes");
    if (!original_lutimes)
        original_lutimes = dlsym(RTLD_NEXT, "lutimes");
    if (!original_utimensat)
        original_utimensat = dlsym(RTLD_NEXT, "utimensat");
    if (!original_inotify_add_watch)
        original_inotify_add_watch = dlsym(RTLD_NEXT, "inotify_add_watch");
    if (!original_dlopen)
        original_dlopen = dlsym(RTLD_NEXT, "dlopen");
    if (!original_scandir)
        original_scandir = dlsym(RTLD_NEXT, "scandir");
    if (!original_tempnam)
        original_tempnam = dlsym(RTLD_NEXT, "tempnam");

    /* GROUP 2: Two-path hooks */
    if (!original_linkat)
        original_linkat = dlsym(RTLD_NEXT, "linkat");
    if (!original_symlinkat)
        original_symlinkat = dlsym(RTLD_NEXT, "symlinkat");
    if (!original_renameat)
        original_renameat = dlsym(RTLD_NEXT, "renameat");
    if (!original_renameat2)
        original_renameat2 = dlsym(RTLD_NEXT, "renameat2");

    /* GROUP 3: Extended attribute hooks */
    if (!original_getxattr)
        original_getxattr = dlsym(RTLD_NEXT, "getxattr");
    if (!original_lgetxattr)
        original_lgetxattr = dlsym(RTLD_NEXT, "lgetxattr");
    if (!original_setxattr)
        original_setxattr = dlsym(RTLD_NEXT, "setxattr");
    if (!original_lsetxattr)
        original_lsetxattr = dlsym(RTLD_NEXT, "lsetxattr");
    if (!original_removexattr)
        original_removexattr = dlsym(RTLD_NEXT, "removexattr");
    if (!original_lremovexattr)
        original_lremovexattr = dlsym(RTLD_NEXT, "lremovexattr");
    if (!original_listxattr)
        original_listxattr = dlsym(RTLD_NEXT, "listxattr");
    if (!original_llistxattr)
        original_llistxattr = dlsym(RTLD_NEXT, "llistxattr");

    /* GROUP 4: Exec + W^X */
    if (!original_execvpe)
        original_execvpe = dlsym(RTLD_NEXT, "execvpe");

    /* GROUP 5: Bionic fortified variants */
    if (!original___open_2)
        original___open_2 = dlsym(RTLD_NEXT, "__open_2");
    if (!original___openat_2)
        original___openat_2 = dlsym(RTLD_NEXT, "__openat_2");
}

/* ========================================================================
 * EXECVE HELPERS (W^X bypass via linker64)
 * ======================================================================== */

/**
 * Check if the file at 'path' starts with ELF magic bytes (0x7F 'E' 'L' 'F').
 * Returns 1 if ELF, 0 otherwise.
 *
 * Uses original_open to avoid recursion through our intercepted open().
 */
static int is_elf(const char *path) {
    unsigned char buf[4];
    init_originals();
    int fd = original_open(path, O_RDONLY);
    if (fd < 0) return 0;
    int n = read(fd, buf, 4);
    close(fd);
    return (n == 4 && buf[0] == 0x7f && buf[1] == 'E' && buf[2] == 'L' && buf[3] == 'F');
}

/**
 * Check if the file starts with "#!" (shebang).
 * If so, parse the interpreter path into 'interp_buf' (up to buf_size) and
 * optionally the first argument into 'arg_buf'.
 * Returns 1 if shebang found, 0 otherwise.
 *
 * Uses original_open to avoid recursion through our intercepted open().
 */
static int parse_shebang(const char *path, char *interp_buf, size_t buf_size,
                         char *arg_buf, size_t arg_buf_size) {
    char line[SHEBANG_MAX];
    init_originals();
    int fd = original_open(path, O_RDONLY);
    if (fd < 0) return 0;
    int n = read(fd, line, sizeof(line) - 1);
    close(fd);
    if (n < 3) return 0;
    line[n] = '\0';

    /* Must start with #! */
    if (line[0] != '#' || line[1] != '!') return 0;

    /* Terminate at first newline */
    char *nl = strchr(line, '\n');
    if (nl) *nl = '\0';

    /* Skip #! and leading whitespace */
    char *p = line + 2;
    while (*p == ' ' || *p == '\t') p++;
    if (*p == '\0') return 0;

    /* Extract interpreter path */
    char *start = p;
    while (*p && *p != ' ' && *p != '\t') p++;

    size_t len = (size_t)(p - start);
    if (len >= buf_size) len = buf_size - 1;
    memcpy(interp_buf, start, len);
    interp_buf[len] = '\0';

    /* Extract optional argument */
    if (arg_buf && arg_buf_size > 0) {
        arg_buf[0] = '\0';
        while (*p == ' ' || *p == '\t') p++;
        if (*p) {
            /* Take everything until end of line as the argument */
            char *arg_start = p;
            /* Trim trailing whitespace */
            char *end = p + strlen(p) - 1;
            while (end > arg_start && (*end == ' ' || *end == '\t')) end--;
            size_t alen = (size_t)(end - arg_start + 1);
            if (alen >= arg_buf_size) alen = arg_buf_size - 1;
            memcpy(arg_buf, arg_start, alen);
            arg_buf[alen] = '\0';
        }
    }

    return 1;
}

/**
 * Build a new argv array that routes execution through the system linker.
 *
 * For a direct ELF binary:
 *   [linker64, original_path, original_args...] -> execve(linker64, ...)
 *
 * For a shebang script whose interpreter is in /data/:
 *   [linker64, interpreter, [shebang_arg], script_path, original_args...]
 *
 * Returns the new argv (caller must free), or NULL on allocation failure.
 * Sets *out_count to the number of entries (excluding terminal NULL).
 */
static char **build_linker_argv(const char *exec_path, const char *interp,
                                const char *shebang_arg, const char *script_path,
                                char *const argv[], int *out_count) {
    int orig_argc = 0;
    if (argv) {
        while (argv[orig_argc]) orig_argc++;
    }

    if (interp) {
        /* Shebang case: linker64 interpreter [shebang_arg] script_path original_args[1:]... */
        int has_arg = (shebang_arg && shebang_arg[0] != '\0') ? 1 : 0;
        int new_argc = 1 /* linker64 */ + 1 /* interpreter */ + has_arg +
                        1 /* script_path */ + (orig_argc > 1 ? orig_argc - 1 : 0);
        char **new_argv = malloc((new_argc + 1) * sizeof(char *));
        if (!new_argv) return NULL;

        int idx = 0;
        new_argv[idx++] = LINKER_PATH;
        new_argv[idx++] = (char *)interp;
        if (has_arg) {
            new_argv[idx++] = (char *)shebang_arg;
        }
        new_argv[idx++] = (char *)script_path;
        /* Copy original args after argv[0] */
        for (int i = 1; i < orig_argc; i++) {
            new_argv[idx++] = argv[i];
        }
        new_argv[idx] = NULL;
        *out_count = idx;
        return new_argv;
    } else {
        /* Direct ELF case: linker64 original_path original_args[1:]... */
        int new_argc = 1 /* linker64 */ + 1 /* path */ + (orig_argc > 1 ? orig_argc - 1 : 0);
        char **new_argv = malloc((new_argc + 1) * sizeof(char *));
        if (!new_argv) return NULL;

        int idx = 0;
        new_argv[idx++] = LINKER_PATH;
        new_argv[idx++] = (char *)exec_path;
        for (int i = 1; i < orig_argc; i++) {
            new_argv[idx++] = argv[i];
        }
        new_argv[idx] = NULL;
        *out_count = idx;
        return new_argv;
    }
}

/* ========================================================================
 * INTERCEPTED FUNCTIONS
 * ======================================================================== */

/**
 * Intercepted execve().
 *
 * First rewrites the path from com.termux to com.mobilekinetic.agent if needed.
 * Then, if the target binary is inside /data/ and is an ELF, route through linker64.
 * If it's a script with a shebang whose interpreter is in /data/, route the
 * interpreter through linker64.
 * Otherwise, pass through to the real execve unchanged.
 */
__attribute__((visibility("default")))
int execve(const char *path, char *const argv[], char *const envp[]) {
    init_originals();

    /* Safety: if we can't find the real execve, fail loudly */
    if (!original_execve) {
        errno = ENOSYS;
        return -1;
    }

    /* Null path: let the kernel return the appropriate error */
    if (!path) {
        return original_execve(path, argv, envp);
    }

    /* Rewrite com.termux paths to com.mobilekinetic.agent, then try Unix path rewriting */
    char *rewritten = rewrite_path(path);
    if (!rewritten) rewritten = rewrite_unix_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    /* Only intercept paths within the data directory */
    if (strncmp(actual_path, DATA_PREFIX, DATA_PREFIX_LEN) != 0) {
        int result = original_execve(actual_path, argv, envp);
        int saved_errno = errno;
        free(rewritten);
        errno = saved_errno;
        return result;
    }

    /* Check if it's an ELF binary */
    if (is_elf(actual_path)) {
        int count;
        char **new_argv = build_linker_argv(actual_path, NULL, NULL, NULL, argv, &count);
        if (!new_argv) {
            free(rewritten);
            errno = ENOMEM;
            return -1;
        }

        int result = original_execve(LINKER_PATH, new_argv, envp);
        int saved_errno = errno;
        free(new_argv);
        free(rewritten);
        errno = saved_errno;
        return result;
    }

    /* Check for shebang scripts */
    char interp[SHEBANG_MAX];
    char shebang_arg[SHEBANG_MAX];
    if (parse_shebang(actual_path, interp, sizeof(interp), shebang_arg, sizeof(shebang_arg))) {
        /* Rewrite the interpreter path too if needed */
        char *rewritten_interp = rewrite_path(interp);
        if (!rewritten_interp) rewritten_interp = rewrite_unix_path(interp);
        const char *actual_interp = rewritten_interp ? rewritten_interp : interp;

        /* If the interpreter is in the data dir and is an ELF, route through linker */
        if (strncmp(actual_interp, DATA_PREFIX, DATA_PREFIX_LEN) == 0 && is_elf(actual_interp)) {
            int count;
            char **new_argv = build_linker_argv(actual_path, actual_interp, shebang_arg,
                                                actual_path, argv, &count);
            if (!new_argv) {
                free(rewritten_interp);
                free(rewritten);
                errno = ENOMEM;
                return -1;
            }

            int result = original_execve(LINKER_PATH, new_argv, envp);
            int saved_errno = errno;
            free(new_argv);
            free(rewritten_interp);
            free(rewritten);
            errno = saved_errno;
            return result;
        }
        free(rewritten_interp);
        /* Interpreter not in data dir -- fall through to normal execve */
    }

    /* Not an ELF in data dir, not a shebang needing interception: pass through */
    int result = original_execve(actual_path, argv, envp);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted open().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real open().
 */
__attribute__((visibility("default")))
int open(const char *path, int flags, ...) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    /* Extract the optional mode argument (required when O_CREAT is set) */
    mode_t mode = 0;
    if (flags & (O_CREAT
#ifdef O_TMPFILE
                 | O_TMPFILE
#endif
                )) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);  /* mode_t is promoted to int in varargs */
        va_end(args);
    }

    int result = original_open(actual_path, flags, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted openat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real openat().
 * Note: path rewriting only applies to absolute paths (starting with /).
 * Relative paths are resolved against dirfd, so they pass through unchanged.
 */
__attribute__((visibility("default")))
int openat(int dirfd, const char *path, int flags, ...) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    mode_t mode = 0;
    if (flags & (O_CREAT
#ifdef O_TMPFILE
                 | O_TMPFILE
#endif
                )) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }

    int result = original_openat(dirfd, actual_path, flags, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted access().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real access().
 */
__attribute__((visibility("default")))
int access(const char *path, int mode) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_access(actual_path, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted stat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real stat().
 */
__attribute__((visibility("default")))
int stat(const char *path, struct stat *buf) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_stat(actual_path, buf);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted lstat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real lstat().
 */
__attribute__((visibility("default")))
int lstat(const char *path, struct stat *buf) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_lstat(actual_path, buf);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted opendir().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real opendir().
 */
__attribute__((visibility("default")))
DIR *opendir(const char *path) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    DIR *result = original_opendir(actual_path);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted readlink().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real readlink().
 */
__attribute__((visibility("default")))
ssize_t readlink(const char *path, char *buf, size_t bufsiz) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    ssize_t result = original_readlink(actual_path, buf, bufsiz);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted unlink().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real unlink().
 */
__attribute__((visibility("default")))
int unlink(const char *path) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_unlink(actual_path);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted rename().
 * Rewrites BOTH old and new paths from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
int rename(const char *old_path, const char *new_path) {
    init_originals();

    char *rewritten_old = rewrite_path(old_path);
    char *rewritten_new = rewrite_path(new_path);
    const char *actual_old = rewritten_old ? rewritten_old : old_path;
    const char *actual_new = rewritten_new ? rewritten_new : new_path;

    int result = original_rename(actual_old, actual_new);
    int saved_errno = errno;
    free(rewritten_old);
    free(rewritten_new);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted mkdir().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real mkdir().
 */
__attribute__((visibility("default")))
int mkdir(const char *path, mode_t mode) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_mkdir(actual_path, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted chdir().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real chdir().
 */
__attribute__((visibility("default")))
int chdir(const char *path) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_chdir(actual_path);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted chmod().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real chmod().
 */
__attribute__((visibility("default")))
int chmod(const char *path, mode_t mode) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_chmod(actual_path, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted fchmodat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real fchmodat().
 * Note: path rewriting only applies to absolute paths (starting with /).
 * Relative paths are resolved against dirfd, so they pass through unchanged.
 */
__attribute__((visibility("default")))
int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_fchmodat(dirfd, actual_path, mode, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted chown().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real chown().
 */
__attribute__((visibility("default")))
int chown(const char *path, uid_t owner, gid_t group) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_chown(actual_path, owner, group);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted lchown().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real lchown().
 * Unlike chown(), lchown() does not follow symlinks.
 */
__attribute__((visibility("default")))
int lchown(const char *path, uid_t owner, gid_t group) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_lchown(actual_path, owner, group);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted fstatat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real fstatat().
 * Note: path rewriting only applies to absolute paths (starting with /).
 * Relative paths are resolved against dirfd, so they pass through unchanged.
 */
__attribute__((visibility("default")))
int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_fstatat(dirfd, actual_path, statbuf, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted symlink().
 * Rewrites BOTH target and linkpath from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
int symlink(const char *target, const char *linkpath) {
    init_originals();

    char *rewritten_target = rewrite_path(target);
    char *rewritten_linkpath = rewrite_path(linkpath);
    const char *actual_target = rewritten_target ? rewritten_target : target;
    const char *actual_linkpath = rewritten_linkpath ? rewritten_linkpath : linkpath;

    int result = original_symlink(actual_target, actual_linkpath);
    int saved_errno = errno;
    free(rewritten_target);
    free(rewritten_linkpath);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted link().
 * Rewrites BOTH oldpath and newpath from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
int link(const char *oldpath, const char *newpath) {
    init_originals();

    char *rewritten_old = rewrite_path(oldpath);
    char *rewritten_new = rewrite_path(newpath);
    const char *actual_old = rewritten_old ? rewritten_old : oldpath;
    const char *actual_new = rewritten_new ? rewritten_new : newpath;

    int result = original_link(actual_old, actual_new);
    int saved_errno = errno;
    free(rewritten_old);
    free(rewritten_new);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted fopen().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real fopen().
 */
__attribute__((visibility("default")))
FILE *fopen(const char *pathname, const char *mode) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    FILE *result = original_fopen(actual_path, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted freopen().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real freopen().
 */
__attribute__((visibility("default")))
FILE *freopen(const char *pathname, const char *mode, FILE *stream) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    FILE *result = original_freopen(actual_path, mode, stream);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted creat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real creat().
 * creat(path, mode) is equivalent to open(path, O_CREAT|O_WRONLY|O_TRUNC, mode).
 */
__attribute__((visibility("default")))
int creat(const char *pathname, mode_t mode) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_creat(actual_path, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted execv().
 *
 * First rewrites the path from com.termux to com.mobilekinetic.agent if needed.
 * Then, if the target binary is inside /data/ and is an ELF, route through linker64.
 * If it's a script with a shebang whose interpreter is in /data/, route the
 * interpreter through linker64.
 * Otherwise, pass through to the real execv unchanged.
 *
 * Note: execv() uses the current environment (environ) implicitly.
 */
__attribute__((visibility("default")))
int execv(const char *pathname, char *const argv[]) {
    init_originals();

    /* Safety: if we can't find the real execv, fail loudly */
    if (!original_execv) {
        errno = ENOSYS;
        return -1;
    }

    /* Null path: let the kernel return the appropriate error */
    if (!pathname) {
        return original_execv(pathname, argv);
    }

    /* Rewrite com.termux paths to com.mobilekinetic.agent, then try Unix path rewriting */
    char *rewritten = rewrite_path(pathname);
    if (!rewritten) rewritten = rewrite_unix_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    /* Only intercept paths within the data directory */
    if (strncmp(actual_path, DATA_PREFIX, DATA_PREFIX_LEN) != 0) {
        int result = original_execv(actual_path, argv);
        int saved_errno = errno;
        free(rewritten);
        errno = saved_errno;
        return result;
    }

    /* Check if it's an ELF binary */
    if (is_elf(actual_path)) {
        int count;
        char **new_argv = build_linker_argv(actual_path, NULL, NULL, NULL, argv, &count);
        if (!new_argv) {
            free(rewritten);
            errno = ENOMEM;
            return -1;
        }

        int result = original_execve(LINKER_PATH, new_argv, environ);
        int saved_errno = errno;
        free(new_argv);
        free(rewritten);
        errno = saved_errno;
        return result;
    }

    /* Check for shebang scripts */
    char interp[SHEBANG_MAX];
    char shebang_arg[SHEBANG_MAX];
    if (parse_shebang(actual_path, interp, sizeof(interp), shebang_arg, sizeof(shebang_arg))) {
        /* Rewrite the interpreter path too if needed */
        char *rewritten_interp = rewrite_path(interp);
        if (!rewritten_interp) rewritten_interp = rewrite_unix_path(interp);
        const char *actual_interp = rewritten_interp ? rewritten_interp : interp;

        /* If the interpreter is in the data dir and is an ELF, route through linker */
        if (strncmp(actual_interp, DATA_PREFIX, DATA_PREFIX_LEN) == 0 && is_elf(actual_interp)) {
            int count;
            char **new_argv = build_linker_argv(actual_path, actual_interp, shebang_arg,
                                                actual_path, argv, &count);
            if (!new_argv) {
                free(rewritten_interp);
                free(rewritten);
                errno = ENOMEM;
                return -1;
            }

            int result = original_execve(LINKER_PATH, new_argv, environ);
            int saved_errno = errno;
            free(new_argv);
            free(rewritten_interp);
            free(rewritten);
            errno = saved_errno;
            return result;
        }
        free(rewritten_interp);
        /* Interpreter not in data dir -- fall through to normal execv */
    }

    /* Not an ELF in data dir, not a shebang needing interception: pass through */
    int result = original_execv(actual_path, argv);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted execvp().
 *
 * First rewrites the path from com.termux to com.mobilekinetic.agent if needed.
 * Then, if the target binary is inside /data/ and is an ELF, route through linker64.
 * If it's a script with a shebang whose interpreter is in /data/, route the
 * interpreter through linker64.
 * Otherwise, pass through to the real execvp unchanged.
 *
 * Note: execvp() searches PATH for the file if it doesn't contain a slash.
 * Path rewriting only applies to absolute paths (starting with /), so relative
 * names (like "ls") will pass through to the real execvp for PATH resolution.
 */
__attribute__((visibility("default")))
int execvp(const char *file, char *const argv[]) {
    init_originals();

    /* Safety: if we can't find the real execvp, fail loudly */
    if (!original_execvp) {
        errno = ENOSYS;
        return -1;
    }

    /* Null file: let the kernel return the appropriate error */
    if (!file) {
        return original_execvp(file, argv);
    }

    /* Rewrite com.termux paths to com.mobilekinetic.agent, then try Unix path rewriting */
    char *rewritten = rewrite_path(file);
    if (!rewritten) rewritten = rewrite_unix_path(file);
    const char *actual_path = rewritten ? rewritten : file;

    /* Only intercept absolute paths within the data directory */
    if (strncmp(actual_path, DATA_PREFIX, DATA_PREFIX_LEN) != 0) {
        int result = original_execvp(actual_path, argv);
        int saved_errno = errno;
        free(rewritten);
        errno = saved_errno;
        return result;
    }

    /* Check if it's an ELF binary */
    if (is_elf(actual_path)) {
        int count;
        char **new_argv = build_linker_argv(actual_path, NULL, NULL, NULL, argv, &count);
        if (!new_argv) {
            free(rewritten);
            errno = ENOMEM;
            return -1;
        }

        int result = original_execve(LINKER_PATH, new_argv, environ);
        int saved_errno = errno;
        free(new_argv);
        free(rewritten);
        errno = saved_errno;
        return result;
    }

    /* Check for shebang scripts */
    char interp[SHEBANG_MAX];
    char shebang_arg[SHEBANG_MAX];
    if (parse_shebang(actual_path, interp, sizeof(interp), shebang_arg, sizeof(shebang_arg))) {
        /* Rewrite the interpreter path too if needed */
        char *rewritten_interp = rewrite_path(interp);
        if (!rewritten_interp) rewritten_interp = rewrite_unix_path(interp);
        const char *actual_interp = rewritten_interp ? rewritten_interp : interp;

        /* If the interpreter is in the data dir and is an ELF, route through linker */
        if (strncmp(actual_interp, DATA_PREFIX, DATA_PREFIX_LEN) == 0 && is_elf(actual_interp)) {
            int count;
            char **new_argv = build_linker_argv(actual_path, actual_interp, shebang_arg,
                                                actual_path, argv, &count);
            if (!new_argv) {
                free(rewritten_interp);
                free(rewritten);
                errno = ENOMEM;
                return -1;
            }

            int result = original_execve(LINKER_PATH, new_argv, environ);
            int saved_errno = errno;
            free(new_argv);
            free(rewritten_interp);
            free(rewritten);
            errno = saved_errno;
            return result;
        }
        free(rewritten_interp);
        /* Interpreter not in data dir -- fall through to normal execvp */
    }

    /* Not an ELF in data dir, not a shebang needing interception: pass through */
    int result = original_execvp(actual_path, argv);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted realpath().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real realpath().
 */
__attribute__((visibility("default")))
char *realpath(const char *path, char *resolved_path) {
    init_originals();

    char *new_path = rewrite_path(path);
    char *result = original_realpath(new_path ? new_path : path, resolved_path);
    int saved_errno = errno;
    free(new_path);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted canonicalize_file_name().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real
 * canonicalize_file_name(). This is a GNU extension equivalent to
 * realpath(path, NULL).
 */
__attribute__((visibility("default")))
char *canonicalize_file_name(const char *path) {
    init_originals();

    char *new_path = rewrite_path(path);
    char *result = original_canonicalize_file_name(new_path ? new_path : path);
    int saved_errno = errno;
    free(new_path);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted __realpath_chk().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real
 * __realpath_chk(). This is the Bionic fortified variant of realpath().
 */
__attribute__((visibility("default")))
char *__realpath_chk(const char *path, char *resolved_path, size_t resolved_len) {
    init_originals();

    char *new_path = rewrite_path(path);
    char *result = original___realpath_chk(new_path ? new_path : path, resolved_path, resolved_len);
    int saved_errno = errno;
    free(new_path);
    errno = saved_errno;
    return result;
}

/* ========================================================================
 * GROUP 1: "at" VARIANTS AND SINGLE-PATH HOOKS
 * ======================================================================== */

/**
 * Intercepted faccessat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real faccessat().
 */
__attribute__((visibility("default")))
int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_faccessat(dirfd, actual_path, mode, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted rmdir().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real rmdir().
 */
__attribute__((visibility("default")))
int rmdir(const char *pathname) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_rmdir(actual_path);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted mkdirat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real mkdirat().
 */
__attribute__((visibility("default")))
int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_mkdirat(dirfd, actual_path, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted unlinkat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real unlinkat().
 */
__attribute__((visibility("default")))
int unlinkat(int dirfd, const char *pathname, int flags) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_unlinkat(dirfd, actual_path, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted readlinkat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real readlinkat().
 */
__attribute__((visibility("default")))
ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    ssize_t result = original_readlinkat(dirfd, actual_path, buf, bufsiz);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted fchownat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real fchownat().
 */
__attribute__((visibility("default")))
int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_fchownat(dirfd, actual_path, owner, group, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted mknod().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real mknod().
 */
__attribute__((visibility("default")))
int mknod(const char *pathname, mode_t mode, dev_t dev) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_mknod(actual_path, mode, dev);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted mknodat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real mknodat().
 */
__attribute__((visibility("default")))
int mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_mknodat(dirfd, actual_path, mode, dev);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted mkfifo().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real mkfifo().
 */
__attribute__((visibility("default")))
int mkfifo(const char *pathname, mode_t mode) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_mkfifo(actual_path, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted mkfifoat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real mkfifoat().
 */
__attribute__((visibility("default")))
int mkfifoat(int dirfd, const char *pathname, mode_t mode) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_mkfifoat(dirfd, actual_path, mode);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted chroot().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real chroot().
 */
__attribute__((visibility("default")))
int chroot(const char *path) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_chroot(actual_path);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted truncate().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real truncate().
 */
__attribute__((visibility("default")))
int truncate(const char *path, off_t length) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_truncate(actual_path, length);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted statfs().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real statfs().
 */
__attribute__((visibility("default")))
int statfs(const char *path, struct statfs *buf) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_statfs(actual_path, buf);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted statvfs().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real statvfs().
 */
__attribute__((visibility("default")))
int statvfs(const char *path, struct statvfs *buf) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_statvfs(actual_path, buf);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted pathconf().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real pathconf().
 */
__attribute__((visibility("default")))
long pathconf(const char *path, int name) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    long result = original_pathconf(actual_path, name);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted utimes().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real utimes().
 */
__attribute__((visibility("default")))
int utimes(const char *filename, const struct timeval times[2]) {
    init_originals();

    char *rewritten = rewrite_path(filename);
    const char *actual_path = rewritten ? rewritten : filename;

    int result = original_utimes(actual_path, times);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted lutimes().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real lutimes().
 * Unlike utimes(), lutimes() does not follow symlinks.
 */
__attribute__((visibility("default")))
int lutimes(const char *filename, const struct timeval tv[2]) {
    init_originals();

    char *rewritten = rewrite_path(filename);
    const char *actual_path = rewritten ? rewritten : filename;

    int result = original_lutimes(actual_path, tv);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted utimensat().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real utimensat().
 */
__attribute__((visibility("default")))
int utimensat(int dirfd, const char *pathname, const struct timespec times[2], int flags) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_utimensat(dirfd, actual_path, times, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted inotify_add_watch().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real inotify_add_watch().
 */
__attribute__((visibility("default")))
int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original_inotify_add_watch(fd, actual_path, mask);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted dlopen().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real dlopen().
 */
__attribute__((visibility("default")))
void *dlopen(const char *filename, int flags) {
    init_originals();

    char *rewritten = rewrite_path(filename);
    const char *actual_path = rewritten ? rewritten : filename;

    void *result = original_dlopen(actual_path, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted scandir().
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real scandir().
 */
__attribute__((visibility("default")))
int scandir(const char *dirp, struct dirent ***namelist,
            int (*filter)(const struct dirent *),
            int (*compar)(const struct dirent **, const struct dirent **)) {
    init_originals();

    char *rewritten = rewrite_path(dirp);
    const char *actual_path = rewritten ? rewritten : dirp;

    int result = original_scandir(actual_path, namelist, filter, compar);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted tempnam().
 * Rewrites the dir argument (first arg only) from com.termux to com.mobilekinetic.agent
 * before calling the real tempnam().
 */
__attribute__((visibility("default")))
char *tempnam(const char *dir, const char *pfx) {
    init_originals();

    char *rewritten = rewrite_path(dir);
    const char *actual_dir = rewritten ? rewritten : dir;

    char *result = original_tempnam(actual_dir, pfx);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/* ========================================================================
 * GROUP 2: TWO-PATH HOOKS
 * ======================================================================== */

/**
 * Intercepted linkat().
 * Rewrites BOTH oldpath and newpath from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    init_originals();

    char *rewritten_old = rewrite_path(oldpath);
    char *rewritten_new = rewrite_path(newpath);
    const char *actual_old = rewritten_old ? rewritten_old : oldpath;
    const char *actual_new = rewritten_new ? rewritten_new : newpath;

    int result = original_linkat(olddirfd, actual_old, newdirfd, actual_new, flags);
    int saved_errno = errno;
    free(rewritten_old);
    free(rewritten_new);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted symlinkat().
 * Rewrites BOTH target and linkpath from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
int symlinkat(const char *target, int newdirfd, const char *linkpath) {
    init_originals();

    char *rewritten_target = rewrite_path(target);
    char *rewritten_linkpath = rewrite_path(linkpath);
    const char *actual_target = rewritten_target ? rewritten_target : target;
    const char *actual_linkpath = rewritten_linkpath ? rewritten_linkpath : linkpath;

    int result = original_symlinkat(actual_target, newdirfd, actual_linkpath);
    int saved_errno = errno;
    free(rewritten_target);
    free(rewritten_linkpath);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted renameat().
 * Rewrites BOTH oldpath and newpath from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    init_originals();

    char *rewritten_old = rewrite_path(oldpath);
    char *rewritten_new = rewrite_path(newpath);
    const char *actual_old = rewritten_old ? rewritten_old : oldpath;
    const char *actual_new = rewritten_new ? rewritten_new : newpath;

    int result = original_renameat(olddirfd, actual_old, newdirfd, actual_new);
    int saved_errno = errno;
    free(rewritten_old);
    free(rewritten_new);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted renameat2().
 * Rewrites BOTH oldpath and newpath from com.termux to com.mobilekinetic.agent.
 * renameat2() adds a flags parameter (e.g., RENAME_NOREPLACE, RENAME_EXCHANGE).
 */
__attribute__((visibility("default")))
int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, unsigned int flags) {
    init_originals();

    char *rewritten_old = rewrite_path(oldpath);
    char *rewritten_new = rewrite_path(newpath);
    const char *actual_old = rewritten_old ? rewritten_old : oldpath;
    const char *actual_new = rewritten_new ? rewritten_new : newpath;

    int result = original_renameat2(olddirfd, actual_old, newdirfd, actual_new, flags);
    int saved_errno = errno;
    free(rewritten_old);
    free(rewritten_new);
    errno = saved_errno;
    return result;
}

/* ========================================================================
 * GROUP 3: EXTENDED ATTRIBUTE HOOKS
 * ======================================================================== */

/**
 * Intercepted getxattr().
 * Rewrites the path argument from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
ssize_t getxattr(const char *path, const char *name, void *value, size_t size) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    ssize_t result = original_getxattr(actual_path, name, value, size);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted lgetxattr().
 * Rewrites the path argument from com.termux to com.mobilekinetic.agent.
 * Unlike getxattr(), lgetxattr() does not follow symlinks.
 */
__attribute__((visibility("default")))
ssize_t lgetxattr(const char *path, const char *name, void *value, size_t size) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    ssize_t result = original_lgetxattr(actual_path, name, value, size);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted setxattr().
 * Rewrites the path argument from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
int setxattr(const char *path, const char *name, const void *value, size_t size, int flags) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_setxattr(actual_path, name, value, size, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted lsetxattr().
 * Rewrites the path argument from com.termux to com.mobilekinetic.agent.
 * Unlike setxattr(), lsetxattr() does not follow symlinks.
 */
__attribute__((visibility("default")))
int lsetxattr(const char *path, const char *name, const void *value, size_t size, int flags) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_lsetxattr(actual_path, name, value, size, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted removexattr().
 * Rewrites the path argument from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
int removexattr(const char *path, const char *name) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_removexattr(actual_path, name);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted lremovexattr().
 * Rewrites the path argument from com.termux to com.mobilekinetic.agent.
 * Unlike removexattr(), lremovexattr() does not follow symlinks.
 */
__attribute__((visibility("default")))
int lremovexattr(const char *path, const char *name) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    int result = original_lremovexattr(actual_path, name);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted listxattr().
 * Rewrites the path argument from com.termux to com.mobilekinetic.agent.
 */
__attribute__((visibility("default")))
ssize_t listxattr(const char *path, char *list, size_t size) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    ssize_t result = original_listxattr(actual_path, list, size);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted llistxattr().
 * Rewrites the path argument from com.termux to com.mobilekinetic.agent.
 * Unlike listxattr(), llistxattr() does not follow symlinks.
 */
__attribute__((visibility("default")))
ssize_t llistxattr(const char *path, char *list, size_t size) {
    init_originals();

    char *rewritten = rewrite_path(path);
    const char *actual_path = rewritten ? rewritten : path;

    ssize_t result = original_llistxattr(actual_path, list, size);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/* ========================================================================
 * GROUP 4: EXEC + W^X (execvpe)
 * ======================================================================== */

/**
 * Intercepted execvpe().
 *
 * First rewrites the path from com.termux to com.mobilekinetic.agent if needed.
 * Then, if the target binary is inside /data/ and is an ELF, route through linker64.
 * If it's a script with a shebang whose interpreter is in /data/, route the
 * interpreter through linker64.
 * Otherwise, pass through to the real execvpe unchanged.
 *
 * Note: execvpe() searches PATH for the file if it doesn't contain a slash,
 * and uses the provided envp[] (like execve, unlike execvp which uses environ).
 */
__attribute__((visibility("default")))
int execvpe(const char *file, char *const argv[], char *const envp[]) {
    init_originals();

    /* Safety: if we can't find the real execvpe, fail loudly */
    if (!original_execvpe) {
        errno = ENOSYS;
        return -1;
    }

    /* Null file: let the kernel return the appropriate error */
    if (!file) {
        return original_execvpe(file, argv, envp);
    }

    /* Rewrite com.termux paths to com.mobilekinetic.agent, then try Unix path rewriting */
    char *rewritten = rewrite_path(file);
    if (!rewritten) rewritten = rewrite_unix_path(file);
    const char *actual_path = rewritten ? rewritten : file;

    /* Only intercept absolute paths within the data directory */
    if (strncmp(actual_path, DATA_PREFIX, DATA_PREFIX_LEN) != 0) {
        int result = original_execvpe(actual_path, argv, envp);
        int saved_errno = errno;
        free(rewritten);
        errno = saved_errno;
        return result;
    }

    /* Check if it's an ELF binary */
    if (is_elf(actual_path)) {
        int count;
        char **new_argv = build_linker_argv(actual_path, NULL, NULL, NULL, argv, &count);
        if (!new_argv) {
            free(rewritten);
            errno = ENOMEM;
            return -1;
        }

        int result = original_execve(LINKER_PATH, new_argv, envp);
        int saved_errno = errno;
        free(new_argv);
        free(rewritten);
        errno = saved_errno;
        return result;
    }

    /* Check for shebang scripts */
    char interp[SHEBANG_MAX];
    char shebang_arg[SHEBANG_MAX];
    if (parse_shebang(actual_path, interp, sizeof(interp), shebang_arg, sizeof(shebang_arg))) {
        /* Rewrite the interpreter path too if needed */
        char *rewritten_interp = rewrite_path(interp);
        if (!rewritten_interp) rewritten_interp = rewrite_unix_path(interp);
        const char *actual_interp = rewritten_interp ? rewritten_interp : interp;

        /* If the interpreter is in the data dir and is an ELF, route through linker */
        if (strncmp(actual_interp, DATA_PREFIX, DATA_PREFIX_LEN) == 0 && is_elf(actual_interp)) {
            int count;
            char **new_argv = build_linker_argv(actual_path, actual_interp, shebang_arg,
                                                actual_path, argv, &count);
            if (!new_argv) {
                free(rewritten_interp);
                free(rewritten);
                errno = ENOMEM;
                return -1;
            }

            int result = original_execve(LINKER_PATH, new_argv, envp);
            int saved_errno = errno;
            free(new_argv);
            free(rewritten_interp);
            free(rewritten);
            errno = saved_errno;
            return result;
        }
        free(rewritten_interp);
        /* Interpreter not in data dir -- fall through to normal execvpe */
    }

    /* Not an ELF in data dir, not a shebang needing interception: pass through */
    int result = original_execvpe(actual_path, argv, envp);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/* ========================================================================
 * GROUP 5: BIONIC FORTIFIED VARIANTS
 * ======================================================================== */

/**
 * Intercepted __open_2().
 * This is the Bionic fortified variant of open() without the mode argument.
 * Called when open() is used without O_CREAT (so no mode is needed).
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real __open_2().
 */
__attribute__((visibility("default")))
int __open_2(const char *pathname, int flags) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original___open_2(actual_path, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}

/**
 * Intercepted __openat_2().
 * This is the Bionic fortified variant of openat() without the mode argument.
 * Called when openat() is used without O_CREAT (so no mode is needed).
 * Rewrites com.termux paths to com.mobilekinetic.agent before calling the real __openat_2().
 */
__attribute__((visibility("default")))
int __openat_2(int dirfd, const char *pathname, int flags) {
    init_originals();

    char *rewritten = rewrite_path(pathname);
    const char *actual_path = rewritten ? rewritten : pathname;

    int result = original___openat_2(dirfd, actual_path, flags);
    int saved_errno = errno;
    free(rewritten);
    errno = saved_errno;
    return result;
}
