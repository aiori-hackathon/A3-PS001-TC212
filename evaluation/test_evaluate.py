import json, subprocess, sys, tempfile
from pathlib import Path
sample = {"runs": [
    {"label":"simulation","alert":True,"detection_delay_ms":120,"distinct_files_at_alert":5},
    {"label":"simulation","alert":False},
    {"label":"benign","alert":False},
    {"label":"benign","alert":True,"detection_delay_ms":80,"distinct_files_at_alert":5}
]}
with tempfile.TemporaryDirectory() as d:
    p = Path(d) / "evidence.json"
    p.write_text(json.dumps(sample), encoding="utf-8")
    out = subprocess.check_output([sys.executable, str(Path(__file__).with_name("evaluate.py")), str(p)], text=True)
    assert "TP=1 FP=1 FN=1 TN=1" in out, out
    assert "Precision: 0.500" in out, out
    assert "Recall: 0.500" in out, out
print("evaluation smoke test passed")
