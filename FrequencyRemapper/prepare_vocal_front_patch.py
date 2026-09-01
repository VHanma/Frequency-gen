from pathlib import Path

path = Path(__file__).resolve().parent / "apply_vocal_front_patch.py"
text = path.read_text(encoding="utf-8")

old_first = '''replace_once(
    live,
    "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), versionCounter\\n",
    "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), vocalFront, versionCounter\\n"
)
'''
new_first = '''text = live.read_text(encoding="utf-8")
old_cfg_ctor = "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), versionCounter\\n"
new_cfg_ctor = "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), vocalFront, versionCounter\\n"
ctor_count = text.count(old_cfg_ctor)
if ctor_count != 2:
    raise SystemExit(f"Unexpected Config constructor count: {ctor_count}")
live.write_text(text.replace(old_cfg_ctor, new_cfg_ctor), encoding="utf-8")
'''

old_second = '''# second Config constructor occurrence in update()
text = live.read_text(encoding="utf-8")
old = "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), versionCounter\\n"
new = "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), vocalFront, versionCounter\\n"
if old in text:
    if text.count(old) != 1:
        raise SystemExit(f"Unexpected remaining Config constructor count: {text.count(old)}")
    live.write_text(text.replace(old, new, 1), encoding="utf-8")

'''

if new_first not in text:
    count = text.count(old_first)
    if count != 1:
        raise SystemExit(f"Expected one first constructor patch block, found {count}")
    text = text.replace(old_first, new_first, 1)

if old_second in text:
    text = text.replace(old_second, "", 1)

path.write_text(text, encoding="utf-8")
print("Prepared Vocals Front patch for both Config constructors.")
