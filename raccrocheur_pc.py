"""
Raccrocheur PC pour Sonne Caller.
Le téléphone (Samsung non rooté) ne peut pas raccrocher tout seul.
Ce programme lit les journaux de l'app par le câble USB (adb). Pour chaque appel
lancé PAR L'APP uniquement (jamais tes appels perso), il raccroche :
  - dès que l'appel est décroché (personne OU messagerie) ;
  - sinon au bout de MAX_SECONDS au plus tard.

Chaque appel est noté dans journal_appels.csv (à côté de ce fichier) : pays, a sonné ?,
décroché ?, délais, raison de fin. Les numéros ne sont jamais enregistrés.

Lancement :     python raccrocheur_pc.py
Statistiques :  python raccrocheur_pc.py stats
Arrêt :         Ctrl+C
"""
import csv
import os
import re
import shutil
import subprocess
import sys
import threading
import time

MAX_SECONDS = 25      # durée maximale de sonnerie
POLL_SECONDS = 0.3    # fréquence de vérification de l'état d'appel

# Android PreciseCallState : 0 repos, 1 décroché (ACTIVE), 3 numérotation, 4 ça sonne chez le correspondant
STATE_IDLE, STATE_ACTIVE, STATE_ALERTING = 0, 1, 4

JOURNAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "journal_appels.csv")
COLONNES = ["date", "heure", "pays", "a_sonne", "secondes_avant_sonnerie", "decroche",
            "secondes_avant_decroche", "secondes_decroche", "fin", "code_fin"]

# Codes de fin d'appel réseau (3GPP, « Precise disconnect cause »)
CAUSES = {
    1: "numéro inexistant", 16: "fin normale", 17: "occupé", 18: "pas de réponse du réseau",
    19: "pas de réponse", 21: "appel refusé", 22: "numéro changé", 27: "téléphone hors service",
    28: "numéro mal formé", 31: "fin normale", 34: "réseau saturé", 38: "réseau en panne",
    41: "panne temporaire", 42: "réseau saturé",
}
# Codes Android (« DisconnectCause ») si le code réseau manque
CAUSES_ANDROID = {
    2: "fin normale", 3: "raccroché par nous", 4: "occupé", 5: "réseau saturé",
    7: "numéro invalide", 8: "numéro injoignable", 13: "délai dépassé", 14: "signal perdu",
    16: "appel refusé", 17: "téléphone éteint", 18: "hors réseau", 20: "appel bloqué",
}

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


def disconnect_causes():
    """Raison de la fin du dernier appel : (code réseau, code Android)."""
    out = adb("shell", "dumpsys", "telephony.registry").stdout
    precise = re.search(r"mCallPreciseDisconnectCause=(-?\d+)", out)
    android = re.search(r"mCallDisconnectCause=(-?\d+)", out)
    return (int(precise.group(1)) if precise else -1, int(android.group(1)) if android else -1)


def country_of(number):
    """Pays seulement (le numéro n'est jamais gardé)."""
    n = number.replace("#31#", "")
    if n.startswith("+33"):
        return "France"
    if n.startswith(("+262", "+590", "+594", "+596")):
        return "DOM"
    if n.startswith("+1"):
        return "USA/Canada (" + n[2:5] + ")"
    if n.startswith("+86"):
        return "Chine"
    return "autre"


def write_journal(row):
    new = not os.path.exists(JOURNAL)
    try:
        with open(JOURNAL, "a", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, fieldnames=COLONNES, delimiter=";")
            if new:
                w.writeheader()
            w.writerow(row)
    except OSError as e:
        log(f"Journal non écrit ({e}) - fichier ouvert dans Excel ?")


def hang_up():
    adb("shell", "input", "keyevent", "KEYCODE_ENDCALL")


