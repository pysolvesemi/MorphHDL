# Expanded-roadmap evidence map

The application roadmap dated 16 October 2026 supersedes the frozen 86-item
baseline for scope and closure policy. Targeted local evidence is sufficient;
old remote-CI requirements in historical development records are not current
closure gates. This map does not turn historical results into fresh executions.

The reviewed starting compiler is `f7135bbd8b1e40b89ee1183de826c90d2f999e31`.
Historical local runs and their source snapshots are recorded in `progress.json`,
`qualification.md`, and `compiler-repair-receipt.json`. New parameter spelling,
packed representation, optional policies and HardType inference require their
new focused receipts before closure. Source inventory is in `source-inventory.md`.

| Roadmap area | Implemented contract / regression evidence | Historical CA relationship |
|---|---|---|
| Staged semantics and helpers | `semantics-v1.md`; `LightweightSyntaxBaselineTests`, typed-control/effect tests; immutable block helpers and exact lexical ownership in `progress.json` | Extends ordinary authoring; does not reopen prior native control fixes |
| Parameter forwarding, arithmetic, errors | `LightweightParameterTests`, `LightweightPortFactoryTests`, `LightweightBooleanRecursiveTests`, `LightweightInferredHelperTests`; exact formal/actual ownership and finite-domain rejection | CA-009/010/011/016/020 native binding/sharing remains the foundation |
| Conditional ownership and drivers | `LightweightStatementChainTests`, `LightweightRecursiveSafetyTests`, `LightweightLocalNamesTests`; complementary branches and rejected illegal effects/escapes | CA-024/027/028 native scope/publication repairs |
| Nested loops and affine access/write coverage | `LightweightAffineTests`, `LightweightAffineWriteTests`, `LightweightNestedWriteTests`, guarded-register tests, retained ownership mutation controls | CA-012/013/047 plus current guarded publication checks |
| Arrays, packing and boundaries | `LightweightWordSliceTests`, nested/record capacity suites, mixed index/write suites; Verilog-2001 packed boundary and lane-zero-first ordering in semantics contract | CA-019/021/048/049/050/054/055 remain historical native repairs |
| Direct recursive Scala components | `LightweightRecursiveTests`, composite/nested/Boolean recursive suites and safety tests; decreasing metric, exact constructor binding and interface ownership | CA-029 onward recursive publication; CA-046 direct-constructor capability |
| Operation and pipeline semantics | balanced-tree suites for adjacent subtraction, signed maximum, Bits operations, Bundle fields, odd-tail bridges and callback registers; reset/soft-reset/enable checks | CA-036–038, CA-051–053; native Vec.reduceBalancedTree is unchanged |
| Hierarchy and naming | hierarchy-capacity/nested-capacity suites, local-name tests and compiler repair receipt; pure-direction aggregate boundaries retain exact geometry | CA-028 scope-local naming; CA-036–038/046–050/054 repaired-source tests |
| Cleanup and formatting | `local_validation_enrolled_a3274b97` records 22 cleanup profiles, four cleaned proofs and four negative controls on each Scala version; f7135 repair receipt records current application generation/lint/unit checks | No product-specific cleanup rules or new Rust rewrite are introduced |
| Ordered priority | `DescendingPriorityProcessTests`, ordered-priority-selection guide and f7135 repair receipt | CA-056 separate structural versus ordered procedural ownership |

Supported subsets remain explicit: uniform-direction recursive aggregate ports;
complete module-owned mixed hardware/scoped row writes; finite authenticated
parameter domains; fixed-result-geometry reduction callbacks. Non-unit ordinary
symbolic traversal remains an explicit rejection. Opaque external callback bodies
are not certified pure. Existing documented restrictions are not claims of
arbitrary Scala or arbitrary recursion support.

The existing native `Vec.reduceBalancedTree` is not replaced. The opt-in recursive
library preserves adjacent-pair operand order, odd-tail bridge placement and
concrete Int bridge levels. Application migration must use that library rather
than retaining a second algorithm.
