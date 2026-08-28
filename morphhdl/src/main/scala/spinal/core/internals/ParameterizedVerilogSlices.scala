package spinal.core.internals

import java.util.regex.{Matcher, Pattern}

import spinal.core._

/** Generic MorphHDL-owned lowering of exact native parameterized part-selects. */
private[internals] object ParameterizedVerilogSlices {
  private final case class SliceKey(
      sourceName: String,
      witnessLow: BigInt,
      witnessHigh: BigInt
  )

  def rewrite(component: Component, verilog: String): String = {
    val records = ExternalParameterizedSliceRegistry.slicesOf(component)
    if (records.isEmpty) return verilog

    val grouped = records.groupBy { record =>
      val sourceName = requiredName(record)
      SliceKey(
        sourceName,
        record.offset.default,
        record.offset.default + record.width.default - 1
      )
    }
    grouped.collectFirst {
      case (key, values)
          if values
            .map(record => record.offset.verilog -> record.width.verilog)
            .distinct
            .size != 1 =>
        key
    }.foreach { key =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-REWRITE-CONFLICT",
        s"native source '${key.sourceName}[${key.witnessHigh}:${key.witnessLow}]' maps to multiple symbolic part-selects"
      )
    }

    grouped.toVector
      .sortBy { case (key, _) =>
        (-key.sourceName.length, key.witnessLow, key.witnessHigh)
      }
      .foldLeft(verilog) { case (current, (key, values)) =>
        val record = values.head
        val source = Pattern.quote(key.sourceName)
        val high = Pattern.quote(key.witnessHigh.toString)
        val low = Pattern.quote(key.witnessLow.toString)
        val width = Pattern.quote(record.width.default.toString)
        val descending = (
          "(?<![A-Za-z0-9_$])" + source +
            "\\s*\\[\\s*" + high + "\\s*:\\s*" + low + "\\s*\\]"
        ).r
        val indexed = (
          "(?<![A-Za-z0-9_$])" + source +
            "\\s*\\[\\s*" + low + "\\s*\\+:\\s*" + width + "\\s*\\]"
        ).r
        val matches =
          descending.findAllMatchIn(current).size +
            indexed.findAllMatchIn(current).size
        if (matches == 0) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SLICE-REWRITE-MISSING",
            s"native publication has no exact witness part-select '${key.sourceName}[${key.witnessHigh}:${key.witnessLow}]'",
            record.sourceLocation
              .orElse(record.offset.sourceLocation)
              .orElse(record.width.sourceLocation)
          )
        }
        val replacement = Matcher.quoteReplacement(
          s"${key.sourceName}[${record.offset.verilog} +: ${record.width.verilog}]"
        )
        indexed.replaceAllIn(
          descending.replaceAllIn(current, replacement),
          replacement
        )
      }
  }

  private def requiredName(record: ExternalParameterizedSliceRecord): String =
    Option(record.source.getName()).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SLICE-SOURCE-NAME-MISSING",
        "one exact native parameterized slice source has no final emitted name",
        record.sourceLocation
          .orElse(record.offset.sourceLocation)
          .orElse(record.width.sourceLocation)
      )
    }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
