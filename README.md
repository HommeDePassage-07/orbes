# Orbes multi-fenêtres

Une sphère de particules façon Jarvis dans chaque fenêtre. Chaque nouvelle fenêtre prend une autre couleur.
Quand tu rapproches deux fenêtres, un lien lumineux les relie et leurs couleurs fusionnent.

## Version Web (HTML + CSS + JavaScript)

Dossier `web/` : `index.html`, `style.css`, `script.js`.

Communication : `BroadcastChannel` + `localStorage` (les deux fenêtres doivent venir du même dossier ou du même site).

## Version Java (Swing, aucune dépendance)

Dossier `java/` : `Orbes.java`. Il faut Java 11 ou plus.

```
javac Orbes.java && java Orbes.java
```


## Version Python (PySide6 + NumPy)

Dossier `python/` : `orbes.py`.

```
pip install PySide6 numpy
python orbes.py
```


## Comment les fenêtres se parlent

- **Web** : chaque fenêtre envoie sa position sur l'écran (`window.screenX/screenY`) aux autres via le navigateur.
- **Java et Python** : chaque fenêtre écrit sa position dans un petit fichier, dans le dossier temporaire du système (`orbes/`), ~20 fois par seconde, et lit ceux des autres.
  Java et Python utilisent le même format : une fenêtre Java et une fenêtre Python se voient et se relient entre elles.
- Chaque fenêtre dessine **toutes** les sphères à leur vraie place sur l'écran, en retirant sa propre position.
  C'est pour ça que le lien a l'air de passer d'une fenêtre à l'autre.
- La fusion vient du mélange **additif** (bleu + vert = cyan) et du rapprochement des teintes selon la distance.

## Dépannage

- **Linux avec Wayland** : Wayland ne donne pas la position des fenêtres aux applications.
  Pour Python, lance : `QT_QPA_PLATFORM=xcb python orbes.py` (paquet système `libxcb-cursor0` nécessaire).
  Java passe déjà par X11, donc ça marche directement.
- **Plusieurs écrans** : ça marche tant que les fenêtres sont sur le même bureau étendu.
- Une fenêtre fermée brutalement disparaît chez les autres au bout de 3 secondes.
