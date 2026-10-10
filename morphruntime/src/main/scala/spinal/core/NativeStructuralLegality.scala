package spinal.core

/** Bridge an issued lexical capture handle to the native diagnostic emitter.
  * Activation comes from the completed exact structural path, never labels,
  * default witnesses, or generated Verilog text.
  */
private[core] object NativeStructuralLegality {
  def currentOwner(): NativeSymbolicLegality.Scope = {
    val owner = ParameterizedStructure.currentLexicalOwner("symbolic legality")
    new NativeSymbolicLegality.Scope {
      val component: Component = owner.component
      private def path = ParameterizedStructure.pathOfLexicalOwner(owner)
      private val capturedJoint = ParameterizedStructure.jointPredicatesOfLexicalOwner(owner)
      override def classify(condition: ElabBool, baseline: ElabBool.Truth): ElabBool.Truth =
        if (capturedJoint.isEmpty || baseline != ElabBool.Unknown) baseline
        else ElaborationProductDomain.truthUnderStructuralPredicates(condition.expression, capturedJoint)
      def validate(): Unit = {
        ParameterizedStructure.blockOfLexicalOwner(owner)
        val publishedJoint = path.flatMap { case (region, branch) => region match {
          case value: ParameterizedStructure.StructuralIf =>
            value.predicateDomain.flatMap(_.joint).map(_ -> branch)
          case _ => None
        }}
        require(publishedJoint.size == capturedJoint.size && publishedJoint.zip(capturedJoint).forall {
          case ((actual, branch), (expected, capturedBranch)) =>
            (actual eq expected) && branch == capturedBranch
        }, "legality activation differs from its captured joint owner")
        var child = component
        while (child.parent != null) {
          require(child.parent.children.exists(_ eq child), "legality owner is a detached child")
          child = child.parent
        }
      }
      def admitted(root: ElaborationIntegerParameterRoot, universe: Set[BigInt]): Set[BigInt] = {
        var values = ParameterizedStructure.withLexicalOwnerDomain(owner) {
          ElaborationDomainContext.admitted(root, universe)
        }
        var child = component
        while (child.parent != null) {
          values = values intersect ParameterizedStructure.exactChildDomainOf(
            child.parent, child, root, universe, "symbolic legality", owner.sourceLocation).values
          child = child.parent
        }
        values
      }
      def parameters: Vector[ElaborationIntegerParameter] = path.flatMap(_._1.parameters).distinct
      def roots: Vector[ElaborationIntegerParameterRoot] = path.flatMap(_._1.parameterRoots).distinct

      def wrap(body: String, allocate: String => String): String = {
        path.reverse.foldLeft(body) { case (inside, (region, branch)) =>
          val label = allocate("G_PARAMETER_LEGALITY_ACTIVE")
          val opening = region match {
            case value: ParameterizedStructure.StructuralIf =>
              val condition = value.condition.verilog
              if (branch == 0) s"if ($condition)" else s"if (!($condition))"
            case value: ParameterizedStructure.StructuralCase =>
              val selector = value.selector.verilog
              val condition = if (branch < value.choices.size)
                s"($selector == ${value.choices(branch).value})"
              else value.choices.map(choice => s"($selector != ${choice.value})").mkString(" && ")
              s"if ($condition)"
            case value: ParameterizedStructure.StructuralFor =>
              val index = value.indexName
              s"for ($index = 0; $index < (${value.count.verilog}); $index = $index + 1)"
          }
          s"    $opening begin : $label\n$inside    end\n"
        }
      }
    }
  }
}
