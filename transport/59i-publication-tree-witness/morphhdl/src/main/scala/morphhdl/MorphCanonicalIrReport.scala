package morphhdl

import morphhdl.ir.v1.CanonicalIrHandoff

/**
  * Successful single-source generation paired with the validated snapshot
  * captured from the same native elaboration after width normalization and
  * before alias simplification, then released only after inherited validation.
  *
  * `phaseClassNames` is the complete native execution plan observed when the
  * canonical boundary runs, after every configured phase inserter has finished.
  * The handoff profile is the authority for capture completeness. In
  * particular, this initial bounded producer has no exact structured native
  * source-position object, so it leaves source-location fields empty rather
  * than reconstructing them from diagnostic or line-comment strings.
  */
final case class MorphCanonicalIrReport(
    generation: MorphSingleSourceVerilogReport,
    handoff: CanonicalIrHandoff,
    phaseClassNames: Vector[String]
)
