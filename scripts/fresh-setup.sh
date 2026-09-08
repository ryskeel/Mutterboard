#!/usr/bin/env bash
# Walk the setup flow as a new user, without actually being one.
#
# The setup experience is the last thing standing between the overlay branch and
# shipping, and it can only be judged from nothing enabled, no key, no grants.
# Getting there by hand means giving up a working install and a pasted API key,
# which is why it never gets done.
#
#   scripts/fresh-setup.sh save      snapshot everything this app owns
#   scripts/fresh-setup.sh reset     tear it down to a fresh install
#   scripts/fresh-setup.sh restore   put it all back
#
# save writes to tools/.setup-backup, which is gitignored. Run it before reset,
# every time: reset is destructive and restore can only give back what save saw.
set -euo pipefail

PKG=com.example.mutterboard
IME="$PKG/.MutterboardInputMethodService"
A11Y="$PKG/.MutterboardAccessibilityService"
BACKUP="$(cd "$(dirname "$0")" && pwd)/.setup-backup"
APK="$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/debug/app-debug.apk"

say() { printf '  %s\n' "$*"; }

adb_ok() {
    adb get-state >/dev/null 2>&1 || { echo "No device. Plug the phone in."; exit 1; }
}

# Secure settings survive pm clear, so the keyboard and the accessibility
# service have to be handled separately from the app's own data.
secure_get() { adb shell settings get secure "$1" | tr -d '\r'; }

save() {
    mkdir -p "$BACKUP"
    say "prefs (API key, vocabulary, refine mode)"
    # run-as only works on a debuggable build, which is what we install anyway.
    adb exec-out run-as "$PKG" cat shared_prefs/mutterboard_prefs.xml \
        > "$BACKUP/mutterboard_prefs.xml" 2>/dev/null \
        || say "  (none yet)"
    say "secure settings"
    secure_get enabled_input_methods    > "$BACKUP/enabled_input_methods"
    secure_get default_input_method     > "$BACKUP/default_input_method"
    secure_get enabled_accessibility_services > "$BACKUP/enabled_a11y"
    secure_get accessibility_enabled    > "$BACKUP/a11y_enabled"
    # Force-stopping the app turns its accessibility service off, so a save that
    # follows one records "off" and restore hands that back. Say so rather than
    # silently snapshotting a state the user did not choose.
    if ! grep -q "$PKG" "$BACKUP/enabled_a11y"; then
        say "  NOTE: the accessibility service is currently off in this snapshot"
    fi

    say "grants"
    adb shell dumpsys package "$PKG" | tr -d '\r' > "$BACKUP/package_dump.txt"
    adb shell appops get "$PKG" SYSTEM_ALERT_WINDOW > "$BACKUP/appop_saw.txt" 2>/dev/null || true
    say "component states (which entry point owns the app icon)"
    # Read the dump's own blocks rather than grepping for the class names: they
    # appear elsewhere in the dump too, so a loose match would restore the
    # overlay for someone who had it off.
    for block in enabled disabled; do
        awk -v want="${block}Components:" '
            $1 == want { f = 1; next }
            f && /^[[:space:]]+com\./ { print $1; next }
            f { exit }
        ' "$BACKUP/package_dump.txt" > "$BACKUP/${block}_components"
    done
    say "saved to $BACKUP"
}

