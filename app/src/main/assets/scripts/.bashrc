export TMPDIR=$HOME/tmp
export CARGO_TARGET_DIR=$HOME/tmp/cargo
export CARGO_HOME=$HOME/.cargo
export PIP_CACHE_DIR=$HOME/tmp/pip-cache
export CC=cc
export CXX=c++
mkdir -p $TMPDIR $CARGO_TARGET_DIR $CARGO_HOME $PIP_CACHE_DIR 2>/dev/null
export TMPDIR=$PREFIX/tmp

# /sdcard TIMEOUT GUARDS
_SDCARD_TIMEOUT=3
_path_touches_sdcard() {
    for arg in "$@"; do
        case "$arg" in
            /sdcard*|/storage/emulated*|/mnt/sdcard*|/storage/self*|~/storage*) return 0 ;;
        esac
    done
    return 1
}
_guarded_exec() {
    local cmd="$1"; shift
    if _path_touches_sdcard "$@"; then
        timeout "$_SDCARD_TIMEOUT" command "$cmd" "$@" 2>/dev/null
        local rc=$?
        if [ $rc -eq 124 ]; then
            echo "sdcard-guard: '$cmd $*' timed out after ${_SDCARD_TIMEOUT}s (scoped storage)" >&2
            return 1
        fi
        return $rc
    else
        command "$cmd" "$@"
    fi
}
for _cmd in ls cat head tail wc stat file find readlink realpath du df cp mv rm touch mkdir rmdir chmod chown grep rg egrep fgrep; do
    eval "${_cmd}() { _guarded_exec ${_cmd} \"\$@\"; }"
done
cd() {
    if _path_touches_sdcard "$@"; then
        if ! timeout "$_SDCARD_TIMEOUT" test -d "$1" 2>/dev/null; then
            echo "sdcard-guard: 'cd $1' blocked (scoped storage)" >&2
            return 1
        fi
    fi
    builtin cd "$@"
}
unset _cmd
. "$HOME/.cargo/env"
