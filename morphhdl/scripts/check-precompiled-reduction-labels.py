#!/usr/bin/env python3
"""Compile BalancedTree and its consumers separately, then qualify label ownership.

Supply an already built matching compiler classpath. Fresh library classes precede
that classpath for consumer compilation and execution; no source checkout or
application RTL is modified. This is a targeted local check, not full CI.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
SUITES = ["PrecompiledReductionLabelTests", "ReductionWiringTests",
          "BranchWiringSafetyTests", "CompactGenerateLabelTests"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scala", choices=["2.12", "2.13"], required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cp = args.classpath_file.read_text().strip()
    if not all(Path(p).exists() for p in cp.split(os.pathsep)):
        raise SystemExit("Missing compiler classpath entry")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    for name in ["library", "consumers", "artifacts", "xml"]:
        (out/name).mkdir()
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    plugins = [ROOT/f"{project}/target/scala-{args.scala}/{name}_{args.scala}-dev.jar"
               for project, name in [("idslplugin", "spinalhdl-idsl-plugin"),
                                     ("morphplugin", "morphhdl-compiler-plugin")]]
    sources = [ROOT/f"morphhdl/src/test/scala/morphhdl/{name}.scala" for name in SUITES]
    sources += [ROOT/"morphhdl/src/test/scala/morphhdl/Increment66ToolEvidence.scala"]
    library = ROOT/"lib/src/main/scala/spinal/lib/BalancedTree.scala"
    options = ["-Yrangepos", *["-Xplugin:"+str(p) for p in plugins],
               "-Xplugin-require:idsl-plugin", "-Xplugin-require:morphhdl"]
    java = ["java", "-Xmx3G", "-XX:ActiveProcessorCount=4"]
    consumer_cp = str(out/"library")+os.pathsep+cp
    commands = [
        java+["-cp", cp, "scala.tools.nsc.Main", "-classpath", cp, "-d", str(out/"library"),
              *options, "-P:morphhdl:ordinary-loops=host", str(library)],
        java+["-cp", consumer_cp, "scala.tools.nsc.Main", "-classpath", consumer_cp,
              "-d", str(out/"consumers"), *options, *map(str, sources)],
        java+["-Djava.io.tmpdir="+str(out/"artifacts"), "-cp",
              str(out/"consumers")+os.pathsep+consumer_cp, "org.scalatest.tools.Runner",
              "-o", "-u", str(out/"xml"), *[arg for name in SUITES for arg in ["-s", "morphhdl."+name]]],
    ]
    paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard"],
                                    cwd=ROOT, text=True).splitlines()
    record = dict(base=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  scala=args.scala, commands=[],
                  source_sha256={p: sha(ROOT/p) for p in paths if p.endswith(".scala") and (ROOT/p).is_file()},
                  plugins={str(p.relative_to(ROOT)): sha(p) for p in plugins})
    for index, command in enumerate(commands):
        start = time.monotonic()
        log = out/f"{index}.log"
        with log.open("w") as stream:
            result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                    timeout=600, env={**os.environ, "MORPHDL_INCREMENT66_EVIDENCE": str(out/"tools")})
        record["commands"].append(dict(command=command, returncode=result.returncode,
                                       seconds=time.monotonic()-start, log_sha256=sha(log)))
        record["library_classes"] = {str(p.relative_to(out)): sha(p) for p in (out/"library").rglob("*.class")}
        record["artifacts"] = {str(p.relative_to(out)): sha(p) for p in (out/"artifacts").rglob("*.v")}
        (out/"results.json").write_text(json.dumps(record, indent=2)+"\n")
        print(index, result.returncode, log, flush=True)
        if result.returncode:
            raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