reset() {
    [ -d "$BACKUP" ] || { echo "Run 'save' first."; exit 1; }
    say "stopping the app"
    adb shell am force-stop "$PKG"
    adb shell am stopservice "$PKG/.OverlayDictationService" >/dev/null 2>&1 || true

    # Only worth touching if our keyboard is actually in the picture. It is not
    # enabled at all on an overlay-only phone, and switching away from a default
    # that was never ours just picks a fight with whatever is typing.
    if adb shell ime list -s | tr -d '\r' | grep -q "^$PKG/"; then
        local current other
        current=$(adb shell settings get secure default_input_method | tr -d '\r')
        case "$current" in
            "$PKG/"*)
                # Only the enabled list is selectable; -a lists ones that are not.
                other=$(adb shell ime list -s | tr -d '\r' | grep -v "^$PKG/" | head -1)
                if [ -n "$other" ]; then
                    say "switching the keyboard to $other"
                    adb shell ime set "$other" >/dev/null || true
                else
                    say "WARNING: no other keyboard enabled; leaving the IME alone"
                    return 1
                fi
                ;;
        esac
        say "disabling the Mutterboard keyboard"
        adb shell ime disable "$IME" >/dev/null || true
    fi

    say "turning the accessibility service off"
    # delete, not put "": an empty value is rejected as a bad argument.
    adb shell settings delete secure enabled_accessibility_services >/dev/null || true
    adb shell settings put secure accessibility_enabled 0 || true

    # Uninstall rather than pm clear. Clearing leaves the component states the
    # overlay toggle sets, so the overlay would still own the app icon - and the
    # shell user is not allowed to put those back (SecurityException: "Shell
    # cannot change component state"), only the app itself can. Reinstalling is
    # the only way to a state a new user would actually recognise.
    say "uninstalling"
    adb uninstall "$PKG" >/dev/null
    say "installing the debug build"
    adb install -r "$APK" >/dev/null
    echo
    echo "Fresh. Open Mutterboard from the drawer and walk it as a new user."
}

restore() {
    [ -d "$BACKUP" ] || { echo "Nothing saved."; exit 1; }
    say "stopping the app"
    adb shell am force-stop "$PKG"

    if [ -s "$BACKUP/mutterboard_prefs.xml" ]; then
        say "prefs"
        adb shell run-as "$PKG" mkdir -p shared_prefs
        # The redirect has to live inside the quoted remote command. Left
        # outside, the device's own shell applies it as the shell user in / -
        # and exec-out does not forward stdin at all, so the file gets truncated
        # to nothing and the API key with it. Both of those happened.
        adb shell "run-as $PKG sh -c 'cat > shared_prefs/mutterboard_prefs.xml'" \
            < "$BACKUP/mutterboard_prefs.xml"
    fi

    say "grants"
    for perm in RECORD_AUDIO POST_NOTIFICATIONS; do
        if grep -q "android.permission.$perm: granted=true" "$BACKUP/package_dump.txt"; then
            adb shell pm grant "$PKG" "android.permission.$perm" || true
        fi
    done
    if grep -q ": allow" "$BACKUP/appop_saw.txt" 2>/dev/null; then
        adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
    fi

    # The overlay toggle's component states are deliberately not restored: only
    # the app may change them, so this is the one thing that has to go back by
    # hand. It is one switch.
    if grep -q OverlayLauncherActivity "$BACKUP/enabled_components"; then
        NEEDS_OVERLAY_TOGGLE=1
    fi

    say "secure settings"
    # Quoted for the DEVICE's shell, not just ours. An enabled-IME list is
    # semicolon-separated, and adb shell hands its arguments to sh as one string:
    # unquoted, everything after the first ";" runs as its own command.
    secure_put() {
        adb shell "settings put secure $1 '$2'" || true
    }
    secure_put enabled_input_methods "$(cat "$BACKUP/enabled_input_methods")"
    secure_put default_input_method "$(cat "$BACKUP/default_input_method")"
    secure_put enabled_accessibility_services "$(cat "$BACKUP/enabled_a11y")"
    secure_put accessibility_enabled "$(cat "$BACKUP/a11y_enabled")"

    echo
    echo "Back to where you were - except the overlay switch."
    if [ -n "${NEEDS_OVERLAY_TOGGLE:-}" ]; then
        echo "Open Mutterboard and turn 'Dictation overlay' back on; only the app"
        echo "itself is allowed to change which entry point owns the app icon."
    fi
}

adb_ok
case "${1:-}" in
    save) save ;;
    reset) reset ;;
    restore) restore ;;
    *) echo "usage: $0 {save|reset|restore}"; exit 2 ;;
esac
