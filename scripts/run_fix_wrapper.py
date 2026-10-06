from pathlib import Path

p = Path('scripts/fix_kulturadar_ui_speed.py')
s = p.read_text()
old = '''def regex_once(s: str, pattern: str, repl: str, label: str) -> str:
    out, n = re.subn(pattern, repl, s, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 replacement, got {n}")
    return out
'''
new = '''def regex_once(s: str, pattern: str, repl: str, label: str) -> str:
    if label == "collapsible filters":
        start_marker = "@Composable\\nprivate fun ProControls("
        end_marker = "@Composable\\nprivate fun ProMessage"
    elif label == "image fallback":
        start_marker = "@Composable\\nprivate fun ProImage("
        end_marker = "@Composable\\nprivate fun ProDetail"
    else:
        out, n = re.subn(pattern, repl, s, count=1, flags=re.S)
        if n != 1:
            raise SystemExit(f"{label}: expected 1 replacement, got {n}")
        return out
    start = s.find(start_marker)
    end = s.find(end_marker, start + 1)
    if start < 0 or end < 0:
        raise SystemExit(f"{label}: markers not found")
    return s[:start] + repl + s[end + len(end_marker):]
'''
if old not in s:
    raise SystemExit('wrapper target not found')
s = s.replace(old, new, 1)
exec(compile(s, str(p), 'exec'), {'__name__': '__main__'})
