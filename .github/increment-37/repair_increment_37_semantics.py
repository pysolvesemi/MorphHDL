#!/usr/bin/env python3
from pathlib import Path


def unique_replace(text: str, old: str, new: str, description: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{description} anchor count was {count}, expected 1")
    return text.replace(old, new, 1)


apply_path = Path('.github/increment-37/apply_increment_37.py')
apply_text = apply_path.read_text()

start_candidates = (
    '      // Normal emission uses logic_ptr_push/logic_ptr_pop while other\n',
    '      val compactName = lower.replace("_", "")\n',
    '      val pointerContext =\n',
)
start = -1
for marker in start_candidates:
    found = apply_text.find(marker)
    if found >= 0:
        start = found
        break
end_marker = '      val memoryArray = lower.contains("[0:")\n'
end = apply_text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('Increment 37 FIFO geometry-context anchors were not found')

context_replacement = '''      // The witness-depth FIFO emits several related address and occupancy
      // pipeline names. Normalize separators and classify the complete native
      // path so every depth-derived declaration is rewritten consistently.
      val compactName = lower.replace("_", "")
      val pointerContext =
        compactName.contains("pushptr") || compactName.contains("popptr") ||
          compactName.contains("ptrpush") || compactName.contains("ptrpop") ||
          compactName.contains("poponio") || compactName.contains("popreg") ||
          compactName.contains("addressgenpayload") ||
          compactName.contains("addressgenrdata") ||
          compactName.contains("readarbitrationpayload") ||
          compactName.contains("readportcmdpayload") ||
          compactName.contains("toflowfirepayload") ||
          (lower.contains("address") &&
            (lower.contains("ram") || lower.contains("memory")))
      val occupancyContext =
        lower.contains("occupancy") || lower.contains("availability") ||
          (compactName.contains("notpow2counter") &&
            !lower.contains("[0:0]")) ||
          lower.contains("push_ready")
'''
apply_text = apply_text[:start] + context_replacement + apply_text[end:]
apply_path.write_text(apply_text)

build_path = Path('.github/increment-37/build_increment_37_test.py')
build_text = build_path.read_text()
sentinel = '# Increment 37 native-capacity and observable-occupancy proof'
if sentinel not in build_text:
    build_text += r'''

# Increment 37 native-capacity and observable-occupancy proof
source = TARGET.read_text()

def replace_source_once(old: str, new: str, description: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(
            f"Increment 37 {description} anchor count was {count}, expected 1"
        )
    source = source.replace(old, new, 1)

replace_source_once(
    """    val simulationLog = run(directory, Seq("vvp", executable.toString))
    assert(simulationLog._1 == 0, simulationLog._2)
    assert(
      simulationLog._2.contains(s"PASS depth=$selectedDepth"),
      simulationLog._2
    )
""",
    """    val simulationLog = run(directory, Seq("vvp", executable.toString))
    if (
      simulationLog._1 != 0 ||
        !simulationLog._2.contains(s"PASS depth=$selectedDepth")
    ) {
      println(s"--- BEGIN PARAMETERIZED FIFO RTL depth=$selectedDepth ---")
      println(read(rtl))
      println(s"--- END PARAMETERIZED FIFO RTL depth=$selectedDepth ---")
    }
    assert(simulationLog._1 == 0, simulationLog._2)
    assert(
      simulationLog._2.contains(s"PASS depth=$selectedDepth"),
      simulationLog._2
    )
""",
    "generated RTL failure diagnostics",
)
replace_source_once(
    """         |  wire [7:0] io_pop_payload;
         |  reg io_flush = 1'b0;
""",
    """         |  wire [7:0] io_pop_payload;
         |  reg io_flush = 1'b0;
         |  wire [3:0] io_occupancy;
         |  wire [3:0] io_availability;
""",
    "testbench occupancy declarations",
)
replace_source_once(
    """         |    .io_flush(io_flush),
         |    .io_occupancy(),
         |    .io_availability(),
""",
    """         |    .io_flush(io_flush),
         |    .io_occupancy(io_occupancy),
         |    .io_availability(io_availability),
""",
    "testbench occupancy connections",
)
replace_source_once(
    """         |      $$display("FAIL depth=%0d: %0s", DEPTH, reason);
         |      $$finish(2);
""",
    """         |      $$display("FAIL depth=%0d: %0s", DEPTH, reason);
         |      $$display("STATE sent=%0d received=%0d ready=%b valid=%b occupancy=%0d availability=%0d",
         |        sent, received, io_push_ready, io_pop_valid, io_occupancy, io_availability);
         |      $$finish(2);
""",
    "failure-state diagnostics",
)
replace_source_once(
    """         |    reset = 1'b0;
         |    tick;
         |
         |    capacity = (DEPTH == 1) ? 1 : DEPTH + 1;
""",
    """         |    reset = 1'b0;
         |    tick;
         |    if (io_occupancy !== 0) fail("reset occupancy mismatch");
         |    if (io_availability !== DEPTH) fail("reset availability mismatch");
         |
         |    capacity = DEPTH;
""",
    "native FIFO capacity",
)
replace_source_once(
    """         |      if (!io_push_ready) fail("push timeout");
         |      tick;
         |    end
""",
    """         |      if (!io_push_ready) fail("push timeout");
         |      tick;
         |      if (io_occupancy !== (sent + 1)) fail("occupancy mismatch after push");
         |      if (io_availability !== (DEPTH - sent - 1)) fail("availability mismatch after push");
         |    end
""",
    "push occupancy checks",
)
replace_source_once(
    """         |    io_push_valid = 1'b0;
         |    tick;
         |    if (io_push_ready !== 1'b0) fail("fifo did not report full");
""",
    """         |    io_push_valid = 1'b0;
         |    tick;
         |    if (io_push_ready !== 1'b0) fail("fifo did not report full");
         |    if (io_occupancy !== capacity) fail("full occupancy mismatch");
         |    if (io_availability !== 0) fail("full availability mismatch");
""",
    "full-state checks",
)
replace_source_once(
    """         |      tick;
         |      timeout = timeout + 1;
         |    end
         |    if (received != capacity) fail("pop timeout");
""",
    """         |      tick;
         |      if (io_occupancy !== (capacity - received)) fail("occupancy mismatch after pop");
         |      if (io_availability !== received) fail("availability mismatch after pop");
         |      timeout = timeout + 1;
         |    end
         |    if (received != capacity) fail("pop timeout");
""",
    "pop occupancy checks",
)
replace_source_once(
    """         |    io_pop_ready = 1'b0;
         |    tick;
         |    if (io_pop_valid !== 1'b0) fail("fifo did not become empty");
""",
    """         |    io_pop_ready = 1'b0;
         |    tick;
         |    if (io_pop_valid !== 1'b0) fail("fifo did not become empty");
         |    if (io_occupancy !== 0) fail("empty occupancy mismatch");
         |    if (io_availability !== DEPTH) fail("empty availability mismatch");
""",
    "empty-state checks",
)
replace_source_once(
    """         |    if (!io_push_ready) fail("post-drain push timeout");
         |    tick;
         |    io_push_valid = 1'b0;
""",
    """         |    if (!io_push_ready) fail("post-drain push timeout");
         |    tick;
         |    if (io_occupancy !== 1) fail("post-drain occupancy mismatch");
         |    if (io_availability !== (DEPTH - 1)) fail("post-drain availability mismatch");
         |    io_push_valid = 1'b0;
""",
    "post-drain occupancy checks",
)
replace_source_once(
    """         |    if (io_pop_valid !== 1'b0) fail("flush did not discard queued data");
         |
         |    $$display("PASS depth=%0d", DEPTH);
""",
    """         |    if (io_pop_valid !== 1'b0) fail("flush did not discard queued data");
         |    if (io_occupancy !== 0) fail("flush occupancy mismatch");
         |    if (io_availability !== DEPTH) fail("flush availability mismatch");
         |
         |    $$display("PASS depth=%0d", DEPTH);
""",
    "flush occupancy checks",
)

geometry_anchor = """      assert(
        parameterized.contains("clog2((DEPTH + 1), 1)") ||
          parameterized.contains("clog2(DEPTH + 1, 1)")
      )
"""
geometry_checks = geometry_anchor + (
    '      assert(\n'
    '        """(?m)^\\s*(?:wire|reg)\\s+\\[clog2\\(DEPTH, 1\\)-1:0\\]\\s+logic_ptr_(?:push|pop|popOnIo);\\s*$""".r\n'
    '          .findAllMatchIn(parameterized)\n'
    '          .size >= 3\n'
    '      )\n'
    '      assert(\n'
    '        """(?m)^\\s*(?:wire|reg)\\s+\\[clog2\\(DEPTH, 1\\)-1:0\\]\\s+logic_pop_(?:addressGen_payload|sync_readPort_cmd_payload|sync_popReg);\\s*$""".r\n'
    '          .findAllMatchIn(parameterized)\n'
    '          .size >= 3\n'
    '      )\n'
    '      assert(\n'
    '        """(?m)^\\s*(?:wire|reg)\\s+\\[clog2\\(\\(DEPTH \\+ 1\\), 1\\)-1:0\\]\\s+logic_ptr_notPow2_counter;\\s*$""".r\n'
    '          .findFirstIn(parameterized)\n'
    '          .nonEmpty\n'
    '      )\n'
)
replace_source_once(
    geometry_anchor,
    geometry_checks,
    "symbolic FIFO geometry assertions",
)

TARGET.write_text(source)
print("Repaired Increment 37 native capacity, geometry, and occupancy regression")
'''
    build_path.write_text(build_text)

complete_path = Path('.github/increment-37/complete_increment_37.py')
complete_text = complete_path.read_text()
api_start = complete_text.find('def api(\n')
api_end = complete_text.find('\n\ndef branch_sha', api_start)
if api_start < 0 or api_end < 0:
    raise SystemExit('Increment 37 controller API anchors were not found')
api_replacement = '''def api(
    method: str,
    path: str,
    *,
    data: dict | None = None,
    allow: tuple[int, ...] = (),
) -> tuple[int, object | None]:
    url = f"https://api.github.com/repos/{REPO}{path}"
    body = None if data is None else json.dumps(data).encode("utf-8")
    attempts = 5
    for attempt in range(1, attempts + 1):
        request = urllib.request.Request(url, data=body, method=method)
        request.add_header("Authorization", f"Bearer {TOKEN}")
        request.add_header("Accept", "application/vnd.github+json")
        request.add_header("X-GitHub-Api-Version", "2022-11-28")
        if body is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read()
                decoded = json.loads(payload) if payload else None
                return response.status, decoded
        except urllib.error.HTTPError as error:
            payload = error.read()
            decoded: object | None
            try:
                decoded = json.loads(payload) if payload else None
            except json.JSONDecodeError:
                decoded = payload.decode("utf-8", errors="replace")
            if error.code in allow:
                return error.code, decoded
            retryable = error.code == 429 or 500 <= error.code < 600
            if retryable and attempt < attempts:
                delay = min(2 ** (attempt - 1), 8)
                print(
                    f"GitHub API {method} {path} returned {error.code}; "
                    f"retrying in {delay}s ({attempt}/{attempts})"
                )
                time.sleep(delay)
                continue
            raise RuntimeError(
                f"GitHub API {method} {path} failed: {error.code} {decoded}"
            ) from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            if attempt < attempts:
                delay = min(2 ** (attempt - 1), 8)
                print(
                    f"GitHub API {method} {path} transport failure {error}; "
                    f"retrying in {delay}s ({attempt}/{attempts})"
                )
                time.sleep(delay)
                continue
            raise RuntimeError(
                f"GitHub API {method} {path} transport failure after {attempts} attempts: {error}"
            ) from error
    raise RuntimeError(f"GitHub API {method} {path} exhausted retries")
'''
complete_path.write_text(
    complete_text[:api_start] + api_replacement + complete_text[api_end:]
)

print(
    'Repaired Increment 37 native FIFO capacity, complete symbolic geometry, '
    'failure diagnostics, and controller retries'
)
