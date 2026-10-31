"""Project verified precompiled runtime files; retain full build sources in the source kit."""
import argparse
import hashlib
import json
from pathlib import Path
from build_input_lexicon import encoded, sha


def project(args):
    if args.output.exists():raise ValueError("Preserve prior projections")
    source=json.loads((args.compiled/"manifest.json").read_bytes())
    if not source["compiled"] or source["format"]!="vime-rime-2":raise ValueError("Complete compiled source required")
    if hashlib.sha256(encoded(source["files"])).hexdigest()!=source["dataset_id"]:raise ValueError("Invalid source identity")
    removed={"essay.txt","vime_pinyin.dict.yaml"}
    if not removed<=source["files"].keys():raise ValueError("Full build sources are missing")
    args.output.mkdir(parents=True)
    for name,expected in source["files"].items():
        if sha(args.compiled/name)!=expected:raise ValueError("Source checksum differs")
        if name in removed:continue
        if ".." in Path(name).parts or Path(name).is_absolute():raise ValueError("Unsafe source path")
        dest=args.output/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes((args.compiled/name).read_bytes())
    result=dict(source)
    result.update(format="vime-rime-runtime-1",source_dataset_id=source["dataset_id"],
                  build_sources={name:source["files"][name] for name in sorted(removed)},
                  files={name:expected for name,expected in source["files"].items() if name not in removed},
                  source_delivery="Complete plaintext build inputs and compiled outputs accompany this version's dictionary source kit.")
    result["dataset_id"]=hashlib.sha256(encoded(result["files"])).hexdigest()
    (args.output/"manifest.json").write_bytes(encoded(result))
    print(json.dumps({"passed":True,"runtime_files":len(result["files"]),"source_dataset_id":source["dataset_id"],"runtime_dataset_id":result["dataset_id"]}),flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("compiled","output"):parser.add_argument("--"+name,required=True,type=Path)
    project(parser.parse_args())
