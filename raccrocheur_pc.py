"""
Raccrocheur PC pour Sonne Caller.
Le téléphone (Samsung non rooté) ne peut pas raccrocher tout seul.
Ce programme lit les journaux de l'app par le câble USB (adb). Pour chaque appel
lancé PAR L'APP uniquement (jamais tes appels perso), il raccroche :
  - dès que l'appel est décroché (personne OU messagerie) ;
  - sinon au bout de MAX_SECONDS au plus tard.

Lancement :  python raccrocheur_pc.py
Arrêt :      Ctrl+C
"""
import re
import shutil
import subprocess
import sys
import threading
import time

MAX_SECONDS = 25      # durée maximale de sonnerie
POLL_SECONDS = 0.3    # fréquence de vérification de l'état d'appel

# Android PreciseCallState : 0 repos, 1 décroché (ACTIVE), 3 numérotation, 4 ça sonne chez le correspondant
STATE_IDLE, STATE_ACTIVE = 0, 1

ADB = shutil.which("adb")
if not ADB:
    sys.exit("adb introuvable. Installe scrcpy ou les platform-tools Android.")

busy = threading.Lock()


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")


def foreground_state():
    out = adb("shell", "dumpsys", "telephony.registry").stdout
    m = re.search(r"mForegroundCallState=(\d+)", out)
    return int(m.group(1)) if m else None


def hang_up():
    adb("shell", "input", "keyevent", "KEYCODE_ENDCALL")


def watch_call():
    """Surveille un appel lancé par l'app et raccroche au bon moment."""
    if not busy.acquire(blocking=False):
        return  # déjà en train de surveiller cet appel
    try:
        start = time.time()
        seen_call = False
        while True:
            elapsed = time.time() - start
            state = foreground_state()
            if state not in (None, STATE_IDLE):
                seen_call = True
            if seen_call and state == STATE_IDLE:
                log(f"Appel terminé tout seul ({elapsed:.0f} s)")
                return
            if state == STATE_ACTIVE:
                hang_up()
                log(f"Décroché (personne ou messagerie) → raccroché à {elapsed:.0f} s")
                return
            if elapsed >= MAX_SECONDS:
                if state not in (None, STATE_IDLE):
                    hang_up()
                    log(f"Pas de réponse → raccroché à {elapsed:.0f} s")
                else:
                    log("Aucun appel détecté")
                return
            time.sleep(POLL_SECONDS)
    finally:
        time.sleep(2)
        if foreground_state() not in (None, STATE_IDLE):
            hang_up()
            log("Appel encore actif après raccrochage, 2e tentative")
        busy.release()


def main():
    log("Raccrocheur PC lancé. Ctrl+C pour arrêter.")
    while True:
        adb("wait-for-device")
        log("Téléphone connecté, en attente d'appels...")
        proc = subprocess.Popen(
            [ADB, "logcat", "-T", "1", "-v", "time", "-s", "SonneCaller:V"],
            stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
        )
        for line in proc.stdout:
            if "Appel GSM lancé" in line:
                log("Appel lancé par l'app, surveillance...")
                threading.Thread(target=watch_call, daemon=True).start()
        log("Connexion au téléphone perdue, nouvelle tentative...")
        time.sleep(3)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nArrêté.")
