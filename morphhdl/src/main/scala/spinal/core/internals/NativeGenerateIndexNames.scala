package spinal.core.internals

import scala.collection.mutable
import spinal.core._

/** Publish short names only after the exact retained loop identities, operands
  * and native templates have passed structural validation. Internal selectors
  * keep their unique names and opaque tokens throughout those checks.
  */
private[internals] object NativeGenerateIndexNames {
  def publish(component: Component, verilog: String): String = {
    val regions = ParameterizedStructure.regionsOf(component)
    def loops(region: ParameterizedStructure.StructuralRegion): Vector[ParameterizedStructure.StructuralFor] =
      (region match {
        case loop: ParameterizedStructure.StructuralFor => Vector(loop)
        case _ => Vector.empty
      }) ++ region.blocks.flatMap(_.regions).flatMap(loops)
    val allLoops = regions.flatMap(loops)
    def labels(region: ParameterizedStructure.StructuralRegion): Vector[Vector[String]] = {
      val own = region match {
        case loop: ParameterizedStructure.StructuralFor if AutomaticGenerateLabels.owns(component, loop.body) =>
          Vector(Vector(loop.label))
        case branch: ParameterizedStructure.StructuralIf if AutomaticGenerateLabels.owns(component, branch.whenTrue) =>
          Vector(Vector(branch.whenTrueLabel, branch.whenFalseLabel))
        case _ => Vector.empty
      }
      own ++ region.blocks.flatMap(_.regions).flatMap(labels)
    }
    val defaultLabels = regions.flatMap(labels)
    val identifier = "[A-Za-z_][A-Za-z0-9_$]*".r
    val originalLines = verilog.split("\n", -1).toVector
    val labelNames = mutable.LinkedHashMap.empty[String, String]
    val internalLabels = defaultLabels.flatten.toSet
    if (internalLabels.size != defaultLabels.flatten.size)
      ParameterizedVerilogException.fail("SPINAL-GENERATE-LABEL-IDENTITY-MISMATCH",
        "default generate labels lost their unique captured identities", None)
    val labelReserved = mutable.HashSet.empty[String]
    ParameterizedVerilogVecs.mapReferenceCode(originalLines) { code =>
      labelReserved ++= identifier.findAllIn(code).filterNot(internalLabels)
      code
    }
    var ordinal = 0
    defaultLabels.foreach { group =>
      var names = Vector.empty[String]
      do {
        ordinal += 1
        val base = s"g_GEN_$ordinal"
        names = if (group.size == 1) Vector(base) else Vector(base + "_TRUE", base + "_FALSE")
      } while (names.exists(labelReserved) || labelReserved(s"g_GEN_$ordinal"))
      group.zip(names).foreach { case (original, published) => labelNames(original) = published }
      labelReserved ++= names
      labelReserved += s"g_GEN_$ordinal"
    }
    val labeled = ParameterizedVerilogVecs.mapReferenceCode(originalLines) { code =>
      identifier.replaceAllIn(code, token => java.util.regex.Matcher.quoteReplacement(
        labelNames.getOrElse(token.matched, token.matched)))
    }.mkString("\n")
    val automatic = allLoops.filter(loop => loop.finiteIndexToken
      .flatMap(ElabFiniteRange.automaticIndexName(component, _)).contains(loop.indexName))
    if (automatic.isEmpty) return labeled
    val internalNames = automatic.map(_.indexName).toSet
    require(internalNames.size == automatic.size, "automatic loops must retain distinct internal selector identities")
    val lines = labeled.split("\n", -1).toVector
    val reserved = mutable.HashSet.empty[String]
    ParameterizedVerilogVecs.mapReferenceCode(lines) { code =>
      reserved ++= identifier.findAllIn(code).filterNot(internalNames)
      code
    }
    // Reserving declarations in every nested scope also prevents an emitted
    // index from being shadowed by an authored local signal or parameter.
    val names = mutable.LinkedHashMap.empty[String, String]
    def available(enclosing: Set[String]): String = {
      val short = Vector("i", "j", "k", "l", "m", "n")
      (short.iterator ++ Iterator.from(1).map(n => s"i_$n"))
        .find(name => !reserved(name) && !enclosing(name)).get
    }
    def allocate(region: ParameterizedStructure.StructuralRegion, enclosing: Set[String]): Unit = {
      val active = region match {
        case loop: ParameterizedStructure.StructuralFor =>
          val name = if (internalNames(loop.indexName)) {
            val published = available(enclosing)
            names(loop.indexName) = published
            published
          } else loop.indexName
          enclosing + name
        case _ => enclosing
      }
      region.blocks.flatMap(_.regions).foreach(allocate(_, active))
    }
    regions.foreach(allocate(_, Set.empty))
    val declaration = "^\\s*genvar\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*;\\s*$".r
    val declarations = lines.zipWithIndex.collect {
      case (declaration(name), index) if internalNames(name) => name -> index
    }
    if (declarations.map(_._1).sorted != internalNames.toVector.sorted)
      ParameterizedVerilogException.fail("SPINAL-GENERATE-INDEX-DECLARATION-MISMATCH",
        "each retained automatic loop requires exactly one native genvar declaration before naming", None)
    val removed = declarations.map(_._2).toSet
    val first = declarations.map(_._2).min
    val renamed = ParameterizedVerilogVecs.mapReferenceCode(lines) { code =>
      identifier.replaceAllIn(code, token => java.util.regex.Matcher.quoteReplacement(
        names.getOrElse(token.matched, token.matched)))
    }
    renamed.zipWithIndex.flatMap { case (line, index) =>
      val shared = if (index == first) names.values.toVector.distinct.map(name => s"  genvar $name;") else Vector.empty
      shared ++ (if (removed(index)) Vector.empty else Vector(line))
    }.mkString("\n")
  }
}
