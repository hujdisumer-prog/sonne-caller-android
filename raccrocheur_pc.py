"""
Raccrocheur PC pour Sonne Caller.
Le téléphone (Samsung non rooté) ne peut pas raccrocher tout seul.
Ce programme lit les journaux de l'app par le câble USB (adb) et, quand l'app
écrit « Impossible de raccrocher automatiquement », il raccroche à sa place.

Lancement :  python raccrocheur_pc.py
Arrêt :      Ctrl+C
"""
import shutil
import subprocess
import sys
import time

ADB = shutil.which("adb")
if not ADB:
    sys.exit("adb introuvable. Installe scrcpy ou les platform-tools Android.")


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True)


def en_appel():
    out = adb("shell", "dumpsys", "telephony.registry").stdout
    for line in out.splitlines():
        if "mCallState=" in line:
            return line.strip() != "mCallState=0"
    return False


def raccrocher():
    adb("shell", "input", "keyevent", "KEYCODE_ENDCALL")
    time.sleep(1.5)
    return not en_appel()


def main():
    print("Raccrocheur PC lancé. Ctrl+C pour arrêter.")
    while True:
        # Relance automatique si le téléphone est débranché puis rebranché
        adb("wait-for-device")
        print(time.strftime("%H:%M:%S"), "Téléphone connecté, en attente d'appels...")
        proc = subprocess.Popen(
            [ADB, "logcat", "-T", "1", "-v", "time", "-s", "SonneCaller:V"],
            stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
        )
        for line in proc.stdout:
            if "Appel GSM lancé" in line:
                print(time.strftime("%H:%M:%S"), "Appel lancé")
            elif "Impossible de raccrocher" in line:
                if not en_appel():
                    print(time.strftime("%H:%M:%S"), "Appel déjà terminé")
                    continue
                ok = raccrocher()
                print(time.strftime("%H:%M:%S"), "Raccroché par le PC" if ok else "ÉCHEC du raccrochage, raccroche à la main !")
        print(time.strftime("%H:%M:%S"), "Connexion au téléphone perdue, nouvelle tentative...")
        time.sleep(3)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nArrêté.")
