from pathlib import Path


def test_research_tree_contains_no_android_payload():
    root = Path(__file__).resolve().parents[1]
    forbidden_suffixes = {".java", ".kt", ".apk", ".gradle"}
    forbidden_names = {"AndroidManifest.xml", "index.html"}
    files = [path for path in root.rglob("*") if path.is_file()]
    assert not [path for path in files if path.suffix in forbidden_suffixes or path.name in forbidden_names]
    text = "\n".join(path.as_posix() for path in files)
    assert "app/src/" not in text