def watch_call(pays="?"):
    """Surveille un appel lancé par l'app et raccroche au bon moment."""
    if not busy.acquire(blocking=False):
        return  # déjà en train de surveiller cet appel
    start = time.time()
    info = {"sonne": None, "decroche": None, "detecte": False}
    try:
        seen_call = False
        while True:
            elapsed = time.time() - start
            state = foreground_state()
            if state not in (None, STATE_IDLE):
                info["detecte"] = True
            if state == STATE_ALERTING and info["sonne"] is None:
                info["sonne"] = elapsed
            if state == STATE_ACTIVE and info["decroche"] is None:
                info["decroche"] = elapsed
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
        try:
            noter_appel(pays, start, info)
        except Exception as e:  # le journal ne doit jamais gêner le raccrochage
            log(f"Journal : erreur {e}")


def noter_appel(pays, start, info):
    end = time.time()
    precise, android = disconnect_causes()
    if not info["detecte"]:
        fin = "aucun appel détecté"
    elif info["decroche"] is not None:
        fin = "décroché → raccroché"
    elif precise in CAUSES and precise not in (16, 31):
        fin = CAUSES[precise]
    elif android == 3:
        fin = "pas de réponse → raccroché par nous"
    elif android in CAUSES_ANDROID:
        fin = CAUSES_ANDROID[android]
    else:
        fin = "inconnue"
    t = time.localtime(start)
    sonne, decroche = info["sonne"], info["decroche"]
    write_journal({
        "date": time.strftime("%Y-%m-%d", t),
        "heure": time.strftime("%H:%M:%S", t),
        "pays": pays,
        "a_sonne": "oui" if sonne is not None else "non",
        "secondes_avant_sonnerie": f"{sonne:.1f}" if sonne is not None else "",
        "decroche": "oui" if decroche is not None else "non",
        "secondes_avant_decroche": f"{decroche:.1f}" if decroche is not None else "",
        # temps décroché approximatif (on retire les 2 s de vérification finale)
        "secondes_decroche": f"{max(0.0, end - start - 2 - decroche):.1f}" if decroche is not None else "0",
        "fin": fin,
        "code_fin": f"{precise}/{android}",
    })
    log(f"Noté dans le journal : {pays}, {fin}")


def stats():
    if not os.path.exists(JOURNAL):
        print("Pas encore de journal.")
        return
    with open(JOURNAL, encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f, delimiter=";"))
    total = len(rows)
    if not total:
        print("Journal vide.")
        return
    sonne = sum(r["a_sonne"] == "oui" for r in rows)
    decroche = sum(r["decroche"] == "oui" for r in rows)
    sec = sum(float(r["secondes_decroche"] or 0) for r in rows)
    print(f"Appels notés : {total}  (depuis le {rows[0]['date']})")
    print(f"  Ont sonné chez la personne : {sonne} ({100 * sonne // total} %)")
    print(f"  Ont décroché (personne ou messagerie) : {decroche} ({100 * decroche // total} %)")
    print(f"  Temps décroché total (forfait) : environ {sec / 60:.1f} min")
    par_pays, par_fin = {}, {}
    for r in rows:
        k = r["pays"].split(" (")[0]
        par_pays[k] = par_pays.get(k, 0) + 1
        par_fin[r["fin"]] = par_fin.get(r["fin"], 0) + 1
    print("Par pays :", ", ".join(f"{k} {v}" for k, v in sorted(par_pays.items(), key=lambda x: -x[1])))
    print("Fins d'appel :")
    for k, v in sorted(par_fin.items(), key=lambda x: -x[1]):
        print(f"  {k} : {v}")


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
                m = re.search(r"vers\s+(\S+)", line)
                pays = country_of(m.group(1)) if m else "?"
                threading.Thread(target=watch_call, args=(pays,), daemon=True).start()
        log("Connexion au téléphone perdue, nouvelle tentative...")
        time.sleep(3)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "stats":
        stats()
        sys.exit()
    try:
        main()
    except KeyboardInterrupt:
        print("\nArrêté.")
